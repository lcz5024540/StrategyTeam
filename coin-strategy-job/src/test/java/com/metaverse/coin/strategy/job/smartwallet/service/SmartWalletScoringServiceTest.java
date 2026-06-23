package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletScoringService;

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
                null,
                new BigDecimal("0.60"),
                new BigDecimal("10000"),
                100,
                null,
                20,
                new BigDecimal("0.20"),
                new BigDecimal("0.10")
        );
        BigDecimal higher = service.score(
                new BigDecimal("0.70"),
                null,
                new BigDecimal("0.90"),
                new BigDecimal("10000"),
                100,
                null,
                20,
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
                null,
                new BigDecimal("5000"),
                80,
                null,
                null,
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

    @Test
    void shouldPenalizeScoreWhenSevenDayWinRateDropsWithEnoughSample() {
        BigDecimal stable = service.score(
                new BigDecimal("0.78"),
                new BigDecimal("0.80"),
                new BigDecimal("0.80"),
                new BigDecimal("12000"),
                140,
                8,
                24,
                new BigDecimal("0.18"),
                new BigDecimal("0.10")
        );
        BigDecimal degraded = service.score(
                new BigDecimal("0.78"),
                new BigDecimal("0.25"),
                new BigDecimal("0.80"),
                new BigDecimal("12000"),
                140,
                8,
                24,
                new BigDecimal("0.18"),
                new BigDecimal("0.10")
        );

        assertTrue(degraded.compareTo(stable) < 0);
    }

    @Test
    void shouldNotOverreactToSevenDayWinRateWhenSampleIsTiny() {
        BigDecimal baseline = service.score(
                new BigDecimal("0.78"),
                null,
                new BigDecimal("0.75"),
                new BigDecimal("12000"),
                140,
                null,
                24,
                new BigDecimal("0.18"),
                new BigDecimal("0.10")
        );
        BigDecimal noisySevenDay = service.score(
                new BigDecimal("0.78"),
                BigDecimal.ZERO,
                new BigDecimal("0.75"),
                new BigDecimal("12000"),
                140,
                1,
                24,
                new BigDecimal("0.18"),
                new BigDecimal("0.10")
        );

        BigDecimal diff = baseline.subtract(noisySevenDay).abs();
        assertTrue(diff.compareTo(new BigDecimal("0.0200")) <= 0);
    }

    @Test
    void shouldKeepBaseScoreWhenBehaviorCompositeIsMissing() {
        BigDecimal base = service.score(
                new BigDecimal("0.78"),
                new BigDecimal("0.74"),
                new BigDecimal("0.76"),
                new BigDecimal("8000"),
                110,
                6,
                18,
                new BigDecimal("0.14"),
                new BigDecimal("0.09")
        );
        BigDecimal same = service.score(
                new BigDecimal("0.78"),
                new BigDecimal("0.74"),
                new BigDecimal("0.76"),
                new BigDecimal("8000"),
                110,
                6,
                18,
                new BigDecimal("0.14"),
                new BigDecimal("0.09"),
                null
        );

        assertEquals(0, base.compareTo(same));
    }

    @Test
    void shouldBoostScoreForStrongBehaviorComposite() {
        BigDecimal withoutBehavior = service.score(
                new BigDecimal("0.78"),
                new BigDecimal("0.74"),
                new BigDecimal("0.76"),
                new BigDecimal("8000"),
                110,
                6,
                18,
                new BigDecimal("0.14"),
                new BigDecimal("0.09")
        );
        BigDecimal withBehavior = service.score(
                new BigDecimal("0.78"),
                new BigDecimal("0.74"),
                new BigDecimal("0.76"),
                new BigDecimal("8000"),
                110,
                6,
                18,
                new BigDecimal("0.14"),
                new BigDecimal("0.09"),
                new BigDecimal("0.90")
        );

        assertTrue(withBehavior.compareTo(withoutBehavior) > 0);
    }

    @Test
    void shouldOnlySlightlyMoveScoreWhenBehaviorCompositeIsWeak() {
        BigDecimal withoutBehavior = service.score(
                new BigDecimal("0.88"),
                new BigDecimal("0.86"),
                new BigDecimal("0.87"),
                new BigDecimal("15000"),
                180,
                8,
                24,
                new BigDecimal("0.20"),
                new BigDecimal("0.08")
        );
        BigDecimal withWeakBehavior = service.score(
                new BigDecimal("0.88"),
                new BigDecimal("0.86"),
                new BigDecimal("0.87"),
                new BigDecimal("15000"),
                180,
                8,
                24,
                new BigDecimal("0.20"),
                new BigDecimal("0.08"),
                new BigDecimal("0.40")
        );

        assertTrue(withoutBehavior.subtract(withWeakBehavior).abs().compareTo(new BigDecimal("0.1000")) < 0);
    }
}
