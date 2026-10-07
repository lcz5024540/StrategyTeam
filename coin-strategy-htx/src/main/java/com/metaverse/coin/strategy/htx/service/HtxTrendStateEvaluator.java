package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;

import java.math.BigDecimal;

/**
 * Evaluates the quality of one candidate or one open position from its current market evidence.
 *
 * <p>This is deliberately independent from recent realised PnL. Historical PnL remains useful for
 * reporting and research, but it must not turn an otherwise valid current trend into a weak entry.
 */
public final class HtxTrendStateEvaluator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public EntryState evaluateEntry(EntryInput input) {
        if (input == null || input.side() == TrendSide.FLAT || !positive(input.modelScore())
                || !positive(input.expectedRMultiple()) || !positive(input.momentumQualityScore())) {
            EntryScoreBreakdown breakdown = new EntryScoreBreakdown(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);
            return EntryState.reject("INVALID", ZERO, ZERO, breakdown, "incomplete candidate evidence");
        }
        boolean emaAligned = aligned(input.side(), input.emaFast(), input.emaSlow());
        boolean broadAligned = alignedWithMarket(input.side(), input.marketRegime());
        boolean broadOpposed = opposedByMarket(input.side(), input.marketRegime());
        BigDecimal expectedRAdjustment = input.expectedRMultiple().compareTo(input.minExpectedRMultiple()) >= 0
                ? new BigDecimal("8") : new BigDecimal("-25");
        BigDecimal momentumAdjustment = input.momentumQualityScore().compareTo(input.minMomentumQualityScore()) >= 0
                ? new BigDecimal("5") : new BigDecimal("-15");
        BigDecimal emaAdjustment = emaAligned ? new BigDecimal("5") : new BigDecimal("-25");
        BigDecimal breadthAdjustment = broadAligned ? new BigDecimal("8") : broadOpposed ? new BigDecimal("-40") : ZERO;
        BigDecimal rawScore = input.modelScore().add(expectedRAdjustment).add(momentumAdjustment)
                .add(emaAdjustment).add(breadthAdjustment);
        BigDecimal score = rawScore.max(ZERO).min(ONE_HUNDRED);
        // A separately-qualified counter-market candidate is assessed on its local evidence.
        // The negative breadth adjustment remains visible in the breakdown and sizing stays reduced.
        BigDecimal eligibilityScore = broadOpposed && input.counterMarketQualified()
                ? input.modelScore().add(expectedRAdjustment).add(momentumAdjustment).add(emaAdjustment)
                        .max(ZERO).min(ONE_HUNDRED)
                : score;
        EntryScoreBreakdown breakdown = new EntryScoreBreakdown(input.modelScore(), expectedRAdjustment,
                momentumAdjustment, emaAdjustment, breadthAdjustment, rawScore, score);

        if (!emaAligned || (!input.counterMarketQualified() && broadOpposed)
                || input.expectedRMultiple().compareTo(input.minExpectedRMultiple()) < 0
                || input.momentumQualityScore().compareTo(input.minMomentumQualityScore()) < 0
                || eligibilityScore.compareTo(input.minTrendScore()) < 0) {
            return EntryState.reject("WEAK", eligibilityScore, rawScore, breakdown, reason(input, emaAligned, broadAligned, broadOpposed));
        }
        if (broadOpposed) {
            return new EntryState("COUNTER_QUALIFIED", true, eligibilityScore, rawScore, breakdown,
                    reason(input, emaAligned, false, true) + ", counterMarketQualified=true");
        }
        boolean strong = score.compareTo(input.strongTrendScore()) >= 0
                && input.expectedRMultiple().compareTo(input.strongExpectedRMultiple()) >= 0
                && (broadAligned || "TRENDING_UP".equals(input.marketRegime()) || "TRENDING_DOWN".equals(input.marketRegime()));
        return new EntryState(strong ? "STRONG" : "QUALIFIED", true, score, rawScore, breakdown,
                reason(input, emaAligned, broadAligned, broadOpposed));
    }

    public HoldingState evaluateHolding(HoldingInput input) {
        if (input == null || !validPositionSide(input.side())) return HoldingState.INVALIDATED;
        boolean oppositeSignal = ("LONG".equals(input.side()) && input.signalSide() == TrendSide.SHORT)
                || ("SHORT".equals(input.side()) && input.signalSide() == TrendSide.LONG);
        if (oppositeSignal || (input.majorTimeframeOpposition() >= 2 && input.marketContextOpposes())) {
            return HoldingState.INVALIDATED;
        }
        boolean emaAgainst = ("LONG".equals(input.side()) && below(input.emaFast(), input.emaSlow()))
                || ("SHORT".equals(input.side()) && above(input.emaFast(), input.emaSlow()));
        if (input.signalSide() == TrendSide.FLAT || emaAgainst || input.majorTimeframeOpposition() > 0
                || input.marketContextOpposes()) {
            return HoldingState.WEAKENING;
        }
        return HoldingState.TRENDING;
    }

    private static String reason(EntryInput input, boolean emaAligned, boolean broadAligned, boolean broadOpposed) {
        return "candidate score=" + number(input.modelScore()) + ", expectedR=" + number(input.expectedRMultiple())
                + ", momentum=" + number(input.momentumQualityScore()) + ", emaAligned=" + emaAligned
                + ", breadth=" + safe(input.marketRegime()) + ", breadthAligned=" + broadAligned
                + ", breadthOpposed=" + broadOpposed;
    }

    private static boolean aligned(TrendSide side, BigDecimal fast, BigDecimal slow) {
        return side == TrendSide.LONG ? above(fast, slow) : side == TrendSide.SHORT && below(fast, slow);
    }

    private static boolean alignedWithMarket(TrendSide side, String marketRegime) {
        return side == TrendSide.LONG && "BROAD_UP".equals(marketRegime)
                || side == TrendSide.SHORT && "BROAD_DOWN".equals(marketRegime);
    }

    private static boolean opposedByMarket(TrendSide side, String marketRegime) {
        return side == TrendSide.LONG && "BROAD_DOWN".equals(marketRegime)
                || side == TrendSide.SHORT && "BROAD_UP".equals(marketRegime);
    }

    private static boolean above(BigDecimal first, BigDecimal second) { return positive(first) && positive(second) && first.compareTo(second) > 0; }
    private static boolean below(BigDecimal first, BigDecimal second) { return positive(first) && positive(second) && first.compareTo(second) < 0; }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static boolean validPositionSide(String side) { return "LONG".equals(side) || "SHORT".equals(side); }
    private static String number(BigDecimal value) { return value == null ? "null" : value.stripTrailingZeros().toPlainString(); }
    private static String safe(String value) { return value == null ? "UNKNOWN" : value; }

    public enum HoldingState { TRENDING, WEAKENING, INVALIDATED }

    public record EntryInput(TrendSide side, BigDecimal modelScore, BigDecimal expectedRMultiple,
                             BigDecimal momentumQualityScore, BigDecimal emaFast, BigDecimal emaSlow,
                             String marketRegime, BigDecimal minTrendScore, BigDecimal strongTrendScore,
                             BigDecimal minExpectedRMultiple, BigDecimal strongExpectedRMultiple,
                             BigDecimal minMomentumQualityScore, boolean counterMarketQualified) { }

    public record EntryScoreBreakdown(BigDecimal modelScore, BigDecimal expectedRAdjustment,
                                      BigDecimal momentumAdjustment, BigDecimal emaAdjustment,
                                      BigDecimal breadthAdjustment, BigDecimal rawScore, BigDecimal cappedScore) {
    }

    public record EntryState(String regime, boolean tradable, BigDecimal score, BigDecimal rawScore,
                             EntryScoreBreakdown breakdown, String reason) {
        static EntryState reject(String regime, BigDecimal score, BigDecimal rawScore,
                                 EntryScoreBreakdown breakdown, String reason) {
            return new EntryState(regime, false, score, rawScore, breakdown, reason);
        }
        boolean strong() { return "STRONG".equals(regime); }
    }

    public record HoldingInput(String side, TrendSide signalSide, BigDecimal emaFast, BigDecimal emaSlow,
                               int majorTimeframeOpposition, boolean marketContextOpposes) { }
}
