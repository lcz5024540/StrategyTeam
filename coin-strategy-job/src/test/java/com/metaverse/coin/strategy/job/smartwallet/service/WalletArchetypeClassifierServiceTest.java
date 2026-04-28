package com.metaverse.coin.strategy.job.smartwallet.service;

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
