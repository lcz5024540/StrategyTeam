package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CrossExchangePositionMonitorService {
    private final Map<Exchange, CrossExchangeMarketDataProvider> providers;
    private final CrossExchangeRiskService risk;
    private final CrossExchangePaperTradingService paper;
    private final CrossExchangeArbitragePersistenceService persistence;
    private final Map<Exchange, ExchangeCost> costs;
    private final BigDecimal basisRiskBuffer;
    private final Duration maximumQuoteAge;
    private final Duration maximumSettlementSkew;
    private final int orderBookDepth;

    public CrossExchangePositionMonitorService(List<CrossExchangeMarketDataProvider> providers,
            CrossExchangeRiskService risk, CrossExchangePaperTradingService paper) {
        this(providers, risk, paper, null, Map.of(), BigDecimal.ZERO, Duration.ofSeconds(30));
    }

    public CrossExchangePositionMonitorService(List<CrossExchangeMarketDataProvider> providers,
            CrossExchangeRiskService risk, CrossExchangePaperTradingService paper,
            CrossExchangeArbitragePersistenceService persistence) {
        this(providers, risk, paper, persistence, Map.of(), BigDecimal.ZERO, Duration.ofSeconds(30));
    }

    public CrossExchangePositionMonitorService(List<CrossExchangeMarketDataProvider> providers,
            CrossExchangeRiskService risk, CrossExchangePaperTradingService paper,
            CrossExchangeArbitragePersistenceService persistence, Map<Exchange, ExchangeCost> costs,
            BigDecimal basisRiskBuffer) {
        this(providers, risk, paper, persistence, costs, basisRiskBuffer, Duration.ofSeconds(30));
    }

    public CrossExchangePositionMonitorService(List<CrossExchangeMarketDataProvider> providers,
            CrossExchangeRiskService risk, CrossExchangePaperTradingService paper,
            CrossExchangeArbitragePersistenceService persistence, Map<Exchange, ExchangeCost> costs,
            BigDecimal basisRiskBuffer, Duration maximumQuoteAge) {
        this(providers, risk, paper, persistence, costs, basisRiskBuffer, maximumQuoteAge,
                Duration.ofMinutes(5));
    }

    public CrossExchangePositionMonitorService(List<CrossExchangeMarketDataProvider> providers,
            CrossExchangeRiskService risk, CrossExchangePaperTradingService paper,
            CrossExchangeArbitragePersistenceService persistence, Map<Exchange, ExchangeCost> costs,
            BigDecimal basisRiskBuffer, Duration maximumQuoteAge, Duration maximumSettlementSkew) {
        this(providers, risk, paper, persistence, costs, basisRiskBuffer, maximumQuoteAge,
                maximumSettlementSkew, 100);
    }

    public CrossExchangePositionMonitorService(List<CrossExchangeMarketDataProvider> providers,
            CrossExchangeRiskService risk, CrossExchangePaperTradingService paper,
            CrossExchangeArbitragePersistenceService persistence, Map<Exchange, ExchangeCost> costs,
            BigDecimal basisRiskBuffer, Duration maximumQuoteAge, Duration maximumSettlementSkew,
            int orderBookDepth) {
        this.providers = providers.stream().collect(Collectors.toMap(
                CrossExchangeMarketDataProvider::exchange, Function.identity()));
        this.risk = risk;
        this.paper = paper;
        this.persistence = persistence;
        this.costs = costs == null ? Map.of() : Map.copyOf(costs);
        this.basisRiskBuffer = basisRiskBuffer == null ? BigDecimal.ZERO : basisRiskBuffer;
        this.maximumQuoteAge = maximumQuoteAge;
        this.maximumSettlementSkew = maximumSettlementSkew;
        if (orderBookDepth <= 0) {
            throw new IllegalArgumentException("order book depth must be positive");
        }
        this.orderBookDepth = orderBookDepth;
    }

    public List<PaperHedgePosition> monitor(Instant now) {
        List<PaperHedgePosition> closed = new ArrayList<>();
        for (PaperHedgePosition position : new ArrayList<>(paper.positions())) {
            if (position.status() == PaperHedgePosition.Status.MANUAL_INTERVENTION) {
                monitorNakedExposure(position, now);
                continue;
            }
            if (position.status() != PaperHedgePosition.Status.OPENED) {
                continue;
            }
            LatestQuotes quotes;
            try {
                quotes = latestQuotes(position, now);
            } catch (RuntimeException exception) {
                recordUnavailableMarketData(position, exception);
                continue;
            }
            BigDecimal latestNetYield = remainingNetYield(quotes.longQuote(), quotes.shortQuote(), now);
            BigDecimal deviation = quotes.shortQuote().markPrice()
                    .subtract(quotes.longQuote().markPrice()).abs()
                    .divide(quotes.longQuote().markPrice(), 12, RoundingMode.HALF_UP);
            CrossExchangeRiskService.Assessment exit = risk.shouldExit(position, latestNetYield,
                    deviation, now, true, paper.isPaused(), settlementAligned(quotes));
            if (exit.passed()) {
                recordExit(position, exit.reason());
                closed.add(closeWithDepth(position, quotes, now));
            }
        }
        return closed;
    }

    public PaperHedgePosition closeAtLatest(String positionId, Instant now) {
        PaperHedgePosition position = paper.positions().stream()
                .filter(item -> item.positionId().equals(positionId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("position is not open"));
        LatestQuotes quotes = latestQuotes(position, now);
        return closeWithDepth(position, quotes, now);
    }

    private LatestQuotes latestQuotes(PaperHedgePosition position, Instant now) {
        NormalizedFundingRateSnapshot longQuote = provider(
                position.opportunity().longQuote().exchange())
                .fundingRate(position.opportunity().symbol());
        NormalizedFundingRateSnapshot shortQuote = provider(
                position.opportunity().shortQuote().exchange())
                .fundingRate(position.opportunity().symbol());
        NormalizedOrderBookSnapshot longBook = provider(position.opportunity().longQuote().exchange())
                .orderBook(position.opportunity().symbol(), orderBookDepth);
        NormalizedOrderBookSnapshot shortBook = provider(position.opportunity().shortQuote().exchange())
                .orderBook(position.opportunity().symbol(), orderBookDepth);
        if (longQuote.isStale(now, maximumQuoteAge) || shortQuote.isStale(now, maximumQuoteAge)) {
            throw new IllegalStateException("latest funding quote is stale");
        }
        if (longBook == null || shortBook == null
                || longBook.receivedAt().plus(maximumQuoteAge).isBefore(now)
                || shortBook.receivedAt().plus(maximumQuoteAge).isBefore(now)) {
            throw new IllegalStateException("latest order book is missing or stale");
        }
        return new LatestQuotes(longQuote, shortQuote, longBook, shortBook);
    }

    private PaperHedgePosition closeWithDepth(PaperHedgePosition position, LatestQuotes quotes, Instant now) {
        BigDecimal quantity = position.hedgedQuantity();
        OrderBookSweep.Result longExit = OrderBookSweep.forQuantity(
                quotes.longBook().bidLevels(), quantity, quotes.longBook().bestBid());
        OrderBookSweep.Result shortExit = OrderBookSweep.forQuantity(
                quotes.shortBook().askLevels(), quantity, quotes.shortBook().bestAsk());
        return paper.close(position.positionId(), longExit.vwap(), shortExit.vwap(), now);
    }

    private void monitorNakedExposure(PaperHedgePosition position, Instant now) {
        CrossExchangeRiskService.Assessment naked = risk.nakedExposure(position, now);
        if (!naked.passed()) {
            return;
        }
        paper.openCircuit();
        if (persistence != null) {
            persistence.saveRisk(position.positionId(), position.opportunity().opportunityId(),
                    "NAKED_EXPOSURE", false, naked.reason());
        }
    }

    private void recordUnavailableMarketData(PaperHedgePosition position, RuntimeException exception) {
        paper.openCircuit();
        if (persistence != null) {
            persistence.saveRisk(position.positionId(), position.opportunity().opportunityId(),
                    "MARKET_DATA", false, exception.getMessage());
        }
    }

    private BigDecimal remainingNetYield(NormalizedFundingRateSnapshot longQuote,
            NormalizedFundingRateSnapshot shortQuote, Instant now) {
        return FundingRateYieldProjection.projectedSpread(longQuote, shortQuote, now)
                .subtract(oneWayCost(longQuote.exchange()))
                .subtract(oneWayCost(shortQuote.exchange()))
                .subtract(basisRiskBuffer);
    }

    private boolean settlementAligned(LatestQuotes quotes) {
        if (quotes.longQuote().fundingIntervalHours() != quotes.shortQuote().fundingIntervalHours()) {
            return true;
        }
        return Duration.between(quotes.longQuote().nextFundingTime(),
                quotes.shortQuote().nextFundingTime()).abs().compareTo(maximumSettlementSkew) <= 0;
    }

    private void recordExit(PaperHedgePosition position, String reason) {
        if (persistence != null) {
            persistence.saveRisk(position.positionId(), position.opportunity().opportunityId(),
                    "POSITION_MONITOR", false, reason);
        }
    }

    private BigDecimal oneWayCost(Exchange exchange) {
        ExchangeCost cost = costs.get(exchange);
        return cost == null ? BigDecimal.ZERO : cost.oneWayTakerCost();
    }

    private CrossExchangeMarketDataProvider provider(Exchange exchange) {
        CrossExchangeMarketDataProvider provider = providers.get(exchange);
        if (provider == null) {
            throw new IllegalStateException("market data provider is unavailable: " + exchange);
        }
        return provider;
    }

    private record LatestQuotes(NormalizedFundingRateSnapshot longQuote,
            NormalizedFundingRateSnapshot shortQuote, NormalizedOrderBookSnapshot longBook,
            NormalizedOrderBookSnapshot shortBook) {
    }
}
