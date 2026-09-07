package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Selects the best executable pair; rejected pairs retain an explicit reason. */
public final class CrossExchangeOpportunitySelector {
    private static final long SECONDS_PER_HOUR = 3_600L;

    public record Limits(Duration maxQuoteAge, Duration maxSettlementSkew, BigDecimal maxMarkPriceDeviation,
                         BigDecimal minNotional, BigDecimal minNetYield, BigDecimal basisRiskBuffer,
                         boolean allowMixedSigns, BigDecimal minimumCostCoverageRatio,
                         BigDecimal maxExecutableBasis, BigDecimal minimumOrderBookNotional,
                         BigDecimal minimumOrderBookCoverageRatio, BigDecimal maximumLegVwapImpact,
                         int expectedHoldingWindows, BigDecimal minimumHorizonNetYield) {
        public Limits(Duration maxQuoteAge, Duration maxSettlementSkew,
                BigDecimal maxMarkPriceDeviation, BigDecimal minNotional, BigDecimal minNetYield,
                BigDecimal basisRiskBuffer, boolean allowMixedSigns) {
            this(maxQuoteAge, maxSettlementSkew, maxMarkPriceDeviation, minNotional, minNetYield,
                    basisRiskBuffer, allowMixedSigns, BigDecimal.ONE,
                    new BigDecimal("0.03"), BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                    1, minNetYield);
        }

        public Limits(Duration maxQuoteAge, Duration maxSettlementSkew,
                BigDecimal maxMarkPriceDeviation, BigDecimal minNotional, BigDecimal minNetYield,
                BigDecimal basisRiskBuffer, boolean allowMixedSigns,
                BigDecimal minimumCostCoverageRatio, BigDecimal maxExecutableBasis) {
            this(maxQuoteAge, maxSettlementSkew, maxMarkPriceDeviation, minNotional, minNetYield,
                    basisRiskBuffer, allowMixedSigns, minimumCostCoverageRatio, maxExecutableBasis,
                    BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 1, minNetYield);
        }

        public Limits(Duration maxQuoteAge, Duration maxSettlementSkew,
                BigDecimal maxMarkPriceDeviation, BigDecimal minNotional, BigDecimal minNetYield,
                BigDecimal basisRiskBuffer, boolean allowMixedSigns,
                BigDecimal minimumCostCoverageRatio, BigDecimal maxExecutableBasis,
                BigDecimal minimumOrderBookNotional, BigDecimal minimumOrderBookCoverageRatio,
                BigDecimal maximumLegVwapImpact) {
            this(maxQuoteAge, maxSettlementSkew, maxMarkPriceDeviation, minNotional, minNetYield,
                    basisRiskBuffer, allowMixedSigns, minimumCostCoverageRatio, maxExecutableBasis,
                    minimumOrderBookNotional, minimumOrderBookCoverageRatio, maximumLegVwapImpact,
                    1, minNetYield);
        }

        public Limits {
            if (expectedHoldingWindows <= 0) {
                throw new IllegalArgumentException("expectedHoldingWindows must be positive");
            }
        }
    }

    public List<CrossExchangeOpportunity> evaluate(List<NormalizedFundingRateSnapshot> rates,
            Map<Exchange, NormalizedOrderBookSnapshot> books, Map<Exchange, ExchangeCost> costs,
            BigDecimal targetNotional, Limits limits, Instant now) {
        List<CrossExchangeOpportunity> result = new ArrayList<>();
        for (int i = 0; i < rates.size(); i++) for (int j = i + 1; j < rates.size(); j++) {
            var a = rates.get(i); var b = rates.get(j);
            if (!a.canonicalSymbol().equals(b.canonicalSymbol()) || a.exchange() == b.exchange()) continue;
            FundingRateYieldProjection.Direction direction = FundingRateYieldProjection.bestDirection(a, b,
                    now, limits.expectedHoldingWindows());
            var low = direction.longQuote();
            var high = direction.shortQuote();
            ExecutionPlan execution = executionPlan(low, high, books, targetNotional,
                    limits.maximumLegVwapImpact());
            String reason = rejection(low, high, books, costs, execution, targetNotional, limits, now);
            BigDecimal hourlySpread = high.hourlyFundingRate().subtract(low.hourlyFundingRate());
            BigDecimal firstWindowFundingYield = FundingRateYieldProjection.projectedSpread(
                    low.rawFundingRate(), low.fundingIntervalHours(), low.nextFundingTime(),
                    high.rawFundingRate(), high.fundingIntervalHours(), high.nextFundingTime(),
                    now);
            BigDecimal horizonFundingYield = direction.projectedSpread();
            BigDecimal tradingCost = tradingCost(low, high, costs);
            BigDecimal riskAdjustedCost = tradingCost.add(limits.basisRiskBuffer());
            BigDecimal horizonNet = horizonFundingYield.subtract(riskAdjustedCost);
            if (reason == null && tradingCost.signum() > 0
                    && firstWindowFundingYield.compareTo(tradingCost.multiply(
                            limits.minimumCostCoverageRatio())) < 0) {
                reason = "COST_COVERAGE_BELOW_THRESHOLD";
            }
            if (reason == null && horizonNet.compareTo(limits.minimumHorizonNetYield()) < 0) {
                reason = "NET_YIELD_BELOW_THRESHOLD";
            }
            Exchange firstLeg = firstLeg(low, high, books);
            NormalizedOrderBookSnapshot longBook = books.get(low.exchange());
            NormalizedOrderBookSnapshot shortBook = books.get(high.exchange());
            result.add(new CrossExchangeOpportunity(UUID.randomUUID().toString(), low.canonicalSymbol(), low, high,
                    hourlySpread, horizonNet, execution.longEntryPrice(), execution.shortEntryPrice(),
                    execution.executableNotional(), firstLeg,
                    reason == null ? CrossExchangeOpportunity.Status.QUALIFIED : CrossExchangeOpportunity.Status.REJECTED,
                    reason, now));
        }
        result.sort(Comparator.comparing(CrossExchangeOpportunity::estimatedNetYield).reversed());
        return result;
    }

    private BigDecimal tradingCost(NormalizedFundingRateSnapshot low,
            NormalizedFundingRateSnapshot high, Map<Exchange, ExchangeCost> costs) {
        if (!costs.containsKey(low.exchange()) || !costs.containsKey(high.exchange())) {
            return BigDecimal.ZERO;
        }
        ExchangeCost longCost = costs.get(low.exchange());
        ExchangeCost shortCost = costs.get(high.exchange());
        return longCost.roundTripTakerCost()
                .add(longCost.amortizedCapitalTransferCost())
                .add(shortCost.roundTripTakerCost())
                .add(shortCost.amortizedCapitalTransferCost());
    }

    private String rejection(NormalizedFundingRateSnapshot low, NormalizedFundingRateSnapshot high,
            Map<Exchange, NormalizedOrderBookSnapshot> books, Map<Exchange, ExchangeCost> costs,
            ExecutionPlan execution, BigDecimal targetNotional, Limits limits, Instant now) {
        if (low.isStale(now, limits.maxQuoteAge()) || high.isStale(now, limits.maxQuoteAge())) return "STALE_FUNDING_QUOTE";
        if (!costs.containsKey(low.exchange()) || !costs.containsKey(high.exchange())) return "MISSING_EXCHANGE_COST";
        if (!books.containsKey(low.exchange()) || !books.containsKey(high.exchange())) return "MISSING_ORDER_BOOK";
        if (low.fundingIntervalHours() == high.fundingIntervalHours()
                && settlementSkew(low, high, now, limits.maxSettlementSkew())
                .compareTo(limits.maxSettlementSkew()) > 0) {
            return "SETTLEMENT_TIME_SKEW";
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
        String depthReason = depthRejection(longBook, shortBook, execution, targetNotional, limits);
        if (depthReason != null) return depthReason;
        if (execution.executableNotional().compareTo(limits.minNotional()) < 0) return "INSUFFICIENT_LIQUIDITY";
        return null;
    }

    private String depthRejection(NormalizedOrderBookSnapshot longBook,
            NormalizedOrderBookSnapshot shortBook, ExecutionPlan execution,
            BigDecimal targetNotional, Limits limits) {
        if (longBook.askLevels().isEmpty() || shortBook.bidLevels().isEmpty()) {
            if (execution.executableNotional().compareTo(limits.minNotional()) < 0) {
                return "MISSING_L2_DEPTH";
            }
            return null;
        }
        BigDecimal longCapacity = notionalWithinImpact(longBook.askLevels(), longBook.bestAsk(), true,
                limits.maximumLegVwapImpact());
        BigDecimal shortCapacity = notionalWithinImpact(shortBook.bidLevels(), shortBook.bestBid(), false,
                limits.maximumLegVwapImpact());
        if (longCapacity.signum() <= 0) {
            return "INSUFFICIENT_LONG_DEPTH_COVERAGE";
        }
        if (shortCapacity.signum() <= 0) {
            return "INSUFFICIENT_SHORT_DEPTH_COVERAGE";
        }
        if (execution.executableNotional().compareTo(limits.minNotional()) < 0) {
            return "INSUFFICIENT_LIQUIDITY";
        }
        if (vwapImpact(execution.longEntryPrice(), longBook.bestAsk())
                .compareTo(limits.maximumLegVwapImpact()) > 0) {
            return "LONG_LEG_VWAP_IMPACT_TOO_HIGH";
        }
        if (vwapImpact(execution.shortEntryPrice(), shortBook.bestBid())
                .compareTo(limits.maximumLegVwapImpact()) > 0) {
            return "SHORT_LEG_VWAP_IMPACT_TOO_HIGH";
        }
        return null;
    }

    private BigDecimal notionalWithinImpact(List<NormalizedOrderBookSnapshot.Level> levels,
            BigDecimal bestPrice, boolean buy, BigDecimal maximumImpact) {
        BigDecimal boundary = buy
                ? bestPrice.multiply(BigDecimal.ONE.add(maximumImpact))
                : bestPrice.multiply(BigDecimal.ONE.subtract(maximumImpact));
        return levels.stream()
                .filter(level -> buy ? level.price().compareTo(boundary) <= 0
                        : level.price().compareTo(boundary) >= 0)
                .map(NormalizedOrderBookSnapshot.Level::notional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal vwapImpact(BigDecimal vwap, BigDecimal bestPrice) {
        return vwap.subtract(bestPrice).abs().divide(bestPrice, 12, RoundingMode.HALF_UP);
    }

    /**
     * Uses L2 only when both legs have it. The size is a common base-asset quantity, so the
     * paper hedge remains delta-neutral even when the two executable prices differ.
     */
    private ExecutionPlan executionPlan(NormalizedFundingRateSnapshot low,
            NormalizedFundingRateSnapshot high, Map<Exchange, NormalizedOrderBookSnapshot> books,
            BigDecimal targetNotional, BigDecimal maximumLegVwapImpact) {
        NormalizedOrderBookSnapshot longBook = books.get(low.exchange());
        NormalizedOrderBookSnapshot shortBook = books.get(high.exchange());
        if (longBook == null || shortBook == null) {
            return new ExecutionPlan(low.markPrice(), high.markPrice(), BigDecimal.ZERO);
        }
        if (longBook.askLevels().isEmpty() || shortBook.bidLevels().isEmpty()) {
            return new ExecutionPlan(longBook.bestAsk(), shortBook.bestBid(),
                    longBook.askNotional().min(shortBook.bidNotional()).min(targetNotional));
        }
        List<NormalizedOrderBookSnapshot.Level> longExecutableLevels = levelsWithinImpact(
                longBook.askLevels(), longBook.bestAsk(), true, maximumLegVwapImpact);
        List<NormalizedOrderBookSnapshot.Level> shortExecutableLevels = levelsWithinImpact(
                shortBook.bidLevels(), shortBook.bestBid(), false, maximumLegVwapImpact);
        if (longExecutableLevels.isEmpty() || shortExecutableLevels.isEmpty()) {
            return new ExecutionPlan(longBook.bestAsk(), shortBook.bestBid(), BigDecimal.ZERO);
        }
        BigDecimal longTargetQuantity = quantityForNotional(longExecutableLevels, targetNotional);
        BigDecimal commonQuantity = longTargetQuantity
                .min(totalQuantity(longExecutableLevels))
                .min(totalQuantity(shortExecutableLevels));
        if (commonQuantity.signum() <= 0) {
            return new ExecutionPlan(longBook.bestAsk(), shortBook.bestBid(), BigDecimal.ZERO);
        }
        BigDecimal longNotional = quoteForQuantity(longExecutableLevels, commonQuantity);
        return new ExecutionPlan(longNotional.divide(commonQuantity, 12, RoundingMode.HALF_UP),
                quoteForQuantity(shortExecutableLevels, commonQuantity)
                        .divide(commonQuantity, 12, RoundingMode.HALF_UP),
                longNotional);
    }

    private Duration settlementSkew(NormalizedFundingRateSnapshot low,
            NormalizedFundingRateSnapshot high, Instant now, Duration maxSettlementSkew) {
        Duration rawSkew = Duration.between(low.nextFundingTime(), high.nextFundingTime()).abs();
        if (rawSkew.compareTo(maxSettlementSkew) <= 0) {
            return rawSkew;
        }
        Instant lowCanonical = nextCanonicalSettlement(now, low.fundingIntervalHours());
        Instant highCanonical = nextCanonicalSettlement(now, high.fundingIntervalHours());
        return Duration.between(lowCanonical, highCanonical).abs();
    }

    private Instant nextCanonicalSettlement(Instant now, int intervalHours) {
        long intervalSeconds = Math.max(1, intervalHours) * SECONDS_PER_HOUR;
        long epochSecond = now.getEpochSecond();
        long nextSlot = (epochSecond / intervalSeconds + 1) * intervalSeconds;
        return Instant.ofEpochSecond(nextSlot);
    }

    private List<NormalizedOrderBookSnapshot.Level> levelsWithinImpact(
            List<NormalizedOrderBookSnapshot.Level> levels, BigDecimal bestPrice,
            boolean buy, BigDecimal maximumImpact) {
        BigDecimal boundary = buy
                ? bestPrice.multiply(BigDecimal.ONE.add(maximumImpact))
                : bestPrice.multiply(BigDecimal.ONE.subtract(maximumImpact));
        return levels.stream()
                .filter(level -> buy ? level.price().compareTo(boundary) <= 0
                        : level.price().compareTo(boundary) >= 0)
                .toList();
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
