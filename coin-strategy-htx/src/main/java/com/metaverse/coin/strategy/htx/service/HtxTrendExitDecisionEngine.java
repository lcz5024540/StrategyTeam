package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Pure, model-routed exit decision engine. It does not call HTX and does not persist data.
 *
 * <p>The first live policy is Fibonacci-only. A profitable position is never reduced merely because it is
 * profitable: it needs an adverse move beyond current ATR noise plus deteriorating continuation evidence.
 * Once a runner is armed, the arm is durable and a crossed protection price is actionable without requiring
 * the current price to still be beyond its original 1R target.</p>
 */
public final class HtxTrendExitDecisionEngine {

    public static final String VERSION = "fib-exit-engine-v2";
    public static final String FIB_PULLBACK_CONTINUATION = "FIB_PULLBACK_CONTINUATION";

    public Decision evaluate(Input input) {
        if (input == null || !FIB_PULLBACK_CONTINUATION.equals(input.primaryModel()) || !valid(input)) {
            return Decision.hold("THESIS_INTACT", "unsupported-or-incomplete", input == null ? null : input.maxFavorableR(),
                    input == null ? null : input.trailingArmedAt(), null, null);
        }
        BigDecimal riskDistance = input.entryPrice().subtract(input.initialStopPrice()).abs();
        BigDecimal favorableMove = favorableMove(input.side(), input.entryPrice(), input.highestPrice(), input.lowestPrice());
        BigDecimal computedMaxR = favorableMove.divide(riskDistance, 12, RoundingMode.DOWN).max(zero(input.maxFavorableR()));
        boolean structureTargetReached = targetReached(input.side(), input.referencePrice(), input.structureTargetPrice());
        boolean riskRewardMilestone = computedMaxR.compareTo(BigDecimal.ONE) >= 0;
        BigDecimal adverseFromBest = adverseMoveFromBest(input.side(), input.referencePrice(), input.highestPrice(), input.lowestPrice());
        boolean dynamicPullback = positive(input.atr()) && adverseFromBest.compareTo(input.atr()) >= 0;
        boolean profitable = positive(input.currentNetPnl());
        boolean milestone = profitable && (structureTargetReached || riskRewardMilestone || dynamicPullback);
        LocalDateTime armedAt = input.trailingArmedAt();
        String armReason = input.trailingArmReason();
        boolean newlyArmed = false;
        if (armedAt == null && milestone) {
            armedAt = input.evaluatedAt();
            newlyArmed = true;
            armReason = structureTargetReached ? "STRUCTURE_TARGET" : riskRewardMilestone ? "RISK_REWARD_MILESTONE"
                    : "MFE_DYNAMIC_PULLBACK";
        }

        boolean invalidated = crossed(input.side(), input.referencePrice(), input.invalidationPrice())
                || crossed(input.side(), input.referencePrice(), input.initialStopPrice());
        if (invalidated) {
            return new Decision("INVALIDATED", Action.FULL_CLOSE, 0L, null, computedMaxR, armedAt, armReason,
                    "model-or-initial-stop-invalidated", evidence(input, computedMaxR, dynamicPullback, 3, null));
        }

        boolean protectionCrossed = !newlyArmed && armedAt != null
                && crossed(input.side(), input.referencePrice(), input.protectionPrice());
        if (protectionCrossed) {
            return new Decision("RUNNER", Action.FULL_CLOSE, 0L, input.protectionPrice(), computedMaxR, armedAt, armReason,
                    "armed-protection-crossed", evidence(input, computedMaxR, dynamicPullback, 3, null));
        }

        boolean flatLossBudgetExceeded = armedAt == null && input.signalSide() == TrendSide.FLAT
                && input.requiredFlatStreak() > 0 && input.flatStreak() >= input.requiredFlatStreak()
                && negative(input.currentNetPnl())
                && positive(input.flatLossBudget())
                && input.currentNetPnl().negate().compareTo(input.flatLossBudget()) >= 0;
        if (flatLossBudgetExceeded) {
            return new Decision("THESIS_EXPIRED", Action.FULL_CLOSE, 0L, null, computedMaxR, null, null,
                    "unarmed-fib-flat-loss-budget-exceeded",
                    evidence(input, computedMaxR, dynamicPullback, 0, null));
        }

        int reversalEvidence = reversalEvidence(input, dynamicPullback);
        BigDecimal expectedReward = expectedReward(input);
        BigDecimal protectedRisk = protectedRisk(input);
        boolean continuationWorthHolding = expectedReward.compareTo(protectedRisk) > 0 && reversalEvidence <= 1;

        if (profitable && dynamicPullback && reversalEvidence >= 2 && !continuationWorthHolding) {
            long targetRemaining = targetRemainingContracts(input.remainingContracts(), expectedReward, protectedRisk);
            if (targetRemaining <= 0) {
                return new Decision("REVERSAL_CONFIRMED", Action.FULL_CLOSE, 0L, input.protectionPrice(), computedMaxR,
                        armedAt, armReason, "multi-evidence-reversal-and-unfavorable-remaining-value",
                        evidence(input, computedMaxR, true, reversalEvidence, expectedReward));
            }
            return new Decision("PROFIT_AT_RISK", Action.PARTIAL_CLOSE, targetRemaining, input.protectionPrice(),
                    computedMaxR, armedAt, armReason, "profit-at-risk-with-deteriorating-continuation",
                    evidence(input, computedMaxR, true, reversalEvidence, expectedReward));
        }

        if (armedAt != null) {
            String phase = continuationWorthHolding ? "RUNNER" : dynamicPullback ? "PROFIT_AT_RISK" : "PROFIT_PROTECTED";
            return new Decision(phase, Action.ARM_TRAILING, -1L, input.protectionPrice(), computedMaxR, armedAt, armReason,
                    continuationWorthHolding ? "runner-hold-with-positive-remaining-value" : "armed-profit-protection",
                    evidence(input, computedMaxR, dynamicPullback, reversalEvidence, expectedReward));
        }
        return Decision.hold(profitable ? "PROFIT_AT_RISK" : "THESIS_INTACT",
                profitable ? "profit-not-yet-at-actionable-milestone" : "thesis-not-yet-profitable", computedMaxR,
                null, null, evidence(input, computedMaxR, dynamicPullback, reversalEvidence, expectedReward));
    }

    private static boolean valid(Input input) {
        return validSide(input.side()) && positive(input.contractSize()) && positive(input.entryPrice()) && positive(input.initialStopPrice())
                && positive(input.referencePrice()) && positive(input.highestPrice()) && positive(input.lowestPrice())
                && input.remainingContracts() > 0L;
    }

    private static int reversalEvidence(Input input, boolean dynamicPullback) {
        int evidence = dynamicPullback ? 1 : 0; // price/structure group
        boolean momentumAgainst = (HtxTrendExitRules.LONG.equals(input.side()) && input.signalSide() == TrendSide.SHORT)
                || (HtxTrendExitRules.SHORT.equals(input.side()) && input.signalSide() == TrendSide.LONG)
                || (input.signalSide() == TrendSide.FLAT && emaAgainst(input.side(), input.emaFast(), input.emaSlow()));
        if (momentumAgainst) evidence++;
        if (input.majorTimeframeOpposition() > 0 || input.marketContextOpposes()) evidence++;
        return evidence;
    }

    private static boolean emaAgainst(String side, BigDecimal fast, BigDecimal slow) {
        if (!positive(fast) || !positive(slow)) return false;
        return HtxTrendExitRules.LONG.equals(side) ? fast.compareTo(slow) < 0 : fast.compareTo(slow) > 0;
    }

    private static BigDecimal expectedReward(Input input) {
        if (!positive(input.structureTargetPrice())) return BigDecimal.ZERO;
        BigDecimal move = HtxTrendExitRules.LONG.equals(input.side())
                ? input.structureTargetPrice().subtract(input.referencePrice())
                : input.referencePrice().subtract(input.structureTargetPrice());
        return move.signum() > 0 ? move.multiply(input.contractSize()).multiply(BigDecimal.valueOf(input.remainingContracts()))
                .subtract(zero(input.estimatedCloseFee())) : BigDecimal.ZERO;
    }

    private static BigDecimal protectedRisk(Input input) {
        if (!positive(input.protectionPrice())) return BigDecimal.ZERO;
        BigDecimal move = HtxTrendExitRules.LONG.equals(input.side())
                ? input.referencePrice().subtract(input.protectionPrice())
                : input.protectionPrice().subtract(input.referencePrice());
        return move.signum() > 0 ? move.multiply(input.contractSize()).multiply(BigDecimal.valueOf(input.remainingContracts()))
                .add(zero(input.estimatedCloseFee())) : BigDecimal.ZERO;
    }

    /** Retain more only when remaining structure reward is materially larger than protected loss. */
    private static long targetRemainingContracts(long remaining, BigDecimal expectedReward, BigDecimal protectedRisk) {
        if (remaining <= 1L || expectedReward.signum() <= 0 || protectedRisk.signum() <= 0) return 0L;
        BigDecimal ratio = expectedReward.divide(expectedReward.add(protectedRisk), 12, RoundingMode.DOWN);
        long target = BigDecimal.valueOf(remaining).multiply(ratio).setScale(0, RoundingMode.DOWN).longValue();
        return Math.max(0L, Math.min(remaining - 1L, target));
    }

    private static String evidence(Input input, BigDecimal maxR, boolean dynamicPullback, int reversalEvidence,
                                   BigDecimal expectedReward) {
        BigDecimal protectedRisk = protectedRisk(input);
        return "{\"schema\":\"trend-exit-decision-v2\",\"model\":\"FIB_PULLBACK_CONTINUATION\","
                + "\"signal\":\"" + input.signalSide().name() + "\",\"dynamicPullback\":" + dynamicPullback
                + ",\"reversalEvidence\":" + reversalEvidence + ",\"majorTimeframeOpposition\":"
                + input.majorTimeframeOpposition() + ",\"marketContextOpposes\":" + input.marketContextOpposes()
                + ",\"maxFavorableR\":" + number(maxR) + ",\"expectedReward\":" + number(expectedReward)
                + ",\"protectedRisk\":" + number(protectedRisk) + ",\"flatStreak\":" + input.flatStreak()
                + ",\"requiredFlatStreak\":" + input.requiredFlatStreak() + ",\"flatLossBudget\":"
                + number(input.flatLossBudget()) + "}";
    }

    private static BigDecimal favorableMove(String side, BigDecimal entry, BigDecimal highest, BigDecimal lowest) {
        return HtxTrendExitRules.LONG.equals(side) ? highest.subtract(entry).max(BigDecimal.ZERO)
                : entry.subtract(lowest).max(BigDecimal.ZERO);
    }

    private static BigDecimal adverseMoveFromBest(String side, BigDecimal reference, BigDecimal highest, BigDecimal lowest) {
        return HtxTrendExitRules.LONG.equals(side) ? highest.subtract(reference).max(BigDecimal.ZERO)
                : reference.subtract(lowest).max(BigDecimal.ZERO);
    }

    private static boolean crossed(String side, BigDecimal reference, BigDecimal price) {
        return HtxTrendExitRules.crossedStop(side, reference, price);
    }

    private static boolean targetReached(String side, BigDecimal reference, BigDecimal target) {
        return HtxTrendExitRules.targetReached(side, reference, target);
    }

    private static boolean validSide(String side) {
        return HtxTrendExitRules.LONG.equals(side) || HtxTrendExitRules.SHORT.equals(side);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String number(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    public enum Action { HOLD, ARM_TRAILING, PARTIAL_CLOSE, FULL_CLOSE }

    public record Input(String primaryModel, String side, long remainingContracts, BigDecimal contractSize,
                        BigDecimal entryPrice, BigDecimal initialStopPrice, BigDecimal invalidationPrice,
                        BigDecimal structureTargetPrice, BigDecimal referencePrice, BigDecimal protectionPrice,
                        BigDecimal highestPrice, BigDecimal lowestPrice, BigDecimal atr, TrendSide signalSide,
                        BigDecimal emaFast, BigDecimal emaSlow, int majorTimeframeOpposition,
                        boolean marketContextOpposes, BigDecimal currentNetPnl, BigDecimal estimatedCloseFee,
                        LocalDateTime trailingArmedAt, String trailingArmReason, BigDecimal maxFavorableR,
                        LocalDateTime evaluatedAt, int flatStreak, int requiredFlatStreak,
                        BigDecimal flatLossBudget) {
    }

    public record Decision(String phase, Action action, long targetRemainingContracts, BigDecimal protectionPrice,
                           BigDecimal maxFavorableR, LocalDateTime trailingArmedAt, String trailingArmReason,
                           String reason, String evidenceJson) {
        static Decision hold(String phase, String reason, BigDecimal maxFavorableR, LocalDateTime armedAt,
                             String armReason, String evidenceJson) {
            return new Decision(phase, Action.HOLD, -1L, null, maxFavorableR, armedAt, armReason, reason, evidenceJson);
        }
    }
}
