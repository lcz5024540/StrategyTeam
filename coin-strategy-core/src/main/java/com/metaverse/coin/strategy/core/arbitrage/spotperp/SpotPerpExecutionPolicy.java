package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import java.util.Objects;

/**
 * Paper-only execution policy layered on {@link SpotPerpRules}.
 *
 * @param style                taker IOC, maker limit, or mixed
 * @param publishedLegFees     when true, charge VIP0/Prime0 per-leg bps; when false,
 *                             keep the production lump {@code roundTrip / 2} per fill
 * @param makerFillDelayPeriods 0 = fill on the same 8h bar (optimistic, no L2);
 *                             1 = rest one bar then fill (conservative)
 */
public record SpotPerpExecutionPolicy(
        SpotPerpExecutionStyle style,
        boolean publishedLegFees,
        int makerFillDelayPeriods
) {
    public SpotPerpExecutionPolicy {
        Objects.requireNonNull(style, "style");
        if (makerFillDelayPeriods < 0 || makerFillDelayPeriods > 6) {
            throw new IllegalArgumentException("makerFillDelayPeriods must be 0..6");
        }
        if (style == SpotPerpExecutionStyle.TAKER_IOC && makerFillDelayPeriods != 0) {
            throw new IllegalArgumentException("taker IOC is immediate; delay must be 0");
        }
    }

    public static SpotPerpExecutionPolicy productionTaker() {
        return new SpotPerpExecutionPolicy(SpotPerpExecutionStyle.TAKER_IOC, false, 0);
    }

    public static SpotPerpExecutionPolicy publishedTaker() {
        return new SpotPerpExecutionPolicy(SpotPerpExecutionStyle.TAKER_IOC, true, 0);
    }

    public static SpotPerpExecutionPolicy schemeB(SpotPerpExecutionStyle style, int delayPeriods) {
        return new SpotPerpExecutionPolicy(style, true, delayPeriods);
    }
}
