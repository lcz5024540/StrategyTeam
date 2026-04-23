package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartWalletScoringServiceTest {

    private final SmartWalletScoringService service = new SmartWalletScoringService();

    @Test
    void shouldGiveHigherScoreToHigherEffectiveWinRate() {
        BigDecimal lower = service.score(
                new BigDecimal("0.70"),
                new BigDecimal("0.60"),
                new BigDecimal("10000"),
                100,
                new BigDecimal("0.20"),
                new BigDecimal("0.10")
        );
        BigDecimal higher = service.score(
                new BigDecimal("0.70"),
                new BigDecimal("0.90"),
                new BigDecimal("10000"),
                100,
                new BigDecimal("0.20"),
                new BigDecimal("0.10")
        );

        assertTrue(higher.compareTo(lower) > 0);
    }

    @Test
    void shouldFallbackToHistoricalWinRateWhenRecentWinRateMissing() {
        BigDecimal withFallback = service.score(
                new BigDecimal("0.72"),
                null,
                new BigDecimal("5000"),
                80,
                new BigDecimal("0.15"),
                new BigDecimal("0.08")
        );
        BigDecimal legacyEquivalent = service.score(
                new BigDecimal("0.72"),
                new BigDecimal("5000"),
                80,
                new BigDecimal("0.15"),
                new BigDecimal("0.08")
        );

        assertEquals(0, withFallback.compareTo(legacyEquivalent));
    }
}
