package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Decides whether a combined candidate may trade against a confirmed broad-market move.
 *
 * <p>Market breadth remains a risk input, not a universal directional veto. A counter-market
 * entry must prove local structure and continuation quality; passing this gate is intentionally
 * smaller than an aligned entry through the existing counter-breadth sizing multiplier.</p>
 */
public final class HtxTrendCounterMarketEntryGate {

    private HtxTrendCounterMarketEntryGate() {
    }

    public static Decision evaluate(Input input) {
        if (input == null || !opposes(input.side(), input.marketRegime())) {
            return Decision.notApplicable();
        }
        if (!input.enabled()) {
            return Decision.rejected("counter-market entry disabled");
        }
        if (belowThreshold(input.modelScore(), input.minModelScore())) {
            return Decision.rejected("modelScore=" + value(input.modelScore()) + "<" + value(input.minModelScore()));
        }
        if (belowThreshold(input.expectedRMultiple(), input.minExpectedRMultiple())) {
            return Decision.rejected("expectedR=" + value(input.expectedRMultiple()) + "<" + value(input.minExpectedRMultiple()));
        }
        if (belowThreshold(input.momentumQualityScore(), input.minMomentumQualityScore())) {
            return Decision.rejected("momentum=" + value(input.momentumQualityScore()) + "<" + value(input.minMomentumQualityScore()));
        }
        if (belowThreshold(input.adx(), input.minAdx())) {
            return Decision.rejected("adx=" + value(input.adx()) + "<" + value(input.minAdx()));
        }
        if (!emaAligned(input.side(), input.emaFast(), input.emaSlow())) {
            return Decision.rejected("EMA not aligned with counter-market direction");
        }
        if (!hasIndependentStructure(input.confirmationModels())) {
            return Decision.rejected("missing independent breakout confirmation");
        }
        return Decision.qualified("independent structure confirmed; counter-market sizing applies");
    }

    private static boolean opposes(TrendSide side, String regime) {
        return HtxTrendCombinationMarketRiskGate.opposesBroadMarket(side == null ? null : side.name(), regime);
    }

    private static boolean hasIndependentStructure(String confirmations) {
        if (confirmations == null) return false;
        String value = confirmations.toUpperCase(Locale.ROOT);
        return value.contains("DONCHIAN_BREAKOUT") || value.contains("BOX_BREAKOUT");
    }

    private static boolean emaAligned(TrendSide side, BigDecimal fast, BigDecimal slow) {
        return side == TrendSide.LONG ? above(fast, slow) : side == TrendSide.SHORT && below(fast, slow);
    }

    private static boolean above(BigDecimal first, BigDecimal second) {
        return first != null && second != null && first.compareTo(second) > 0;
    }

    private static boolean below(BigDecimal first, BigDecimal second) {
        return first != null && second != null && first.compareTo(second) < 0;
    }

    private static boolean belowThreshold(BigDecimal actual, BigDecimal minimum) {
        return actual == null || minimum == null || actual.compareTo(minimum) < 0;
    }

    private static String value(BigDecimal value) {
        return value == null ? "null" : value.stripTrailingZeros().toPlainString();
    }

    public record Input(boolean enabled, TrendSide side, String marketRegime, BigDecimal modelScore,
                        BigDecimal expectedRMultiple, BigDecimal momentumQualityScore, BigDecimal adx,
                        BigDecimal emaFast, BigDecimal emaSlow, String confirmationModels,
                        BigDecimal minModelScore, BigDecimal minExpectedRMultiple,
                        BigDecimal minMomentumQualityScore, BigDecimal minAdx) {
    }

    public record Decision(boolean counterMarket, boolean qualified, String reason) {
        static Decision notApplicable() {
            return new Decision(false, false, "market direction aligned or mixed");
        }

        static Decision qualified(String reason) {
            return new Decision(true, true, reason);
        }

        static Decision rejected(String reason) {
            return new Decision(true, false, reason);
        }

        boolean blocksEntry() {
            return counterMarket && !qualified;
        }
    }
}
