package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Selects the best executable pair; rejected pairs retain an explicit reason. */
public final class CrossExchangeOpportunitySelector {
    public record Limits(Duration maxQuoteAge, Duration maxSettlementSkew, BigDecimal maxMarkPriceDeviation,
                         BigDecimal minNotional, BigDecimal minNetYield, BigDecimal basisRiskBuffer,
                         boolean allowMixedSigns, BigDecimal minimumCostCoverageRatio,
                         BigDecimal maxExecutableBasis) {
        public Limits(Duration maxQuoteAge, Duration maxSettlementSkew,
                BigDecimal maxMarkPriceDeviation, BigDecimal minNotional, BigDecimal minNetYield,
                BigDecimal basisRiskBuffer, boolean allowMixedSigns) {
            this(maxQuoteAge, maxSettlementSkew, maxMarkPriceDeviation, minNotional, minNetYield,
                    basisRiskBuffer, allowMixedSigns, new BigDecimal("1.25"),
                    new BigDecimal("0.03"));
        }
    }

    public List<CrossExchangeOpportunity> evaluate(List<NormalizedFundingRateSnapshot> rates,
            Map<Exchange, NormalizedOrderBookSnapshot> books, Map<Exchange, ExchangeCost> costs,
            BigDecimal targetNotional, Limits limits, Instant now) {
        List<CrossExchangeOpportunity> result = new ArrayList<>();
        for (int i = 0; i < rates.size(); i++) for (int j = i + 1; j < rates.size(); j++) {
            var a = rates.get(i); var b = rates.get(j);
            if (!a.canonicalSymbol().equals(b.canonicalSymbol()) || a.exchange() == b.exchange()) continue;
            var low = a.hourlyFundingRate().compareTo(b.hourlyFundingRate()) <= 0 ? a : b;
            var high = low == a ? b : a;
            ExecutionPlan execution = executionPlan(low, high, books, targetNotional);
            String reason = rejection(low, high, books, costs, execution, limits, now);
            BigDecimal hourlySpread = high.hourlyFundingRate().subtract(low.hourlyFundingRate());
            BigDecimal nextSettlementSpread = high.rawFundingRate().subtract(low.rawFundingRate());
            BigDecimal cost = costs.containsKey(low.exchange()) && costs.containsKey(high.exchange())
                    ? costs.get(low.exchange()).roundTripTakerCost().add(costs.get(high.exchange()).roundTripTakerCost()).add(limits.basisRiskBuffer())
                    : BigDecimal.ZERO;
            BigDecimal net = nextSettlementSpread.subtract(cost);
            if (reason == null && cost.signum() > 0
                    && nextSettlementSpread.compareTo(cost.multiply(limits.minimumCostCoverageRatio())) < 0) {
                reason = "COST_COVERAGE_BELOW_THRESHOLD";
            }
            if (reason == null && net.compareTo(limits.minNetYield()) < 0) reason = "NET_YIELD_BELOW_THRESHOLD";
            Exchange firstLeg = firstLeg(low, high, books);
            NormalizedOrderBookSnapshot longBook = books.get(low.exchange());
            NormalizedOrderBookSnapshot shortBook = books.get(high.exchange());
            result.add(new CrossExchangeOpportunity(UUID.randomUUID().toString(), low.canonicalSymbol(), low, high,
                    hourlySpread, net, execution.longEntryPrice(), execution.shortEntryPrice(),
                    execution.executableNotional(), firstLeg,
                    reason == null ? CrossExchangeOpportunity.Status.QUALIFIED : CrossExchangeOpportunity.Status.REJECTED,
                    reason, now));
        }
        result.sort(Comparator.comparing(CrossExchangeOpportunity::estimatedNetYield).reversed());
        return result;
    }

    private String rejection(NormalizedFundingRateSnapshot low, NormalizedFundingRateSnapshot high,
            Map<Exchange, NormalizedOrderBookSnapshot> books, Map<Exchange, ExchangeCost> costs,
            ExecutionPlan execution, Limits limits, Instant now) {
        if (low.isStale(now, limits.maxQuoteAge()) || high.isStale(now, limits.maxQuoteAge())) return "STALE_FUNDING_QUOTE";
        if (!costs.containsKey(low.exchange()) || !costs.containsKey(high.exchange())) return "MISSING_EXCHANGE_COST";
        if (!books.containsKey(low.exchange()) || !books.containsKey(high.exchange())) return "MISSING_ORDER_BOOK";
        if (Duration.between(low.nextFundingTime(), high.nextFundingTime()).abs().compareTo(limits.maxSettlementSkew()) > 0) return "SETTLEMENT_TIME_SKEW";
        if (!limits.allowMixedSigns()
                && low.rawFundingRate().signum() * high.rawFundingRate().signum() < 0) {
            return "MIXED_FUNDING_SIGNS";
        }
        NormalizedOrderBookSnapshot longBook = books.get(low.exchange());
        NormalizedOrderBookSnapshot shortBook = books.get(high.exchange());
        if (longBook.receivedAt().plus(limits.maxQuoteAge()).isBefore(now)
                || shortBook.receivedAt().plus(limits.maxQuoteAge()).isBefore(now)) {
            return "STALE_ORDER_BOOK";
        }
        BigDecimal deviation = high.markPrice().subtract(low.markPrice()).abs().divide(low.markPrice(), 12, RoundingMode.HALF_UP);
        if (deviation.compareTo(limits.maxMarkPriceDeviation()) > 0) return "MARK_PRICE_DEVIATION";
        BigDecimal executableBasis = execution.shortEntryPrice().subtract(execution.longEntryPrice()).abs()
                .divide(execution.longEntryPrice(), 12, RoundingMode.HALF_UP);
        if (executableBasis.compareTo(limits.maxExecutableBasis()) > 0) return "EXECUTABLE_BASIS_DEVIATION";
        if (execution.executableNotional().compareTo(limits.minNotional()) < 0) return "INSUFFICIENT_LIQUIDITY";
        return null;
    }

    /**
     * Uses L2 only when both legs have it. The size is a common base-asset quantity, so the
     * paper hedge remains delta-neutral even when the two executable prices differ.
     */
    private ExecutionPlan executionPlan(NormalizedFundingRateSnapshot low,
            NormalizedFundingRateSnapshot high, Map<Exchange, NormalizedOrderBookSnapshot> books,
            BigDecimal targetNotional) {
        NormalizedOrderBookSnapshot longBook = books.get(low.exchange());
        NormalizedOrderBookSnapshot shortBook = books.get(high.exchange());
        if (longBook == null || shortBook == null) {
            return new ExecutionPlan(low.markPrice(), high.markPrice(), BigDecimal.ZERO);
        }
        if (longBook.askLevels().isEmpty() || shortBook.bidLevels().isEmpty()) {
            return new ExecutionPlan(longBook.bestAsk(), shortBook.bestBid(),
                    longBook.askNotional().min(shortBook.bidNotional()).min(targetNotional));
        }
        BigDecimal longTargetQuantity = quantityForNotional(longBook.askLevels(), targetNotional);
        BigDecimal commonQuantity = longTargetQuantity
                .min(totalQuantity(longBook.askLevels()))
                .min(totalQuantity(shortBook.bidLevels()));
        if (commonQuantity.signum() <= 0) {
            return new ExecutionPlan(longBook.bestAsk(), shortBook.bestBid(), BigDecimal.ZERO);
        }
        BigDecimal longNotional = quoteForQuantity(longBook.askLevels(), commonQuantity);
        return new ExecutionPlan(longNotional.divide(commonQuantity, 12, RoundingMode.HALF_UP),
                quoteForQuantity(shortBook.bidLevels(), commonQuantity)
                        .divide(commonQuantity, 12, RoundingMode.HALF_UP),
                longNotional);
    }

    private BigDecimal quantityForNotional(List<NormalizedOrderBookSnapshot.Level> levels,
            BigDecimal targetNotional) {
        BigDecimal remaining = targetNotional;
        BigDecimal quantity = BigDecimal.ZERO;
        for (NormalizedOrderBookSnapshot.Level level : levels) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal filledNotional = level.notional().min(remaining);
            quantity = quantity.add(filledNotional.divide(level.price(), 18, RoundingMode.DOWN));
            remaining = remaining.subtract(filledNotional);
        }
        return quantity;
    }

    private BigDecimal totalQuantity(List<NormalizedOrderBookSnapshot.Level> levels) {
        return levels.stream().map(level -> level.notional().divide(level.price(), 18,
                RoundingMode.DOWN)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal quoteForQuantity(List<NormalizedOrderBookSnapshot.Level> levels,
            BigDecimal quantity) {
        BigDecimal remaining = quantity;
        BigDecimal quote = BigDecimal.ZERO;
        for (NormalizedOrderBookSnapshot.Level level : levels) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal available = level.notional().divide(level.price(), 18, RoundingMode.DOWN);
            BigDecimal filledQuantity = available.min(remaining);
            quote = quote.add(filledQuantity.multiply(level.price()));
            remaining = remaining.subtract(filledQuantity);
        }
        if (remaining.signum() > 0) {
            throw new IllegalStateException("order book depth is insufficient for planned quantity");
        }
        return quote;
    }

    private Exchange firstLeg(NormalizedFundingRateSnapshot low,
            NormalizedFundingRateSnapshot high,
            Map<Exchange, NormalizedOrderBookSnapshot> books) {
        NormalizedOrderBookSnapshot longBook = books.get(low.exchange());
        NormalizedOrderBookSnapshot shortBook = books.get(high.exchange());
        if (longBook == null || shortBook == null) {
            return low.exchange();
        }
        return longBook.askNotional().compareTo(shortBook.bidNotional()) <= 0
                ? low.exchange() : high.exchange();
    }

    private record ExecutionPlan(BigDecimal longEntryPrice, BigDecimal shortEntryPrice,
            BigDecimal executableNotional) { }
}
