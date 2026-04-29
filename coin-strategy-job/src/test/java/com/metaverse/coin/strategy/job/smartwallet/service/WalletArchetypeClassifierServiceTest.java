package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class WalletArchetypeClassifierServiceTest {

    @Mock
    private com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    @Mock
    private com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    @Mock
    private com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper tradeEventMapper;

    private SmartWalletProperties properties;
    private WalletArchetypeClassifierService classifier;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        classifier = new WalletArchetypeClassifierService(
                properties, walletFeatureDailyMapper, tradeEffectLabelMapper, tradeEventMapper);
    }

    // Bug: switch (archetype) uses case "HF" but the archetype variable is "HF_SCALPER".
    // Result: HF_SCALPER wallets fall to default → NOISE_SYNC_INTERVAL_MS (900s) instead of HF_SYNC_INTERVAL_MS (30s).
    @Test
    void classify_hfScalperWallet_recommendedSyncIntervalMustBe30Seconds() {
        SmartWalletWalletFeatureDailyEntity feature = new SmartWalletWalletFeatureDailyEntity();
        feature.setTrades7d(20);          // ≥18 → HF_SCALPER archetype
        feature.setTrades30d(80);
        feature.setMarkets30d(15);
        feature.setConcentrationMarket30d(new BigDecimal("0.30"));
        feature.setFollowableRate30d(new BigDecimal("0.55"));
        feature.setWinRate30d(new BigDecimal("0.60"));

        WalletArchetypeView view = classifier.classify("0xhf", null, feature, List.of(), List.of());

        assertEquals("HF_SCALPER", view.archetype(),
                "High-frequency wallet (trades7d=20) must be classified as HF_SCALPER.");
        assertEquals(30_000L, view.recommendedSyncIntervalMs(),
                "HF_SCALPER wallet must get 30s sync interval, not the 900s noise default.");
    }

    @Test
    void classify_hfScalperThresholdIsConfigurable_respectsCustomMinTrades7d() {
        // Bug: trades7d >= 18 is hardcoded. Operators cannot tune the HF_SCALPER frequency threshold.
        // Fix: read hfScalperMinTrades7d from config.
        //
        // trades7d=16 is below hardcoded 18 but above configured 15.
        // With hardcoded 18: 16 < 18 → not HF_SCALPER (falls to SWING via followableRate≥0.55)
        // With config hfScalperMinTrades7d=15: 16 >= 15 → HF_SCALPER
        properties.getSignalRisk().setHfScalperMinTrades7d(15);

        SmartWalletWalletFeatureDailyEntity feature = new SmartWalletWalletFeatureDailyEntity();
        feature.setTrades7d(16);           // 16 < 18 (hardcoded) but 16 >= 15 (configured)
        feature.setTrades30d(60);
        feature.setMarkets30d(8);
        feature.setConcentrationMarket30d(new BigDecimal("0.30"));
        feature.setFollowableRate30d(new BigDecimal("0.55"));
        feature.setWinRate30d(new BigDecimal("0.60"));
        // No labels: all alphas = 0 → alpha30s(0) > max(alphaRule(0), alpha5m(0)) = 0 is false
        // → other HF conditions (median, alpha30s) don't fire

        WalletArchetypeView view = classifier.classify("0xtest", null, feature, List.of(), List.of());
        assertEquals("HF_SCALPER", view.archetype(),
                "trades7d=16 must be HF_SCALPER when hfScalperMinTrades7d=15 (not hardcoded 18). Got: "
                        + view.archetype());
    }

    @Test
    void classify_thematicThresholdIsConfigurable_respectsCustomMinAlphaRule() {
        // Bug: alphaRule >= 0.58 is hardcoded for THEMATIC classification.
        // Fix: read thematicMinAlphaRule from config.
        //
        // alphaRule=0.55 is below hardcoded 0.58 but above configured 0.52.
        // With hardcoded 0.58: 0.55 < 0.58 → not THEMATIC
        // With config thematicMinAlphaRule=0.52: 0.55 >= 0.52 → THEMATIC
        properties.getSignalRisk().setThematicMinAlphaRule(new BigDecimal("0.52"));

        SmartWalletWalletFeatureDailyEntity feature = new SmartWalletWalletFeatureDailyEntity();
        feature.setTrades7d(3);            // well below any HF threshold
        feature.setTrades30d(15);
        feature.setMarkets30d(4);          // ≤ 6 → THEMATIC candidate
        feature.setConcentrationMarket30d(new BigDecimal("0.65")); // ≥ 0.55
        feature.setFollowableRate30d(new BigDecimal("0.40"));
        feature.setWinRate30d(new BigDecimal("0.50"));

        // 20 labels: alphaRule=11/20=0.55, alpha30s=8/20=0.40, alpha5m=8/20=0.40
        // alphaRule(0.55) >= alpha30s(0.40)+0.08=0.48 ✓
        // alpha30s(0.40) > max(alphaRule(0.55), alpha5m(0.40)) → 0.40 > 0.55 is false → skip HF
        List<SmartWalletTradeEffectLabelEntity> labels = buildLabels(8, 8, 11, 20);

        WalletArchetypeView view = classifier.classify("0xtest", null, feature, labels, List.of());
        assertEquals("THEMATIC", view.archetype(),
                "alphaRule=0.55 must be THEMATIC when thematicMinAlphaRule=0.52 (not hardcoded 0.58). Got: "
                        + view.archetype());
    }

    @Test
    void classify_followFitWeightsAreConfigurable_respectsCustomFollowableRateWeight() {
        // Bug: followFitScore weights are hardcoded (followableRate=0.30).
        // Fix: read weights from FollowFitWeights config.
        //
        // With wallet having followableRate=1.0 and all other dimensions=0:
        //   custom weight 0.50: score ≈ 1.0*0.50 = 0.50
        //   hardcoded 0.30:     score ≈ 1.0*0.30 = 0.30
        properties.getFollowFitWeights().setFollowableRate(new BigDecimal("0.50")); // default 0.30

        SmartWalletWalletFeatureDailyEntity feature = new SmartWalletWalletFeatureDailyEntity();
        feature.setTrades7d(3);
        feature.setTrades30d(10);
        feature.setMarkets30d(8);
        feature.setConcentrationMarket30d(new BigDecimal("0.20"));
        feature.setFollowableRate30d(new BigDecimal("1.00")); // only non-zero dimension
        feature.setWinRate30d(BigDecimal.ZERO);
        // No labels: all alphas = 0; no profile: leaderQuality = 0

        WalletArchetypeView view = classifier.classify("0xtest", null, feature, List.of(), List.of());
        assertTrue(view.followFitScore().compareTo(new BigDecimal("0.49")) > 0,
                "followFitScore must use configured followableRate weight 0.50 (not hardcoded 0.30). Got: "
                        + view.followFitScore());
    }

    // ─── LONG_HORIZON_SPECULATOR tests ────────────────────────────────────────

    /**
     * A high-quality wallet (walletScore=0.68) with low short-horizon alpha
     * (followableRate=0.33, alphaRule=0.30) — typical of sports-market bettors.
     * Before fix: classified as NOISE_OR_INSUFFICIENT → OBSERVE_ONLY lane → G2 block.
     * After fix: classified as LONG_HORIZON_SPECULATOR → VALUE_FOLLOW lane → G2 pass.
     */
    @Test
    void classify_highQualityWallet_withLowShortHorizonAlpha_mustBeLongHorizonSpeculator() {
        SmartWalletWalletFeatureDailyEntity feature = new SmartWalletWalletFeatureDailyEntity();
        feature.setTrades7d(2);
        feature.setTrades30d(8);          // >= longHorizonSpeculatorMinTrades30d default 3
        feature.setMarkets30d(5);
        feature.setConcentrationMarket30d(new BigDecimal("0.40"));
        feature.setFollowableRate30d(new BigDecimal("0.33"));  // < 0.40 threshold
        feature.setWinRate30d(new BigDecimal("0.55"));

        // 10 labels where only 3 are positive-rule → alphaRule=0.30 < 0.45
        List<SmartWalletTradeEffectLabelEntity> labels = buildLabels(3, 3, 3, 10);

        SmartWalletProfileEntity profile = new SmartWalletProfileEntity();
        profile.setWalletAddress("0xhq");
        profile.setWalletScore(new BigDecimal("0.68")); // >= archetypeLeaderQualityBypassMin 0.65

        WalletArchetypeView view = classifier.classify("0xhq", profile, feature, labels, List.of());

        assertEquals("LONG_HORIZON_SPECULATOR", view.archetype(),
                "High-quality wallet (score=0.68) with low short-horizon alpha must be " +
                "LONG_HORIZON_SPECULATOR, not NOISE_OR_INSUFFICIENT. Got: " + view.archetype());
        assertEquals("VALUE_FOLLOW", view.followLane(),
                "LONG_HORIZON_SPECULATOR must have VALUE_FOLLOW lane. Got: " + view.followLane());
    }

    /**
     * A low-quality wallet (walletScore=0.55) with low short-horizon alpha must still be
     * classified as NOISE_OR_INSUFFICIENT — the bypass only applies to high-quality wallets.
     */
    @Test
    void classify_lowQualityWallet_withLowShortHorizonAlpha_mustRemainNoise() {
        SmartWalletWalletFeatureDailyEntity feature = new SmartWalletWalletFeatureDailyEntity();
        feature.setTrades7d(2);
        feature.setTrades30d(8);
        feature.setMarkets30d(5);
        feature.setConcentrationMarket30d(new BigDecimal("0.40"));
        feature.setFollowableRate30d(new BigDecimal("0.33"));
        feature.setWinRate30d(new BigDecimal("0.50"));

        List<SmartWalletTradeEffectLabelEntity> labels = buildLabels(3, 3, 3, 10);

        SmartWalletProfileEntity profile = new SmartWalletProfileEntity();
        profile.setWalletAddress("0xlq");
        profile.setWalletScore(new BigDecimal("0.55")); // < archetypeLeaderQualityBypassMin 0.65

        WalletArchetypeView view = classifier.classify("0xlq", profile, feature, labels, List.of());

        assertEquals("NOISE_OR_INSUFFICIENT", view.archetype(),
                "Low-quality wallet (score=0.55) with low short-horizon alpha must remain " +
                "NOISE_OR_INSUFFICIENT. Got: " + view.archetype());
        assertEquals("OBSERVE_ONLY", view.followLane(),
                "NOISE_OR_INSUFFICIENT must map to OBSERVE_ONLY lane.");
    }

    /**
     * LONG_HORIZON_SPECULATOR is assigned VALUE_SYNC_INTERVAL_MS (600 s) — the same
     * as LOW_FREQ_VALUE — because long-horizon wallets trade infrequently and do not
     * need aggressive sync. The NOISE default (900 s) is incorrect for them.
     */
    @Test
    void classify_longHorizonSpeculator_syncIntervalMustBe600Seconds() {
        SmartWalletWalletFeatureDailyEntity feature = new SmartWalletWalletFeatureDailyEntity();
        feature.setTrades7d(1);
        feature.setTrades30d(5);
        feature.setMarkets30d(3);
        feature.setConcentrationMarket30d(new BigDecimal("0.35"));
        feature.setFollowableRate30d(new BigDecimal("0.30"));
        feature.setWinRate30d(new BigDecimal("0.60"));

        List<SmartWalletTradeEffectLabelEntity> labels = buildLabels(2, 2, 2, 8);

        SmartWalletProfileEntity profile = new SmartWalletProfileEntity();
        profile.setWalletAddress("0xsync");
        profile.setWalletScore(new BigDecimal("0.72"));

        WalletArchetypeView view = classifier.classify("0xsync", profile, feature, labels, List.of());

        assertEquals("LONG_HORIZON_SPECULATOR", view.archetype(),
                "High-quality wallet with low short-horizon alpha must be LONG_HORIZON_SPECULATOR. Got: "
                        + view.archetype());
        assertEquals(600_000L, view.recommendedSyncIntervalMs(),
                "LONG_HORIZON_SPECULATOR must get 600s sync interval (VALUE_SYNC_INTERVAL_MS). Got: "
                        + view.recommendedSyncIntervalMs());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Build `total` labels where `positive30s / positive5m / positiveRule` entries are positive. */
    private List<SmartWalletTradeEffectLabelEntity> buildLabels(
            int positive30s, int positive5m, int positiveRule, int total) {
        List<SmartWalletTradeEffectLabelEntity> labels = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            labels.add(new SmartWalletTradeEffectLabelEntity()
                    .setIsPositiveEv30s(i < positive30s)
                    .setIsPositiveEv5m(i < positive5m)
                    .setIsPositiveEvRule(i < positiveRule));
        }
        return labels;
    }
}
