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

    /**
     * One and only executable policy for Fibonacci positions.  Do not create a new numbered
     * engine for a rule fix: the durable state and the live execution path must remain one v4 chain.
     */
    public static final String VERSION = "fib-exit-engine-v4";
    public static final String FIB_PULLBACK_CONTINUATION = "FIB_PULLBACK_CONTINUATION";

    public Decision evaluate(Input input) {
        if (input == null || !FIB_PULLBACK_CONTINUATION.equals(input.primaryModel()) || !valid(input)) {
            return Decision.hold("THESIS_INTACT", "unsupported-or-incomplete", input == null ? null : input.maxFavorableR(),
                    input == null ? null : input.trailingArmedAt(), null, null);
        }
        ProfitProtectionPolicy policy = policy(input.profitProtectionPolicy());
        BigDecimal riskDistance = input.entryPrice().subtract(input.initialStopPrice()).abs();
        BigDecimal favorableMove = favorableMove(input.side(), input.entryPrice(), input.highestPrice(), input.lowestPrice());
        BigDecimal computedMaxR = favorableMove.divide(riskDistance, 12, RoundingMode.DOWN).max(zero(input.maxFavorableR()));
        boolean structureTargetReached = targetReached(input.side(), input.referencePrice(), input.structureTargetPrice());
        boolean riskRewardMilestone = computedMaxR.compareTo(BigDecimal.ONE) >= 0;
        BigDecimal adverseFromBest = adverseMoveFromBest(input.side(), input.referencePrice(), input.highestPrice(), input.lowestPrice());
        boolean profitable = positive(input.currentNetPnl());
        BigDecimal allInCostDistance = allInCostDistance(input);
        BigDecimal profitLockDistance = allInCostDistance.add(zero(input.atr()).multiply(policy.lockAtrMultiplier()));
        BigDecimal profitArmDistance = max(zero(input.atr()).multiply(policy.armAtrMultiplier()), profitLockDistance);
        BigDecimal partialQualificationDistance = zero(input.atr()).multiply(policy.partialAtrMultiplier());
        boolean costCovered = input.currentNetPnl().compareTo(zero(input.estimatedCloseFee())
                .multiply(policy.minNetFeeMultiple())) >= 0;
        boolean atrProfitMilestone = costCovered && favorableMove.compareTo(profitArmDistance) >= 0;
        boolean partialQualified = costCovered && favorableMove.compareTo(partialQualificationDistance) >= 0;
        boolean earlyPullback = positive(input.atr())
                && adverseFromBest.compareTo(input.atr().multiply(policy.pullbackAtrMultiplier())) >= 0;
        boolean dynamicPullback = positive(input.atr()) && adverseFromBest.compareTo(input.atr()) >= 0;
        int reversalEvidence = reversalEvidence(input, earlyPullback);
        boolean milestone = profitable && (structureTargetReached || riskRewardMilestone || atrProfitMilestone);
        LocalDateTime armedAt = input.trailingArmedAt();
        String armReason = input.trailingArmReason();
        boolean newlyArmed = false;
        if (armedAt == null && milestone) {
            armedAt = input.evaluatedAt();
            newlyArmed = true;
            armReason = structureTargetReached ? "STRUCTURE_TARGET" : riskRewardMilestone ? "RISK_REWARD_MILESTONE"
                    : "NET_COST_AND_ATR_MILESTONE";
        }
        BigDecimal profitLockPrice = protectionPrice(input.side(), input.entryPrice(), profitLockDistance);
        BigDecimal effectiveProtection = armedAt == null ? input.protectionPrice()
                : tightenProtection(input.side(), input.protectionPrice(), profitLockPrice);

        boolean invalidated = crossed(input.side(), input.referencePrice(), input.invalidationPrice())
                || crossed(input.side(), input.referencePrice(), input.initialStopPrice());
        if (invalidated) {
            return new Decision("INVALIDATED", Action.FULL_CLOSE, 0L, null, computedMaxR, armedAt, armReason,
                    "model-or-initial-stop-invalidated", evidence(input, computedMaxR, dynamicPullback, earlyPullback,
                    profitArmDistance, profitLockPrice, effectiveProtection, reversalEvidence, null));
        }

        boolean flatLossBudgetExceeded = armedAt == null && input.signalSide() == TrendSide.FLAT
                && input.requiredFlatStreak() > 0 && input.flatStreak() >= input.requiredFlatStreak()
                && negative(input.currentNetPnl())
                && positive(input.flatLossBudget())
                && input.currentNetPnl().negate().compareTo(input.flatLossBudget()) >= 0;
        if (flatLossBudgetExceeded) {
            return new Decision("THESIS_EXPIRED", Action.FULL_CLOSE, 0L, null, computedMaxR, null, null,
                    "unarmed-fib-flat-loss-budget-exceeded",
                    evidence(input, computedMaxR, dynamicPullback, earlyPullback, profitArmDistance, profitLockPrice,
                            effectiveProtection, 0, null));
        }

        BigDecimal expectedReward = expectedReward(input);
        BigDecimal protectedRisk = protectedRisk(input, effectiveProtection);
        boolean continuationWorthHolding = expectedReward.compareTo(protectedRisk) > 0 && reversalEvidence <= 1;

        // The protection line is the boundary of already-earned profit.  A deep intrabar break is a failed
        // continuation, even if higher timeframes have not yet reversed.  For a shallow break we retain the
        // completed-bar confirmation so one wick cannot liquidate a healthy runner.
        boolean protectionBreached = !newlyArmed && armedAt != null && input.protectionBreachBars() > 0;
        boolean protectionBreachConfirmed = protectionBreached
                && input.protectionBreachBars() >= policy.protectionBreachConfirmationBars();
        boolean acuteProtectionBreach = protectionBreached && dynamicPullback;
        if (acuteProtectionBreach) {
            return new Decision("REVERSAL_CONFIRMED", Action.FULL_CLOSE, 0L, effectiveProtection, computedMaxR,
                    armedAt, armReason, "armed-protection-breach-atr-impulse",
                    evidence(input, computedMaxR, dynamicPullback, earlyPullback, profitArmDistance, profitLockPrice,
                            effectiveProtection, reversalEvidence, expectedReward));
        }
        if (protectionBreached && !protectionBreachConfirmed) {
            return new Decision("PROFIT_AT_RISK", Action.ARM_TRAILING, -1L, effectiveProtection, computedMaxR,
                    armedAt, armReason, "armed-protection-breach-awaiting-bar-confirmation",
                    evidence(input, computedMaxR, dynamicPullback, earlyPullback, profitArmDistance, profitLockPrice,
                            effectiveProtection, reversalEvidence, expectedReward));
        }
        if (protectionBreachConfirmed) {
            return new Decision("REVERSAL_CONFIRMED", Action.FULL_CLOSE, 0L, effectiveProtection, computedMaxR,
                    armedAt, armReason, "confirmed-profit-protection-breach",
                    evidence(input, computedMaxR, dynamicPullback, earlyPullback, profitArmDistance, profitLockPrice,
                            effectiveProtection, reversalEvidence, expectedReward));
        }

        if (profitable && partialQualified && earlyPullback && reversalEvidence >= 2 && !continuationWorthHolding) {
            long targetRemaining = targetRemainingContracts(input.remainingContracts(), expectedReward, protectedRisk);
            if (targetRemaining <= 0) {
                return new Decision("REVERSAL_CONFIRMED", Action.FULL_CLOSE, 0L, effectiveProtection, computedMaxR,
                        armedAt, armReason, "multi-evidence-reversal-and-unfavorable-remaining-value",
                        evidence(input, computedMaxR, dynamicPullback, earlyPullback, profitArmDistance, profitLockPrice,
                                effectiveProtection, reversalEvidence, expectedReward));
            }
            return new Decision("PROFIT_AT_RISK", Action.PARTIAL_CLOSE, targetRemaining, effectiveProtection,
                    computedMaxR, armedAt, armReason, "profit-at-risk-with-deteriorating-continuation",
                    evidence(input, computedMaxR, dynamicPullback, earlyPullback, profitArmDistance, profitLockPrice,
                            effectiveProtection, reversalEvidence, expectedReward));
        }

        // Funding was historically evaluated after the legacy exit rules.  Fibonacci positions return from
        // this engine before that legacy branch, so keep the same safety rule inside v4 rather than silently
        // dropping it.  It remains lower priority than invalidation and an already-breached profit protection.
        if (input.fundingCostExitReason() != null && !input.fundingCostExitReason().isBlank()) {
            return new Decision("FUNDING_COST", Action.FULL_CLOSE, 0L, effectiveProtection, computedMaxR,
                    armedAt, armReason, "funding-cost-exit: " + input.fundingCostExitReason(),
                    evidence(input, computedMaxR, dynamicPullback, earlyPullback, profitArmDistance, profitLockPrice,
                            effectiveProtection, reversalEvidence, expectedReward));
        }

        if (armedAt != null) {
            String phase = continuationWorthHolding ? "RUNNER" : dynamicPullback ? "PROFIT_AT_RISK" : "PROFIT_PROTECTED";
            return new Decision(phase, Action.ARM_TRAILING, -1L, effectiveProtection, computedMaxR, armedAt, armReason,
                    continuationWorthHolding ? "runner-hold-with-positive-remaining-value" : "armed-profit-protection",
                    evidence(input, computedMaxR, dynamicPullback, earlyPullback, profitArmDistance, profitLockPrice,
                            effectiveProtection, reversalEvidence, expectedReward));
        }
        return Decision.hold(profitable ? "PROFIT_AT_RISK" : "THESIS_INTACT",
                profitable ? "profit-not-yet-at-actionable-milestone" : "thesis-not-yet-profitable", computedMaxR,
                null, null, evidence(input, computedMaxR, dynamicPullback, earlyPullback, profitArmDistance, profitLockPrice,
                effectiveProtection, reversalEvidence, expectedReward));
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

    private static BigDecimal protectedRisk(Input input, BigDecimal protectionPrice) {
        if (!positive(protectionPrice)) return BigDecimal.ZERO;
        BigDecimal move = HtxTrendExitRules.LONG.equals(input.side())
                ? input.referencePrice().subtract(protectionPrice)
                : protectionPrice.subtract(input.referencePrice());
        return move.abs().multiply(input.contractSize()).multiply(BigDecimal.valueOf(input.remainingContracts()))
                .add(zero(input.estimatedCloseFee()));
    }

    /** Retain more only when remaining structure reward is materially larger than protected loss. */
    private static long targetRemainingContracts(long remaining, BigDecimal expectedReward, BigDecimal protectedRisk) {
        if (remaining <= 1L || expectedReward.signum() <= 0 || protectedRisk.signum() <= 0) return 0L;
        BigDecimal ratio = expectedReward.divide(expectedReward.add(protectedRisk), 12, RoundingMode.DOWN);
        long target = BigDecimal.valueOf(remaining).multiply(ratio).setScale(0, RoundingMode.DOWN).longValue();
        return Math.max(0L, Math.min(remaining - 1L, target));
    }

    private static String evidence(Input input, BigDecimal maxR, boolean dynamicPullback, boolean earlyPullback,
                                   BigDecimal profitArmDistance, BigDecimal profitLockPrice, BigDecimal effectiveProtection,
                                   int reversalEvidence,
                                   BigDecimal expectedReward) {
        BigDecimal protectedRisk = protectedRisk(input, effectiveProtection);
        return "{\"schema\":\"trend-exit-decision-v4\",\"model\":\"FIB_PULLBACK_CONTINUATION\","
                + "\"signal\":\"" + input.signalSide().name() + "\",\"dynamicPullback\":" + dynamicPullback
                + ",\"earlyPullback\":" + earlyPullback + ",\"profitArmDistance\":" + number(profitArmDistance)
                + ",\"profitLockPrice\":" + number(profitLockPrice) + ",\"effectiveProtectionPrice\":"
                + number(effectiveProtection)
                + ",\"entryFee\":" + number(input.entryFee()) + ",\"estimatedCloseFee\":"
                + number(input.estimatedCloseFee())
                + ",\"reversalEvidence\":" + reversalEvidence + ",\"protectionBreachBars\":"
                + input.protectionBreachBars() + ",\"requiredProtectionBreachBars\":"
                + policy(input.profitProtectionPolicy()).protectionBreachConfirmationBars()
                + ",\"majorTimeframeOpposition\":"
                + input.majorTimeframeOpposition() + ",\"marketContextOpposes\":" + input.marketContextOpposes()
                + ",\"maxFavorableR\":" + number(maxR) + ",\"expectedReward\":" + number(expectedReward)
                + ",\"protectedRisk\":" + number(protectedRisk) + ",\"flatStreak\":" + input.flatStreak()
                + ",\"requiredFlatStreak\":" + input.requiredFlatStreak() + ",\"flatLossBudget\":"
                + number(input.flatLossBudget()) + ",\"fundingCostExit\":"
                + jsonText(input.fundingCostExitReason()) + "}";
    }

    private static BigDecimal favorableMove(String side, BigDecimal entry, BigDecimal highest, BigDecimal lowest) {
        return HtxTrendExitRules.LONG.equals(side) ? highest.subtract(entry).max(BigDecimal.ZERO)
                : entry.subtract(lowest).max(BigDecimal.ZERO);
    }

    private static BigDecimal adverseMoveFromBest(String side, BigDecimal reference, BigDecimal highest, BigDecimal lowest) {
        return HtxTrendExitRules.LONG.equals(side) ? highest.subtract(reference).max(BigDecimal.ZERO)
                : reference.subtract(lowest).max(BigDecimal.ZERO);
    }

    /**
     * Uses the actual opening fee when it is known. The close fee is an estimate at the current
     * mark price because it has not happened yet. Treating both legs as the close fee is only a
     * compatibility fallback for historical inputs that predate the opening-fee field.
     */
    private static BigDecimal allInCostDistance(Input input) {
        BigDecimal exposure = input.contractSize().multiply(BigDecimal.valueOf(input.remainingContracts()));
        if (exposure.signum() <= 0) return BigDecimal.ZERO;
        return zero(input.entryFee()).add(zero(input.estimatedCloseFee()))
                .divide(exposure, 12, RoundingMode.UP);
    }

    private static BigDecimal protectionPrice(String side, BigDecimal entryPrice, BigDecimal lockDistance) {
        return HtxTrendExitRules.LONG.equals(side) ? entryPrice.add(lockDistance) : entryPrice.subtract(lockDistance);
    }

    private static BigDecimal tightenProtection(String side, BigDecimal existing, BigDecimal candidate) {
        if (!positive(existing)) return candidate;
        if (!positive(candidate)) return existing;
        return HtxTrendExitRules.LONG.equals(side) ? max(existing, candidate) : existing.min(candidate);
    }

    private static BigDecimal max(BigDecimal first, BigDecimal second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private static ProfitProtectionPolicy policy(ProfitProtectionPolicy policy) {
        return policy == null ? ProfitProtectionPolicy.intradayDefaults() : policy.normalized();
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

    private static String jsonText(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public enum Action { HOLD, ARM_TRAILING, PARTIAL_CLOSE, FULL_CLOSE }

    public record Input(String primaryModel, String side, long remainingContracts, BigDecimal contractSize,
                        BigDecimal entryPrice, BigDecimal initialStopPrice, BigDecimal invalidationPrice,
                        BigDecimal structureTargetPrice, BigDecimal referencePrice, BigDecimal protectionPrice,
                        BigDecimal highestPrice, BigDecimal lowestPrice, BigDecimal atr, TrendSide signalSide,
                        BigDecimal emaFast, BigDecimal emaSlow, int majorTimeframeOpposition,
                        boolean marketContextOpposes, BigDecimal currentNetPnl, BigDecimal entryFee,
                        BigDecimal estimatedCloseFee,
                        LocalDateTime trailingArmedAt, String trailingArmReason, BigDecimal maxFavorableR,
                        LocalDateTime evaluatedAt, int flatStreak, int requiredFlatStreak,
                        BigDecimal flatLossBudget, ProfitProtectionPolicy profitProtectionPolicy,
                        int protectionBreachBars, String fundingCostExitReason) {
        /** Compatibility constructor for frozen fixtures and callers that do not have a funding observation. */
        public Input(String primaryModel, String side, long remainingContracts, BigDecimal contractSize,
                     BigDecimal entryPrice, BigDecimal initialStopPrice, BigDecimal invalidationPrice,
                     BigDecimal structureTargetPrice, BigDecimal referencePrice, BigDecimal protectionPrice,
                     BigDecimal highestPrice, BigDecimal lowestPrice, BigDecimal atr, TrendSide signalSide,
                     BigDecimal emaFast, BigDecimal emaSlow, int majorTimeframeOpposition,
                     boolean marketContextOpposes, BigDecimal currentNetPnl, BigDecimal entryFee,
                     BigDecimal estimatedCloseFee, LocalDateTime trailingArmedAt, String trailingArmReason,
                     BigDecimal maxFavorableR, LocalDateTime evaluatedAt, int flatStreak, int requiredFlatStreak,
                     BigDecimal flatLossBudget, ProfitProtectionPolicy profitProtectionPolicy,
                     int protectionBreachBars) {
            this(primaryModel, side, remainingContracts, contractSize, entryPrice, initialStopPrice, invalidationPrice,
                    structureTargetPrice, referencePrice, protectionPrice, highestPrice, lowestPrice, atr, signalSide,
                    emaFast, emaSlow, majorTimeframeOpposition, marketContextOpposes, currentNetPnl, entryFee,
                    estimatedCloseFee, trailingArmedAt, trailingArmReason, maxFavorableR, evaluatedAt, flatStreak,
                    requiredFlatStreak, flatLossBudget, profitProtectionPolicy, protectionBreachBars, null);
        }
        public Input(String primaryModel, String side, long remainingContracts, BigDecimal contractSize,
                     BigDecimal entryPrice, BigDecimal initialStopPrice, BigDecimal invalidationPrice,
                     BigDecimal structureTargetPrice, BigDecimal referencePrice, BigDecimal protectionPrice,
                     BigDecimal highestPrice, BigDecimal lowestPrice, BigDecimal atr, TrendSide signalSide,
                     BigDecimal emaFast, BigDecimal emaSlow, int majorTimeframeOpposition,
                     boolean marketContextOpposes, BigDecimal currentNetPnl, BigDecimal entryFee,
                     BigDecimal estimatedCloseFee, LocalDateTime trailingArmedAt, String trailingArmReason,
                     BigDecimal maxFavorableR, LocalDateTime evaluatedAt, int flatStreak, int requiredFlatStreak,
                     BigDecimal flatLossBudget, ProfitProtectionPolicy profitProtectionPolicy) {
            this(primaryModel, side, remainingContracts, contractSize, entryPrice, initialStopPrice, invalidationPrice,
                    structureTargetPrice, referencePrice, protectionPrice, highestPrice, lowestPrice, atr, signalSide,
                    emaFast, emaSlow, majorTimeframeOpposition, marketContextOpposes, currentNetPnl, entryFee,
                    estimatedCloseFee, trailingArmedAt, trailingArmReason, maxFavorableR, evaluatedAt, flatStreak,
                    requiredFlatStreak, flatLossBudget, profitProtectionPolicy, 0, null);
        }
        public Input(String primaryModel, String side, long remainingContracts, BigDecimal contractSize,
                     BigDecimal entryPrice, BigDecimal initialStopPrice, BigDecimal invalidationPrice,
                     BigDecimal structureTargetPrice, BigDecimal referencePrice, BigDecimal protectionPrice,
                     BigDecimal highestPrice, BigDecimal lowestPrice, BigDecimal atr, TrendSide signalSide,
                     BigDecimal emaFast, BigDecimal emaSlow, int majorTimeframeOpposition,
                     boolean marketContextOpposes, BigDecimal currentNetPnl, BigDecimal entryFee,
                     BigDecimal estimatedCloseFee, LocalDateTime trailingArmedAt, String trailingArmReason,
                     BigDecimal maxFavorableR, LocalDateTime evaluatedAt, int flatStreak, int requiredFlatStreak,
                     BigDecimal flatLossBudget) {
            this(primaryModel, side, remainingContracts, contractSize, entryPrice, initialStopPrice, invalidationPrice,
                    structureTargetPrice, referencePrice, protectionPrice, highestPrice, lowestPrice, atr, signalSide,
                    emaFast, emaSlow, majorTimeframeOpposition, marketContextOpposes, currentNetPnl, entryFee,
                    estimatedCloseFee, trailingArmedAt, trailingArmReason, maxFavorableR, evaluatedAt, flatStreak,
                    requiredFlatStreak, flatLossBudget, ProfitProtectionPolicy.intradayDefaults(), 0, null);
        }

        public Input(String primaryModel, String side, long remainingContracts, BigDecimal contractSize,
                     BigDecimal entryPrice, BigDecimal initialStopPrice, BigDecimal invalidationPrice,
                     BigDecimal structureTargetPrice, BigDecimal referencePrice, BigDecimal protectionPrice,
                     BigDecimal highestPrice, BigDecimal lowestPrice, BigDecimal atr, TrendSide signalSide,
                     BigDecimal emaFast, BigDecimal emaSlow, int majorTimeframeOpposition,
                     boolean marketContextOpposes, BigDecimal currentNetPnl, BigDecimal estimatedCloseFee,
                     LocalDateTime trailingArmedAt, String trailingArmReason, BigDecimal maxFavorableR,
                     LocalDateTime evaluatedAt, int flatStreak, int requiredFlatStreak, BigDecimal flatLossBudget) {
            this(primaryModel, side, remainingContracts, contractSize, entryPrice, initialStopPrice, invalidationPrice,
                    structureTargetPrice, referencePrice, protectionPrice, highestPrice, lowestPrice, atr, signalSide,
                    emaFast, emaSlow, majorTimeframeOpposition, marketContextOpposes, currentNetPnl, estimatedCloseFee,
                    estimatedCloseFee,
                    trailingArmedAt, trailingArmReason, maxFavorableR, evaluatedAt, flatStreak, requiredFlatStreak,
                    flatLossBudget, ProfitProtectionPolicy.intradayDefaults(), 0, null);
        }

        public Input withProtectionBreachBars(int breachBars) {
            return new Input(primaryModel, side, remainingContracts, contractSize, entryPrice, initialStopPrice,
                    invalidationPrice, structureTargetPrice, referencePrice, protectionPrice, highestPrice, lowestPrice,
                    atr, signalSide, emaFast, emaSlow, majorTimeframeOpposition, marketContextOpposes, currentNetPnl,
                    entryFee, estimatedCloseFee, trailingArmedAt, trailingArmReason, maxFavorableR, evaluatedAt,
                    flatStreak, requiredFlatStreak, flatLossBudget, profitProtectionPolicy, breachBars,
                    fundingCostExitReason);
        }

        public Input withFundingCostExitReason(String reason) {
            return new Input(primaryModel, side, remainingContracts, contractSize, entryPrice, initialStopPrice,
                    invalidationPrice, structureTargetPrice, referencePrice, protectionPrice, highestPrice, lowestPrice,
                    atr, signalSide, emaFast, emaSlow, majorTimeframeOpposition, marketContextOpposes, currentNetPnl,
                    entryFee, estimatedCloseFee, trailingArmedAt, trailingArmReason, maxFavorableR, evaluatedAt,
                    flatStreak, requiredFlatStreak, flatLossBudget, profitProtectionPolicy, protectionBreachBars, reason);
        }
    }

    public record ProfitProtectionPolicy(BigDecimal armAtrMultiplier, BigDecimal lockAtrMultiplier,
                                         BigDecimal partialAtrMultiplier, BigDecimal pullbackAtrMultiplier,
                                         BigDecimal minNetFeeMultiple, int protectionBreachConfirmationBars) {
        public ProfitProtectionPolicy(BigDecimal armAtrMultiplier, BigDecimal lockAtrMultiplier,
                                      BigDecimal partialAtrMultiplier, BigDecimal pullbackAtrMultiplier,
                                      BigDecimal minNetFeeMultiple) {
            this(armAtrMultiplier, lockAtrMultiplier, partialAtrMultiplier, pullbackAtrMultiplier,
                    minNetFeeMultiple, 2);
        }
        public static ProfitProtectionPolicy intradayDefaults() {
            return new ProfitProtectionPolicy(new BigDecimal("0.50"), new BigDecimal("0.15"),
                    new BigDecimal("0.75"), new BigDecimal("0.40"), new BigDecimal("2.00"), 2);
        }

        ProfitProtectionPolicy normalized() {
            ProfitProtectionPolicy defaults = intradayDefaults();
            return new ProfitProtectionPolicy(nonNegativeOr(armAtrMultiplier, defaults.armAtrMultiplier),
                    nonNegativeOr(lockAtrMultiplier, defaults.lockAtrMultiplier),
                    nonNegativeOr(partialAtrMultiplier, defaults.partialAtrMultiplier),
                    nonNegativeOr(pullbackAtrMultiplier, defaults.pullbackAtrMultiplier),
                    nonNegativeOr(minNetFeeMultiple, defaults.minNetFeeMultiple),
                    protectionBreachConfirmationBars <= 0 ? defaults.protectionBreachConfirmationBars
                            : protectionBreachConfirmationBars);
        }

        private static BigDecimal nonNegativeOr(BigDecimal value, BigDecimal fallback) {
            return value == null || value.signum() < 0 ? fallback : value;
        }
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
