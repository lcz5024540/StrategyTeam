package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpInstrument.SpotPerpVenue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Retail VIP0 / Prime0 maker and taker rates as a percent of fill notional.
 *
 * <p>Spot maker equals spot taker on both venues at the entry tier, so a maker
 * harvest only saves money on the perpetual leg. Rates are 2026 published
 * schedules without BNB/HT discounts.</p>
 */
public record SpotPerpFeeSchedule(
        BigDecimal spotMaker,
        BigDecimal spotTaker,
        BigDecimal perpMaker,
        BigDecimal perpTaker
) {
    public static final SpotPerpFeeSchedule BINANCE_VIP0 = new SpotPerpFeeSchedule(
            new BigDecimal("0.0010"),
            new BigDecimal("0.0010"),
            new BigDecimal("0.0002"),
            new BigDecimal("0.0005"));

    public static final SpotPerpFeeSchedule HTX_PRIME0 = new SpotPerpFeeSchedule(
            new BigDecimal("0.0020"),
            new BigDecimal("0.0020"),
            new BigDecimal("0.0002"),
            new BigDecimal("0.0006"));

    public SpotPerpFeeSchedule {
        Objects.requireNonNull(spotMaker, "spotMaker");
        Objects.requireNonNull(spotTaker, "spotTaker");
        Objects.requireNonNull(perpMaker, "perpMaker");
        Objects.requireNonNull(perpTaker, "perpTaker");
        if (spotMaker.signum() < 0 || spotTaker.signum() < 0
                || perpMaker.signum() < 0 || perpTaker.signum() < 0) {
            throw new IllegalArgumentException("fee rates must be >= 0");
        }
    }

    public static SpotPerpFeeSchedule forVenue(SpotPerpVenue venue) {
        return switch (Objects.requireNonNull(venue, "venue")) {
            case BINANCE -> BINANCE_VIP0;
            case HTX -> HTX_PRIME0;
        };
    }

    public BigDecimal spot(boolean maker) {
        return maker ? spotMaker : spotTaker;
    }

    public BigDecimal perp(boolean maker) {
        return maker ? perpMaker : perpTaker;
    }

    /** Open plus close, both legs, as a fraction of notional. */
    public BigDecimal roundTrip(SpotPerpExecutionStyle style) {
        Objects.requireNonNull(style, "style");
        BigDecimal oneWay = spot(style.spotMaker()).add(perp(style.perpMaker()));
        return oneWay.add(oneWay).setScale(8, RoundingMode.HALF_UP);
    }

    public BigDecimal roundTripBps(SpotPerpExecutionStyle style) {
        return roundTrip(style).movePointRight(4);
    }
}
