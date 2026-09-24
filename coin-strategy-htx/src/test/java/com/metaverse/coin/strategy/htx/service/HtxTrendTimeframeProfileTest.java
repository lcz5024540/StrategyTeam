package com.metaverse.coin.strategy.htx.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtxTrendTimeframeProfileTest {

    @Test
    void swingProfileUsesFourHourEntryAndDailyEnvironment() {
        HtxTrendTimeframeProfile profile = HtxTrendTimeframeProfile.fromPeriod("4hour");

        assertEquals(HtxTrendTimeframeProfile.SWING_4H, profile);
        assertEquals("4hour", profile.entryPeriod());
        assertEquals("1day", profile.environmentPeriod());
        assertEquals(1.45D, profile.trailingMultiplier());
    }

    @Test
    void historicalPositionFallsBackToConfiguredEntryProfile() {
        assertEquals(HtxTrendTimeframeProfile.INTRADAY_15M,
                HtxTrendTimeframeProfile.fromPersisted(null, "15min"));
        assertEquals(HtxTrendTimeframeProfile.TREND_1D,
                HtxTrendTimeframeProfile.fromPersisted("TREND_1D", "15min"));
    }
}
