package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletScreenerView;
import com.metaverse.coin.strategy.job.strategy.model.CursorPageView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletProfileAnalysisServiceTest {

    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    @Mock
    private WalletBehaviorProfileService walletBehaviorProfileService;
    @Mock
    private SmartWalletOrderAnalysisService orderAnalysisService;

    private WalletProfileAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new WalletProfileAnalysisService(
                profileMapper,
                walletFeatureDailyMapper,
                walletBehaviorProfileService,
                orderAnalysisService
        );
    }

    @Test
    void screenShouldUsePersistedBehaviorSnapshot() {
        Date now = new Date();
        SmartWalletProfileEntity profile = profile("0xabc");
        when(walletFeatureDailyMapper.selectOne(any())).thenReturn(new SmartWalletWalletFeatureDailyEntity()
                .setPnl30d(new BigDecimal("120.00"))
                .setFollowableRate30d(new BigDecimal("0.61"))
                .setConcentrationMarket30d(new BigDecimal("0.22")));
        when(walletBehaviorProfileService.getPersistedProfile("0xabc")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .alias("alpha")
                .archetype("SWING")
                .followLane("SWING_FOLLOW")
                .highFrequencyTradingAddress(false)
                .closedPositionSampleSize(28)
                .orderWinRate(new BigDecimal("0.7142"))
                .averageOrderRoiRate(new BigDecimal("0.1200"))
                .medianOrderRoiRate(new BigDecimal("0.0900"))
                .settlementWinRate(new BigDecimal("0.6500"))
                .winSourceType("SETTLEMENT_ALPHA")
                .qualityScore(new BigDecimal("0.8100"))
                .replicabilityScore(new BigDecimal("0.7200"))
                .stabilityScore(new BigDecimal("0.6600"))
                .recommendedExitLane("HOLD_TO_SETTLEMENT")
                .summary("persisted snapshot")
                .computedAt(now)
                .build());

        CursorPageView<WalletScreenerView> page = service.screen(
                List.of(profile),
                20,
                null,
                "replicabilityScore",
                "desc",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        WalletScreenerView row = page.items().get(0);
        assertEquals("0xabc", row.walletAddress());
        assertEquals("EXECUTABLE_A", row.screenerTier());
        assertEquals(new BigDecimal("0.8100"), row.qualityScore());
        assertEquals(now, row.behaviorComputedAt());
        assertFalse(row.fallbackUsed());
        assertFalse(row.behaviorStale());
        verify(walletBehaviorProfileService, never()).buildProfile(any());
    }

    @Test
    void screenShouldReturnConservativeFallbackWhenSnapshotMissing() {
        SmartWalletProfileEntity profile = profile("0xdef");
        when(walletBehaviorProfileService.getPersistedProfile("0xdef")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xdef")
                .highFrequencyTradingAddress(false)
                .closedPositionSampleSize(0)
                .orderWinRate(BigDecimal.ZERO)
                .averageOrderRoiRate(BigDecimal.ZERO)
                .medianOrderRoiRate(BigDecimal.ZERO)
                .settlementWinRate(BigDecimal.ZERO)
                .winSourceType("UNKNOWN")
                .qualityScore(BigDecimal.ZERO)
                .replicabilityScore(BigDecimal.ZERO)
                .stabilityScore(BigDecimal.ZERO)
                .recommendedExitLane("OBSERVE_ONLY")
                .summary("No wallet behavior evidence yet.")
                .build());

        CursorPageView<WalletScreenerView> page = service.screen(
                List.of(profile),
                20,
                null,
                "replicabilityScore",
                "desc",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        WalletScreenerView row = page.items().get(0);
        assertEquals("OBSERVE", row.screenerTier());
        assertEquals("OBSERVE_ONLY", row.recommendedExitLane());
        assertEquals("UNKNOWN", row.winSourceType());
        assertTrue(row.fallbackUsed());
        assertTrue(row.behaviorStale());
        verify(walletBehaviorProfileService, never()).buildProfile(any());
    }

    private SmartWalletProfileEntity profile(String walletAddress) {
        return new SmartWalletProfileEntity()
                .setWalletAddress(walletAddress)
                .setAlias("alpha")
                .setWinRate(new BigDecimal("0.68"))
                .setRecentWinRate7d(new BigDecimal("0.70"))
                .setRecentWinRate30d(new BigDecimal("0.69"))
                .setAverageRoiRate(new BigDecimal("0.12"))
                .setRealizedPnlUsdc(new BigDecimal("200.00"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setLastTradeAt(new Date(System.currentTimeMillis() - 2L * 24 * 3600_000L));
    }
}
