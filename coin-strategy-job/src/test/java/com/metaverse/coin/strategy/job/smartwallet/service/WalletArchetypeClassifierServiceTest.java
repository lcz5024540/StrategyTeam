package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class WalletArchetypeClassifierServiceTest {

    @Mock
    private com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    @Mock
    private com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    @Mock
    private com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper tradeEventMapper;

    @InjectMocks
    private WalletArchetypeClassifierService classifier;

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
}
