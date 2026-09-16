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
    private static final long SECONDS_PER_HOUR = 3_600L;

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
                recordUnavailableMarketData(position, exception, now);
                continue;
            }
            BigDecimal latestNetYield = remainingIncrementalYield(
                    quotes.longQuote(), quotes.shortQuote(), now);
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
        NormalizedFundingRateSnapshot longQuote = fundingRate(
                position.opportunity().longQuote().exchange(), position.opportunity().symbol());
        NormalizedFundingRateSnapshot shortQuote = fundingRate(
                position.opportunity().shortQuote().exchange(), position.opportunity().symbol());
        NormalizedOrderBookSnapshot longBook = orderBook(position.opportunity().longQuote().exchange(),
                position.opportunity().symbol());
        NormalizedOrderBookSnapshot shortBook = orderBook(position.opportunity().shortQuote().exchange(),
                position.opportunity().symbol());
        if (longQuote == null) {
            throw new MarketDataUnavailableException(position.opportunity().longQuote().exchange(),
                    position.opportunity().symbol(), "latest funding quote is missing");
        }
        if (shortQuote == null) {
            throw new MarketDataUnavailableException(position.opportunity().shortQuote().exchange(),
                    position.opportunity().symbol(), "latest funding quote is missing");
        }
        if (longQuote.isStale(now, maximumQuoteAge)) {
            throw new MarketDataUnavailableException(longQuote.exchange(), position.opportunity().symbol(),
                    "latest funding quote is stale");
        }
        if (shortQuote.isStale(now, maximumQuoteAge)) {
            throw new MarketDataUnavailableException(shortQuote.exchange(), position.opportunity().symbol(),
                    "latest funding quote is stale");
        }
        if (longBook == null || longBook.receivedAt().plus(maximumQuoteAge).isBefore(now)) {
            throw new MarketDataUnavailableException(position.opportunity().longQuote().exchange(),
                    position.opportunity().symbol(), "latest order book is missing or stale");
        }
        if (shortBook == null || shortBook.receivedAt().plus(maximumQuoteAge).isBefore(now)) {
            throw new MarketDataUnavailableException(position.opportunity().shortQuote().exchange(),
                    position.opportunity().symbol(), "latest order book is missing or stale");
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

    private void recordUnavailableMarketData(PaperHedgePosition position, RuntimeException exception,
            Instant now) {
        if (exception instanceof MarketDataUnavailableException unavailable) {
            paper.openScopedCircuit(unavailable.exchange(), unavailable.symbol(), now);
        } else {
            paper.openCircuit();
        }
        if (persistence != null) {
            persistence.saveRisk(position.positionId(), position.opportunity().opportunityId(),
                    "MARKET_DATA", false, exception.getMessage());
        }
    }

    /**
     * Expected value of continuing to hold from now until the next funding horizon.
     *
     * <p>Close fees are deliberately excluded: they are payable whether the position closes now
     * or later, so subtracting the full fee here would double count an unavoidable cost in the
     * hold-vs-close decision. Actual close fees remain part of realized Paper PnL.</p>
     */
    private BigDecimal remainingIncrementalYield(NormalizedFundingRateSnapshot longQuote,
            NormalizedFundingRateSnapshot shortQuote, Instant now) {
        return FundingRateYieldProjection.projectedSpread(longQuote, shortQuote, now)
                .subtract(basisRiskBuffer);
    }

    private boolean settlementAligned(LatestQuotes quotes) {
        if (quotes.longQuote().fundingIntervalHours() != quotes.shortQuote().fundingIntervalHours()) {
            return true;
        }
        Duration rawSkew = Duration.between(quotes.longQuote().nextFundingTime(),
                quotes.shortQuote().nextFundingTime()).abs();
        if (rawSkew.compareTo(maximumSettlementSkew) <= 0) {
            return true;
        }
        Instant now = quotes.longQuote().receivedAt().isAfter(quotes.shortQuote().receivedAt())
                ? quotes.longQuote().receivedAt() : quotes.shortQuote().receivedAt();
        Instant longCanonical = nextCanonicalSettlement(now, quotes.longQuote().fundingIntervalHours());
        Instant shortCanonical = nextCanonicalSettlement(now, quotes.shortQuote().fundingIntervalHours());
        return Duration.between(longCanonical, shortCanonical).abs()
                .compareTo(maximumSettlementSkew) <= 0;
    }

    private Instant nextCanonicalSettlement(Instant now, int intervalHours) {
        long intervalSeconds = Math.max(1, intervalHours) * SECONDS_PER_HOUR;
        long epochSecond = now.getEpochSecond();
        long nextSlot = (epochSecond / intervalSeconds + 1) * intervalSeconds;
        return Instant.ofEpochSecond(nextSlot);
    }

    private void recordExit(PaperHedgePosition position, String reason) {
        if (persistence != null) {
            persistence.saveRisk(position.positionId(), position.opportunity().opportunityId(),
                    "POSITION_MONITOR", false, reason);
        }
    }

    private CrossExchangeMarketDataProvider provider(Exchange exchange) {
        CrossExchangeMarketDataProvider provider = providers.get(exchange);
        if (provider == null) {
            throw new IllegalStateException("market data provider is unavailable: " + exchange);
        }
        return provider;
    }

    private NormalizedFundingRateSnapshot fundingRate(Exchange exchange, String symbol) {
        try {
            return provider(exchange).fundingRate(symbol);
        } catch (RuntimeException exception) {
            throw new MarketDataUnavailableException(exchange, symbol, exception.getMessage(), exception);
        }
    }

    private NormalizedOrderBookSnapshot orderBook(Exchange exchange, String symbol) {
        try {
            return provider(exchange).orderBook(symbol, orderBookDepth);
        } catch (RuntimeException exception) {
            throw new MarketDataUnavailableException(exchange, symbol, exception.getMessage(), exception);
        }
    }

    private record LatestQuotes(NormalizedFundingRateSnapshot longQuote,
            NormalizedFundingRateSnapshot shortQuote, NormalizedOrderBookSnapshot longBook,
            NormalizedOrderBookSnapshot shortBook) {
    }

    private static class MarketDataUnavailableException extends RuntimeException {
        private final Exchange exchange;
        private final String symbol;

        MarketDataUnavailableException(Exchange exchange, String symbol, String message) {
            super(message);
            this.exchange = exchange;
            this.symbol = symbol;
        }

        MarketDataUnavailableException(Exchange exchange, String symbol, String message,
                RuntimeException cause) {
            super(message, cause);
            this.exchange = exchange;
            this.symbol = symbol;
        }

        Exchange exchange() {
            return exchange;
        }

        String symbol() {
            return symbol;
        }
    }
}
