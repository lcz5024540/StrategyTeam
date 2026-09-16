package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sizes Paper-only hedges from observed mark-price risk and executable book capacity. */
public final class FundingPairRiskSizer {
    public record Limits(BigDecimal capitalPerExchange, BigDecimal maximumLeverage,
            BigDecimal maintenanceMarginRate, BigDecimal minimumLiquidationDistance,
            BigDecimal unknownSymbolLiquidationDistance, BigDecimal maximumLiquidationDistance,
            BigDecimal volatilityMultiplier, BigDecimal jumpMultiplier, Duration minimumHistory,
            Duration lookback, BigDecimal orderBookCoverageRatio, BigDecimal maximumLegVwapImpact) { }

    public record Sizing(BigDecimal targetNotional, BigDecimal liquidationDistance,
            BigDecimal riskAdjustedYield) { }

    private final Limits limits;
    private final Map<String, Deque<PricePoint>> history = new ConcurrentHashMap<>();

    public FundingPairRiskSizer(Limits limits) {
        this.limits = limits;
    }

    public void record(Collection<NormalizedFundingRateSnapshot> rates, Instant now) {
        for (NormalizedFundingRateSnapshot rate : rates) {
            String key = rate.exchange().name() + ':' + rate.canonicalSymbol();
            Deque<PricePoint> points = history.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            synchronized (points) {
                points.addLast(new PricePoint(now, rate.markPrice()));
                Instant cutoff = now.minus(limits.lookback());
                while (!points.isEmpty() && points.peekFirst().at().isBefore(cutoff)) {
                    points.removeFirst();
                }
            }
        }
    }

    public Sizing size(NormalizedFundingRateSnapshot low, NormalizedFundingRateSnapshot high,
            Map<Exchange, NormalizedOrderBookSnapshot> books, Map<Exchange, ExchangeCost> costs,
            BigDecimal projectedNetYield) {
        BigDecimal distance = liquidationDistance(low).max(liquidationDistance(high));
        BigDecimal worstEntryCost = costs.get(low.exchange()).oneWayTakerCost()
                .max(costs.get(high.exchange()).oneWayTakerCost());
        BigDecimal marginBound = limits.capitalPerExchange().divide(distance
                .add(limits.maintenanceMarginRate()).add(worstEntryCost), 12, RoundingMode.DOWN);
        BigDecimal leverageBound = limits.capitalPerExchange().multiply(limits.maximumLeverage());
        BigDecimal liquidityBound = capacity(books.get(low.exchange()).askLevels(),
                books.get(low.exchange()).bestAsk(), true)
                .min(capacity(books.get(high.exchange()).bidLevels(),
                        books.get(high.exchange()).bestBid(), false));
        BigDecimal target = marginBound.min(leverageBound).min(liquidityBound.divide(
                limits.orderBookCoverageRatio(), 12, RoundingMode.DOWN));
        BigDecimal riskAdjustedYield = projectedNetYield.divide(distance, 12, RoundingMode.HALF_UP);
        return new Sizing(target, distance, riskAdjustedYield);
    }

    public BigDecimal riskAdjustedYield(CrossExchangeOpportunity opportunity) {
        BigDecimal distance = liquidationDistance(opportunity.longQuote())
                .max(liquidationDistance(opportunity.shortQuote()));
        return opportunity.estimatedNetYield().divide(distance, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal liquidationDistance(NormalizedFundingRateSnapshot quote) {
        Deque<PricePoint> points = history.get(quote.exchange().name() + ':' + quote.canonicalSymbol());
        if (points == null || points.isEmpty()) {
            return limits.unknownSymbolLiquidationDistance();
        }
        synchronized (points) {
            Instant newest = points.peekLast().at();
            if (Duration.between(points.peekFirst().at(), newest).compareTo(limits.minimumHistory()) < 0) {
                return limits.unknownSymbolLiquidationDistance();
            }
            BigDecimal maximumReturn = BigDecimal.ZERO;
            BigDecimal oneHourReturn = BigDecimal.ZERO;
            PricePoint previous = null;
            for (PricePoint point : points) {
                if (previous != null) {
                    BigDecimal change = point.price().subtract(previous.price()).abs()
                            .divide(previous.price(), 12, RoundingMode.HALF_UP);
                    maximumReturn = maximumReturn.max(change);
                    if (!point.at().isBefore(newest.minus(Duration.ofHours(1)))) {
                        oneHourReturn = oneHourReturn.max(change);
                    }
                }
                previous = point;
            }
            BigDecimal dynamic = maximumReturn.multiply(limits.volatilityMultiplier())
                    .max(oneHourReturn.multiply(limits.jumpMultiplier()))
                    .max(limits.minimumLiquidationDistance());
            return dynamic.min(limits.maximumLiquidationDistance());
        }
    }

    private BigDecimal capacity(java.util.List<NormalizedOrderBookSnapshot.Level> levels,
            BigDecimal bestPrice, boolean buy) {
        BigDecimal boundary = buy
                ? bestPrice.multiply(BigDecimal.ONE.add(limits.maximumLegVwapImpact()))
                : bestPrice.multiply(BigDecimal.ONE.subtract(limits.maximumLegVwapImpact()));
        return levels.stream().filter(level -> buy ? level.price().compareTo(boundary) <= 0
                        : level.price().compareTo(boundary) >= 0)
                .map(NormalizedOrderBookSnapshot.Level::notional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record PricePoint(Instant at, BigDecimal price) { }
}
