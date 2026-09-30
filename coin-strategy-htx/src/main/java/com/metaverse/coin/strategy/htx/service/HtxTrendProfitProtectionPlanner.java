package com.metaverse.coin.strategy.htx.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes an auditable profit-protection price from durable MFE facts.
 * It has no exchange side effects. The v4 exit decision engine consumes this price and owns
 * any resulting order; this planner never submits an HTX order directly.
 */
public final class HtxTrendProfitProtectionPlanner {

    public Plan evaluate(Input input) {
        if (input == null || !valid(input)) {
            return Plan.unavailable();
        }
        BigDecimal bestPrice = HtxTrendExitRules.LONG.equals(input.side()) ? input.highestPrice() : input.lowestPrice();
        BigDecimal favorableMove = HtxTrendExitRules.LONG.equals(input.side())
                ? bestPrice.subtract(input.entryPrice()).max(BigDecimal.ZERO)
                : input.entryPrice().subtract(bestPrice).max(BigDecimal.ZERO);
        BigDecimal exposure = input.contractSize().multiply(BigDecimal.valueOf(input.remainingContracts()));
        BigDecimal entryFee = zero(input.entryFee());
        BigDecimal closeFeeAtBest = scaleCloseFee(input.estimatedCloseFee(), input.referencePrice(), bestPrice);
        BigDecimal mfeNet = favorableMove.multiply(exposure).subtract(entryFee).subtract(closeFeeAtBest);
        BigDecimal costDistance = entryFee.add(scaleCloseFee(input.estimatedCloseFee(), input.referencePrice(), input.entryPrice()))
                .divide(exposure, 12, RoundingMode.UP);
        BigDecimal costFloorDistance = costDistance.add(zero(input.atr()).multiply(input.policy().costFloorAtrMultiplier()));
        BigDecimal allowedGiveback = max(zero(input.atr()).multiply(input.policy().noiseAtrMultiplier()),
                favorableMove.multiply(BigDecimal.ONE.subtract(input.policy().mfeLockRatio())));
        BigDecimal mfeProtectionPrice = HtxTrendExitRules.LONG.equals(input.side())
                ? bestPrice.subtract(allowedGiveback) : bestPrice.add(allowedGiveback);
        BigDecimal costFloorPrice = HtxTrendExitRules.LONG.equals(input.side())
                ? input.entryPrice().add(costFloorDistance) : input.entryPrice().subtract(costFloorDistance);
        BigDecimal candidate = tighten(input.side(), input.existingProtectionPrice(),
                tighten(input.side(), costFloorPrice, mfeProtectionPrice));
        BigDecimal projectedNet = netAt(input, candidate);
        BigDecimal captureRatio = mfeNet.signum() > 0 ? projectedNet.max(BigDecimal.ZERO)
                .divide(mfeNet, 12, RoundingMode.DOWN) : BigDecimal.ZERO;
        boolean armed = favorableMove.compareTo(max(zero(input.atr()).multiply(input.policy().armAtrMultiplier()), costFloorDistance)) >= 0
                && mfeNet.compareTo(scaleCloseFee(input.estimatedCloseFee(), input.referencePrice(), bestPrice)
                .multiply(input.policy().minNetFeeMultiple())) >= 0;
        String mode = candidate.compareTo(costFloorPrice) == 0 ? "COST_FLOOR" : "MFE_LOCK";
        return new Plan(armed, bestPrice, mfeNet, costFloorPrice, mfeProtectionPrice, candidate, projectedNet,
                captureRatio, allowedGiveback, mode);
    }

    private static BigDecimal netAt(Input input, BigDecimal price) {
        BigDecimal move = HtxTrendExitRules.LONG.equals(input.side()) ? price.subtract(input.entryPrice())
                : input.entryPrice().subtract(price);
        return move.multiply(input.contractSize()).multiply(BigDecimal.valueOf(input.remainingContracts()))
                .subtract(zero(input.entryFee()))
                .subtract(scaleCloseFee(input.estimatedCloseFee(), input.referencePrice(), price));
    }

    private static BigDecimal scaleCloseFee(BigDecimal fee, BigDecimal reference, BigDecimal price) {
        if (!positive(fee) || !positive(reference) || !positive(price)) return zero(fee);
        return fee.multiply(price).divide(reference, 12, RoundingMode.UP);
    }

    private static BigDecimal tighten(String side, BigDecimal current, BigDecimal candidate) {
        if (!positive(current)) return candidate;
        if (!positive(candidate)) return current;
        return HtxTrendExitRules.LONG.equals(side) ? current.max(candidate) : current.min(candidate);
    }

    private static boolean valid(Input input) {
        return (HtxTrendExitRules.LONG.equals(input.side()) || HtxTrendExitRules.SHORT.equals(input.side()))
                && positive(input.entryPrice()) && positive(input.contractSize()) && input.remainingContracts() > 0
                && positive(input.highestPrice()) && positive(input.lowestPrice()) && positive(input.referencePrice());
    }

    private static BigDecimal max(BigDecimal first, BigDecimal second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    public record Input(String side, long remainingContracts, BigDecimal contractSize, BigDecimal entryPrice,
                        BigDecimal highestPrice, BigDecimal lowestPrice, BigDecimal referencePrice, BigDecimal atr,
                        BigDecimal entryFee, BigDecimal estimatedCloseFee, BigDecimal existingProtectionPrice,
                        Policy policy) {
        public Input {
            policy = policy == null ? Policy.defaults() : policy.normalized();
        }
    }

    public record Policy(BigDecimal armAtrMultiplier, BigDecimal costFloorAtrMultiplier,
                         BigDecimal noiseAtrMultiplier, BigDecimal mfeLockRatio, BigDecimal minNetFeeMultiple) {
        public static Policy defaults() {
            return new Policy(new BigDecimal("0.50"), new BigDecimal("0.15"), new BigDecimal("0.40"),
                    new BigDecimal("0.50"), new BigDecimal("2.00"));
        }
        Policy normalized() {
            Policy d = defaults();
            return new Policy(nonNegativeOr(armAtrMultiplier, d.armAtrMultiplier),
                    nonNegativeOr(costFloorAtrMultiplier, d.costFloorAtrMultiplier),
                    nonNegativeOr(noiseAtrMultiplier, d.noiseAtrMultiplier), boundedRatioOr(mfeLockRatio, d.mfeLockRatio),
                    nonNegativeOr(minNetFeeMultiple, d.minNetFeeMultiple));
        }
        private static BigDecimal nonNegativeOr(BigDecimal value, BigDecimal fallback) {
            return value == null || value.signum() < 0 ? fallback : value;
        }
        private static BigDecimal boundedRatioOr(BigDecimal value, BigDecimal fallback) {
            return value == null || value.signum() <= 0 || value.compareTo(BigDecimal.ONE) >= 0 ? fallback : value;
        }
    }

    public record Plan(boolean armed, BigDecimal mfePrice, BigDecimal mfeNetPnl, BigDecimal costFloorPrice,
                       BigDecimal mfeProtectionPrice, BigDecimal protectionPrice, BigDecimal projectedLockedNetPnl,
                       BigDecimal mfeCaptureRatio, BigDecimal allowedGiveback, String mode) {
        static Plan unavailable() {
            return new Plan(false, null, null, null, null, null, null, null, null, "UNAVAILABLE");
        }
    }
}
