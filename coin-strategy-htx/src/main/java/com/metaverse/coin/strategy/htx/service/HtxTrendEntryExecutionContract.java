package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure, production-owned arithmetic for the final trend-entry preflight.
 *
 * <p>The live service supplies exchange facts; this class keeps the resulting
 * decision math independently testable and mirrored by the Python worker.
 */
final class HtxTrendEntryExecutionContract {
    private static final BigDecimal TWO = new BigDecimal("2");

    private HtxTrendEntryExecutionContract() {
    }

    static String fundingCostGate(BigDecimal fundingRate, TrendSide side, BigDecimal maxCostRate) {
        if (maxCostRate == null || maxCostRate.signum() < 0) return null;
        if (fundingRate == null) return "funding unavailable; skip directional entry";
        BigDecimal sideCost = side == TrendSide.LONG ? fundingRate : fundingRate.negate();
        return sideCost.compareTo(maxCostRate) > 0
                ? "funding cost blocked side=" + side + " funding=" + fundingRate
                + " sideCost=" + sideCost + " max=" + maxCostRate : null;
    }

    static String freshnessGate(Long barTime, String period, long nowEpochSeconds, long maxAgeSeconds) {
        if (maxAgeSeconds <= 0L) return null;
        if (barTime == null || barTime <= 0L) return "entry signal bar time unavailable";
        long ageSeconds = nowEpochSeconds - (barTime + periodSeconds(period));
        if (ageSeconds < 0L) return "entry signal bar not closed ageSeconds=" + ageSeconds;
        return ageSeconds > maxAgeSeconds
                ? "entry signal too stale ageSeconds=" + ageSeconds + " max=" + maxAgeSeconds : null;
    }

    static String expectedMoveGate(BigDecimal atr, BigDecimal entryPrice, BigDecimal spreadRate,
                                   BigDecimal takerFeeRate, boolean enabled, BigDecimal atrMultiplier,
                                   BigDecimal minNetProfitRate) {
        if (!enabled || !positive(atr) || !positive(entryPrice)) return null;
        BigDecimal expectedMove = atr.multiply(nonNegative(atrMultiplier));
        BigDecimal requiredMove = entryPrice.multiply(nonNegative(takerFeeRate).multiply(TWO)
                .add(nonNegative(minNetProfitRate)).add(nonNegative(spreadRate)));
        return expectedMove.compareTo(requiredMove) < 0
                ? "expected move too small after fees expectedMove=" + expectedMove + " requiredMove=" + requiredMove
                + " atr=" + atr + " feeRate=" + nonNegative(takerFeeRate) : null;
    }

    static String chaseGate(TrendSide side, BigDecimal signalClose, BigDecimal entryPrice, BigDecimal atr,
                            BigDecimal atrMultiplier, BigDecimal rateLimit) {
        if (side == null || side == TrendSide.FLAT || !positive(entryPrice) || !positive(signalClose)) return null;
        BigDecimal worseFill = side == TrendSide.LONG ? entryPrice.subtract(signalClose) : signalClose.subtract(entryPrice);
        if (worseFill.signum() <= 0) return null;
        BigDecimal maxChase = positive(atrMultiplier) && positive(atr) ? atr.multiply(atrMultiplier) : BigDecimal.ZERO;
        if (positive(rateLimit)) {
            BigDecimal byRate = signalClose.multiply(rateLimit);
            maxChase = positive(maxChase) ? maxChase.min(byRate) : byRate;
        }
        if (!positive(maxChase) || worseFill.compareTo(maxChase) <= 0) return null;
        return "entry price chased signal too far entryPrice=" + entryPrice + " signalClose=" + signalClose
                + " chase=" + worseFill + " maxChase=" + maxChase;
    }

    static long policyVolume(BigDecimal entryPrice, BigDecimal contractSize, BigDecimal notional, BigDecimal minNotional) {
        if (!positive(entryPrice) || !positive(contractSize) || !positive(notional)
                || notional.compareTo(nonNegative(minNotional)) < 0) return 0L;
        return notional.divide(entryPrice.multiply(contractSize), 0, RoundingMode.DOWN).longValue();
    }

    static long periodSeconds(String period) {
        String value = period == null ? "" : period.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.endsWith("min")) return Long.parseLong(value.substring(0, value.length() - 3)) * 60L;
        if (value.endsWith("hour")) return Long.parseLong(value.substring(0, value.length() - 4)) * 3_600L;
        if (value.endsWith("day")) return Long.parseLong(value.substring(0, value.length() - 3)) * 86_400L;
        throw new IllegalArgumentException("unsupported HTX trend period: " + period);
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static BigDecimal nonNegative(BigDecimal value) { return value == null || value.signum() < 0 ? BigDecimal.ZERO : value; }
}
