package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotPerpFeeScheduleTest {
    @Test
    void binanceVip0TakerRoundTripIs30Bps() {
        assertEquals(0, new BigDecimal("0.00300000").compareTo(
                SpotPerpFeeSchedule.BINANCE_VIP0.roundTrip(SpotPerpExecutionStyle.TAKER_IOC)));
        assertEquals(0, new BigDecimal("30").compareTo(
                SpotPerpFeeSchedule.BINANCE_VIP0.roundTripBps(SpotPerpExecutionStyle.TAKER_IOC)));
    }

    @Test
    void binanceMakerSavesOnlyOnThePerpLeg() {
        BigDecimal maker = SpotPerpFeeSchedule.BINANCE_VIP0.roundTrip(SpotPerpExecutionStyle.MAKER_LIMIT);
        BigDecimal mixedPerpMaker = SpotPerpFeeSchedule.BINANCE_VIP0.roundTrip(
                SpotPerpExecutionStyle.MIXED_SPOT_TAKER_PERP_MAKER);
        BigDecimal mixedSpotMaker = SpotPerpFeeSchedule.BINANCE_VIP0.roundTrip(
                SpotPerpExecutionStyle.MIXED_SPOT_MAKER_PERP_TAKER);
        BigDecimal taker = SpotPerpFeeSchedule.BINANCE_VIP0.roundTrip(SpotPerpExecutionStyle.TAKER_IOC);
        assertEquals(0, new BigDecimal("0.00240000").compareTo(maker));
        assertEquals(0, maker.compareTo(mixedPerpMaker));
        assertEquals(0, taker.compareTo(mixedSpotMaker));
        assertTrue(maker.compareTo(taker) < 0);
    }

    @Test
    void htxPrime0TakerRoundTripIs52Bps() {
        assertEquals(0, new BigDecimal("0.00520000").compareTo(
                SpotPerpFeeSchedule.HTX_PRIME0.roundTrip(SpotPerpExecutionStyle.TAKER_IOC)));
        assertEquals(0, new BigDecimal("0.00440000").compareTo(
                SpotPerpFeeSchedule.HTX_PRIME0.roundTrip(SpotPerpExecutionStyle.MAKER_LIMIT)));
    }
}
