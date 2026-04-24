package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletProfileStoreServiceTest {

    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private SmartWalletOrderMapper orderMapper;
    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private WalletArchetypeClassifierService walletArchetypeClassifierService;
    @Mock
    private WalletBehaviorProfileService walletBehaviorProfileService;

    @Test
    void shouldKeepLeaderboardBiasFallbackWhenUsingRecentWinRateScoring() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("1.0"))
                .setRecentWinRate30d(new BigDecimal("0.95"))
                .setRealizedPnlUsdc(new BigDecimal("1200"))
                .setTotalTrades(20)
                .setAverageRoiRate(new BigDecimal("0.30"))
                .setMaxDrawdownRate(new BigDecimal("0.10"));
        when(profileMapper.selectList(any())).thenReturn(List.of(wallet));
        when(followTaskMapper.selectList(any())).thenReturn(List.of());

        SmartWalletProperties properties = new SmartWalletProperties();
        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                properties,
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                orderMapper,
                positionMapper,
                walletArchetypeClassifierService,
                walletBehaviorProfileService
        );

        service.refreshInternalWinRate();

        ArgumentCaptor<SmartWalletProfileEntity> captor = ArgumentCaptor.forClass(SmartWalletProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertEquals(new BigDecimal("0.65"), captor.getValue().getWinRate());
        assertTrue(captor.getValue().getWalletScore().compareTo(BigDecimal.ONE) < 0);
    }

    @Test
    void shouldUseFollowTaskPositionLinksAndClosedStatusesForInternalWinRateRefresh() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xee613b")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("1.0"))
                .setRecentWinRate30d(new BigDecimal("1.0"))
                .setRealizedPnlUsdc(new BigDecimal("1200"))
                .setTotalTrades(50)
                .setAverageRoiRate(new BigDecimal("0.12"))
                .setMaxDrawdownRate(new BigDecimal("0.10"))
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE);
        SmartWalletFollowTaskEntity task1 = new SmartWalletFollowTaskEntity().setId(101L).setWalletAddress("0xee613b").setStatus("FOLLOWED");
        SmartWalletFollowTaskEntity task2 = new SmartWalletFollowTaskEntity().setId(102L).setWalletAddress("0xee613b").setStatus("FOLLOWED");
        SmartWalletOrderEntity order1 = new SmartWalletOrderEntity().setFollowTaskId(101L).setPositionId(1001L);
        SmartWalletOrderEntity order2 = new SmartWalletOrderEntity().setFollowTaskId(102L).setPositionId(1002L);
        SmartWalletPositionEntity closedLoss = new SmartWalletPositionEntity().setId(1001L).setStatus("CLOSED").setRealizedPnlUsdc(new BigDecimal("-50"));
        SmartWalletPositionEntity claimedWin = new SmartWalletPositionEntity().setId(1002L).setStatus("CLAIMED").setRealizedPnlUsdc(new BigDecimal("20"));

        when(profileMapper.selectList(any())).thenReturn(List.of(wallet));
        when(followTaskMapper.selectList(any())).thenReturn(List.of(task1, task2));
        when(orderMapper.selectList(any())).thenReturn(List.of(order1, order2));
        when(positionMapper.selectBatchIds(any())).thenReturn(List.of(closedLoss, claimedWin));

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.80"));
        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                properties,
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                orderMapper,
                positionMapper,
                walletArchetypeClassifierService,
                walletBehaviorProfileService
        );

        when(walletBehaviorProfileService.getProfile("0xee613b")).thenReturn(null);
        when(walletArchetypeClassifierService.classify("0xee613b", wallet)).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xee613b")
                .archetype("SWING")
                .followLane("SWING_FOLLOW")
                .labelSampleSize(10)
                .followFitScore(new BigDecimal("0.65"))
                .build());

        service.refreshInternalWinRate();

        ArgumentCaptor<SmartWalletProfileEntity> captor = ArgumentCaptor.forClass(SmartWalletProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertEquals(new BigDecimal("0.7000"), captor.getValue().getWinRate());
        assertEquals(Boolean.FALSE, captor.getValue().getQualified());
        assertEquals(Boolean.FALSE, captor.getValue().getWatchEnabled());
    }

    @Test
    void shouldDisableExecutableWatchlistForFastExitOrSkipWallet() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xskip")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.85"))
                .setRecentWinRate30d(new BigDecimal("0.82"))
                .setRealizedPnlUsdc(new BigDecimal("1500"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.18"))
                .setMaxDrawdownRate(new BigDecimal("0.10"))
                .setWalletScore(new BigDecimal("0.92"))
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE);

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

        when(walletBehaviorProfileService.getProfile("0xskip")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xskip")
                .recommendedExitLane("FAST_EXIT_OR_SKIP")
                .build());

        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                properties,
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                orderMapper,
                positionMapper,
                walletArchetypeClassifierService,
                walletBehaviorProfileService
        );

        service.applyQualificationState(wallet);

        assertEquals(Boolean.TRUE, wallet.getQualified());
        assertEquals(Boolean.FALSE, wallet.getWatchEnabled());
    }

    @Test
    void shouldApplyManualOverrideForWatchEnabledAndExitLane() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setId(88L)
                .setWalletAddress("0xmanual")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.85"))
                .setRecentWinRate30d(new BigDecimal("0.82"))
                .setRealizedPnlUsdc(new BigDecimal("1500"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.18"))
                .setMaxDrawdownRate(new BigDecimal("0.10"))
                .setWalletScore(new BigDecimal("0.92"))
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE);
        when(profileMapper.selectOne(any())).thenReturn(wallet);

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));
        when(walletBehaviorProfileService.getProfile("0xmanual")).thenReturn(null);
        when(walletArchetypeClassifierService.classify("0xmanual", wallet)).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xmanual")
                .archetype("SWING")
                .followLane("SWING_FOLLOW")
                .labelSampleSize(10)
                .followFitScore(new BigDecimal("0.65"))
                .build());

        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                properties,
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                orderMapper,
                positionMapper,
                walletArchetypeClassifierService,
                walletBehaviorProfileService
        );
        SmartWalletProfileOverrideRequest request = new SmartWalletProfileOverrideRequest();
        request.setManualWatchEnabled(Boolean.FALSE);
        request.setManualRecommendedExitLane("HOLD_TO_SETTLEMENT");
        request.setNote("manual ops");

        SmartWalletProfileEntity updated = service.updateManualOverride("0xmanual", request);

        assertEquals(Boolean.FALSE, updated.getManualWatchEnabled());
        assertEquals("HOLD_TO_SETTLEMENT", updated.getManualRecommendedExitLane());
        assertEquals("manual ops", updated.getManualOverrideNote());
        assertEquals(Boolean.FALSE, updated.getWatchEnabled());
    }

    @Test
    void shouldListManualOverrides() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xmanual")
                .setAlias("manual-wallet")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.FALSE)
                .setManualWatchEnabled(Boolean.FALSE)
                .setManualRecommendedExitLane("HOLD_TO_SETTLEMENT")
                .setManualOverrideNote("manual ops");
        when(profileMapper.selectList(any())).thenReturn(List.of(wallet));

        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                new SmartWalletProperties(),
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                orderMapper,
                positionMapper,
                walletArchetypeClassifierService,
                walletBehaviorProfileService
        );

        List<SmartWalletProfileOverrideView> rows = service.listManualOverrides(20);

        assertEquals(1, rows.size());
        assertEquals("0xmanual", rows.get(0).walletAddress());
        assertEquals("HOLD_TO_SETTLEMENT", rows.get(0).manualRecommendedExitLane());
    }

    @Test
    void shouldSummarizeManualOverrides() {
        Date now = new Date();
        SmartWalletProfileEntity observeOnly = new SmartWalletProfileEntity()
                .setWalletAddress("0xobserve")
                .setManualWatchEnabled(Boolean.FALSE)
                .setManualRecommendedExitLane("FAST_STOP_LOSS")
                .setManualOverrideUpdatedAt(now);
        SmartWalletProfileEntity forceExecutable = new SmartWalletProfileEntity()
                .setWalletAddress("0xforce")
                .setManualWatchEnabled(Boolean.TRUE)
                .setManualOverrideUpdatedAt(now);
        SmartWalletProfileEntity noteOnly = new SmartWalletProfileEntity()
                .setWalletAddress("0xnote")
                .setManualOverrideNote("ops note")
                .setManualOverrideUpdatedAt(new Date(now.getTime() - TimeUnit.DAYS.toMillis(2)));
        when(profileMapper.selectList(any())).thenReturn(List.of(observeOnly, forceExecutable, noteOnly));

        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                new SmartWalletProperties(),
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                orderMapper,
                positionMapper,
                walletArchetypeClassifierService,
                walletBehaviorProfileService
        );

        SmartWalletProfileOverrideSummaryView summary = service.summarizeManualOverrides();

        assertEquals(3, summary.totalOverrides());
        assertEquals(1, summary.observeOnlyOverrides());
        assertEquals(1, summary.forceExecutableOverrides());
        assertEquals(1, summary.exitLaneOverrides());
        assertEquals(1, summary.noteOnlyOverrides());
        assertEquals(2, summary.updatedLast24h());
        assertEquals(1, summary.exitLaneCounts().get("FAST_STOP_LOSS"));
    }

    @Test
    void shouldBatchUpdateManualOverridesAndReportMissingWallets() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setId(88L)
                .setWalletAddress("0xmanual")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.85"))
                .setRecentWinRate30d(new BigDecimal("0.82"))
                .setRealizedPnlUsdc(new BigDecimal("1500"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.18"))
                .setMaxDrawdownRate(new BigDecimal("0.10"))
                .setWalletScore(new BigDecimal("0.92"))
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE);
        when(profileMapper.selectOne(any()))
                .thenReturn(wallet)
                .thenReturn(null);
        when(walletBehaviorProfileService.getProfile("0xmanual")).thenReturn(null);
        when(walletArchetypeClassifierService.classify("0xmanual", wallet)).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xmanual")
                .archetype("SWING")
                .followLane("SWING_FOLLOW")
                .labelSampleSize(10)
                .followFitScore(new BigDecimal("0.65"))
                .build());

        SmartWalletProfileStoreService service = new SmartWalletProfileStoreService(
                new SmartWalletProperties(),
                profileMapper,
                new SmartWalletScoringService(),
                followTaskMapper,
                orderMapper,
                positionMapper,
                walletArchetypeClassifierService,
                walletBehaviorProfileService
        );
        SmartWalletProfileBatchOverrideRequest request = new SmartWalletProfileBatchOverrideRequest();
        request.setWalletAddresses(List.of("0xmanual", "0xmissing", "0xmanual"));
        request.setManualWatchEnabled(Boolean.FALSE);
        request.setNote("batch ops");

        SmartWalletProfileBatchOverrideResultView result = service.batchUpdateManualOverrides(request);

        assertEquals(3, result.requestedCount());
        assertEquals(2, result.uniqueWalletCount());
        assertEquals(1, result.updatedCount());
        assertEquals(1, result.missingCount());
        assertEquals(List.of("0xmanual"), result.updatedWalletAddresses());
        assertEquals(List.of("0xmissing"), result.missingWalletAddresses());
    }
}
