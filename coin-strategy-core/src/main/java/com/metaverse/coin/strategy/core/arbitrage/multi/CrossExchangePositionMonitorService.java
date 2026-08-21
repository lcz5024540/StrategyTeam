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
        this.providers = providers.stream().collect(Collectors.toMap(
                CrossExchangeMarketDataProvider::exchange, Function.identity()));
        this.risk = risk;
        this.paper = paper;
        this.persistence = persistence;
        this.costs = costs == null ? Map.of() : Map.copyOf(costs);
        this.basisRiskBuffer = basisRiskBuffer == null ? BigDecimal.ZERO : basisRiskBuffer;
        this.maximumQuoteAge = maximumQuoteAge;
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
            BigDecimal latestNetYield = remainingNetYield(quotes.longQuote(), quotes.shortQuote());
            BigDecimal deviation = quotes.shortQuote().markPrice()
                    .subtract(quotes.longQuote().markPrice()).abs()
                    .divide(quotes.longQuote().markPrice(), 12, RoundingMode.HALF_UP);
            CrossExchangeRiskService.Assessment exit = risk.shouldExit(position, latestNetYield,
                    deviation, now, true, paper.isPaused());
            if (exit.passed()) {
                closed.add(paper.close(position.positionId(), quotes.longQuote().markPrice(),
                        quotes.shortQuote().markPrice(), now));
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
        return paper.close(positionId, quotes.longQuote().markPrice(),
                quotes.shortQuote().markPrice(), now);
    }

    private LatestQuotes latestQuotes(PaperHedgePosition position, Instant now) {
        NormalizedFundingRateSnapshot longQuote = provider(
                position.opportunity().longQuote().exchange())
                .fundingRate(position.opportunity().symbol());
        NormalizedFundingRateSnapshot shortQuote = provider(
                position.opportunity().shortQuote().exchange())
                .fundingRate(position.opportunity().symbol());
        if (longQuote.isStale(now, maximumQuoteAge) || shortQuote.isStale(now, maximumQuoteAge)) {
            throw new IllegalStateException("latest funding quote is stale");
        }
        return new LatestQuotes(longQuote, shortQuote);
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
            NormalizedFundingRateSnapshot shortQuote) {
        return shortQuote.hourlyFundingRate().subtract(longQuote.hourlyFundingRate())
                .subtract(oneWayCost(longQuote.exchange()))
                .subtract(oneWayCost(shortQuote.exchange()))
                .subtract(basisRiskBuffer);
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
            NormalizedFundingRateSnapshot shortQuote) {
    }
}
