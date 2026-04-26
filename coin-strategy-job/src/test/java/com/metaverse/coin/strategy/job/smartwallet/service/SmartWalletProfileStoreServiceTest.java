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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void shouldApplyLeaderboardBiasFallbackWithoutOverwritingWinRate() {
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
        assertEquals(new BigDecimal("1.0"), captor.getValue().getWinRate());
        assertTrue(captor.getValue().getWalletScore().compareTo(BigDecimal.ONE) < 0);
        assertTrue(captor.getValue().getHotRetentionReason().contains("LEADERBOARD_BIAS_FALLBACK"));
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
        SmartWalletFollowTaskEntity task3 = new SmartWalletFollowTaskEntity().setId(103L).setWalletAddress("0xee613b").setStatus("FOLLOWED");
        SmartWalletOrderEntity order1 = new SmartWalletOrderEntity().setFollowTaskId(101L).setPositionId(1001L);
        SmartWalletOrderEntity order2 = new SmartWalletOrderEntity().setFollowTaskId(102L).setPositionId(1002L);
        SmartWalletOrderEntity order3 = new SmartWalletOrderEntity().setFollowTaskId(103L).setPositionId(1003L);
        SmartWalletPositionEntity closedLoss = new SmartWalletPositionEntity().setId(1001L).setStatus("CLOSED").setRealizedPnlUsdc(new BigDecimal("-50"));
        SmartWalletPositionEntity claimedWin = new SmartWalletPositionEntity().setId(1002L).setStatus("CLAIMED").setRealizedPnlUsdc(new BigDecimal("20"));
        SmartWalletPositionEntity resolvedLoss = new SmartWalletPositionEntity().setId(1003L).setStatus("RESOLVED_UNCLAIMED").setRealizedPnlUsdc(new BigDecimal("-10"));

        when(profileMapper.selectList(any())).thenReturn(List.of(wallet));
        when(followTaskMapper.selectList(any())).thenReturn(List.of(task1, task2, task3));
        when(orderMapper.selectList(any())).thenReturn(List.of(order1, order2, order3));
        when(positionMapper.selectBatchIds(any())).thenReturn(List.of(closedLoss, claimedWin, resolvedLoss));

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

        service.refreshInternalWinRate();

        ArgumentCaptor<SmartWalletProfileEntity> captor = ArgumentCaptor.forClass(SmartWalletProfileEntity.class);
        verify(profileMapper).updateById(captor.capture());
        assertEquals(new BigDecimal("0.7333"), captor.getValue().getWinRate());
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

    @Test
    void shouldDowngradeWatchEnabledWhenRecentSevenDayPerformanceDegrades() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xcooldown")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.78"))
                .setRecentWinRate30d(new BigDecimal("0.76"))
                .setRecentWinRate7d(new BigDecimal("0.32"))
                .setRecentTradeCount7d(8)
                .setRecentTradeCount30d(20)
                .setRealizedPnlUsdc(new BigDecimal("3000"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.18"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.88"));

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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
        assertNotNull(wallet.getObserveCooldownUntil());
        assertTrue(wallet.getObserveCooldownReason().contains("LOW_7D_WIN_RATE"));
    }

    @Test
    void shouldRecoverWatchEnabledWhenObserveCooldownMetricsNormalize() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xrecover")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.78"))
                .setRecentWinRate30d(new BigDecimal("0.64"))
                .setRecentWinRate7d(new BigDecimal("0.62"))
                .setRecentTradeCount7d(8)
                .setRecentTradeCount30d(20)
                .setRealizedPnlUsdc(new BigDecimal("3000"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.18"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.88"))
                .setObserveCooldownUntil(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2)))
                .setObserveCooldownReason("old reason");

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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
        assertEquals(Boolean.TRUE, wallet.getWatchEnabled());
        assertEquals(null, wallet.getObserveCooldownUntil());
        assertEquals(null, wallet.getObserveCooldownReason());
    }

    @Test
    void shouldAllowManualWatchOverrideToBypassObserveCooldownBlock() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xmanual-force")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.78"))
                .setRecentWinRate30d(new BigDecimal("0.76"))
                .setRecentWinRate7d(new BigDecimal("0.30"))
                .setRecentTradeCount7d(8)
                .setRecentTradeCount30d(20)
                .setRealizedPnlUsdc(new BigDecimal("3000"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.18"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.88"))
                .setManualWatchEnabled(Boolean.TRUE);

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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
        assertEquals(Boolean.TRUE, wallet.getWatchEnabled());
        assertTrue(service.isObserveCooldownActive(wallet));
    }

    @Test
    void shouldPlaceObservedWalletIntoWarmMonitorTier() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xwarm")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.80"))
                .setRecentWinRate30d(new BigDecimal("0.78"))
                .setRecentWinRate7d(new BigDecimal("0.32"))
                .setRecentTradeCount7d(8)
                .setRecentTradeCount30d(20)
                .setRealizedPnlUsdc(new BigDecimal("2500"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.18"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.89"));

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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
        assertEquals("WARM", wallet.getMonitorTier());
        assertTrue(wallet.getMonitorReason().contains("OBSERVE_COOLDOWN"));
    }

    @Test
    void shouldMoveStaleWalletsToWarmBeforeCold() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xcold")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.82"))
                .setRecentWinRate30d(new BigDecimal("0.79"))
                .setRecentTradeCount7d(0)
                .setRecentTradeCount30d(2)
                .setRealizedPnlUsdc(new BigDecimal("2800"))
                .setTotalTrades(160)
                .setAverageRoiRate(new BigDecimal("0.15"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.91"))
                .setLastTradeAt(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)));

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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
        assertEquals("WARM", wallet.getMonitorTier());
        assertTrue(wallet.getMonitorReason().contains("STALE_LAST_TRADE_GT_30D"));
    }

    @Test
    void shouldForceColdMonitorTierForLongStaleWallets() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xcold")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.82"))
                .setRecentWinRate30d(new BigDecimal("0.79"))
                .setRecentTradeCount7d(0)
                .setRecentTradeCount30d(2)
                .setRealizedPnlUsdc(new BigDecimal("2800"))
                .setTotalTrades(160)
                .setAverageRoiRate(new BigDecimal("0.15"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.91"))
                .setLastTradeAt(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(61)));

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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
        assertEquals("COLD", wallet.getMonitorTier());
        assertTrue(wallet.getMonitorReason().contains("STALE_LAST_TRADE_GT_60D"));
    }

    @Test
    void shouldRetainHotWalletWhenScoreIsInsideHysteresisBuffer() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xretain")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.80"))
                .setRealizedPnlUsdc(new BigDecimal("2500"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.15"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.66"))
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE)
                .setMonitorTier("HOT");

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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

        assertEquals(Boolean.FALSE, wallet.getQualified());
        assertEquals(Boolean.TRUE, wallet.getWatchEnabled());
        assertEquals("HOT", wallet.getMonitorTier());
        assertNotNull(wallet.getHotRetentionUntil());
        assertTrue(wallet.getHotRetentionReason().contains("SCORE_NEAR_THRESHOLD"));
    }

    @Test
    void shouldDowngradeWhenHotRetentionExpiresWithoutRecovery() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xexpired")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.80"))
                .setRealizedPnlUsdc(new BigDecimal("2500"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.15"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.66"))
                .setQualified(Boolean.FALSE)
                .setWatchEnabled(Boolean.TRUE)
                .setMonitorTier("HOT")
                .setHotRetentionUntil(new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)))
                .setHotRetentionReason("SCORE_NEAR_THRESHOLD");

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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

        assertEquals(Boolean.FALSE, wallet.getQualified());
        assertEquals(Boolean.FALSE, wallet.getWatchEnabled());
        assertEquals("WARM", wallet.getMonitorTier());
    }

    @Test
    void shouldNotRetainHotWalletWhenScoreIsHardBelowThreshold() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xhard-low")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.80"))
                .setRealizedPnlUsdc(new BigDecimal("2500"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.15"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.40"))
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE)
                .setMonitorTier("HOT");

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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

        assertEquals(Boolean.FALSE, wallet.getQualified());
        assertEquals(Boolean.FALSE, wallet.getWatchEnabled());
        assertEquals("COLD", wallet.getMonitorTier());
        assertEquals(null, wallet.getHotRetentionUntil());
    }

    @Test
    void shouldNotRepeatedlyDecayWalletScoreAcrossActivityDecayRuns() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xdecay")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.82"))
                .setRealizedPnlUsdc(new BigDecimal("2800"))
                .setTotalTrades(160)
                .setAverageRoiRate(new BigDecimal("0.15"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.91"))
                .setWatchEnabled(Boolean.TRUE)
                .setMonitorTier("HOT")
                .setLastTradeAt(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)));
        when(profileMapper.selectList(any())).thenReturn(List.of(wallet));

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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

        service.applyActivityDecay();
        service.applyActivityDecay();

        assertEquals(new BigDecimal("0.91"), wallet.getWalletScore());
        assertEquals("WARM", wallet.getMonitorTier());
    }

    @Test
    void shouldAllowExecutableCandidateWhenArchetypeEvidenceIsInsufficient() {
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xinsufficient")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.82"))
                .setRealizedPnlUsdc(new BigDecimal("2800"))
                .setTotalTrades(160)
                .setAverageRoiRate(new BigDecimal("0.15"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.91"));
        when(walletArchetypeClassifierService.classify("0xinsufficient", wallet)).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xinsufficient")
                .archetype("NOISE_OR_INSUFFICIENT")
                .followLane("OBSERVE_ONLY")
                .labelSampleSize(4)
                .followFitScore(BigDecimal.ZERO)
                .build());

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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
        assertEquals(Boolean.TRUE, wallet.getWatchEnabled());
        assertEquals("HOT", wallet.getMonitorTier());
    }

    @Test
    void shouldRecoverColdWalletWhenNewTradeActivityIsObserved() {
        Date oldTradeAt = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(120));
        Date newTradeAt = new Date();
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setId(99L)
                .setWalletAddress("0xrecover-cold")
                .setSource("POLYMARKET")
                .setWinRate(new BigDecimal("0.82"))
                .setRecentWinRate30d(new BigDecimal("0.79"))
                .setRealizedPnlUsdc(new BigDecimal("2800"))
                .setTotalTrades(160)
                .setAverageRoiRate(new BigDecimal("0.15"))
                .setMaxDrawdownRate(new BigDecimal("0.12"))
                .setWalletScore(new BigDecimal("0.91"))
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.FALSE)
                .setMonitorTier("COLD")
                .setLastTradeAt(oldTradeAt);
        when(profileMapper.selectOne(any())).thenReturn(wallet);
        when(walletBehaviorProfileService.getProfile("0xrecover-cold")).thenReturn(null);
        when(walletArchetypeClassifierService.classify("0xrecover-cold", wallet)).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xrecover-cold")
                .archetype("SWING")
                .followLane("SWING_FOLLOW")
                .labelSampleSize(10)
                .followFitScore(new BigDecimal("0.65"))
                .build());

        SmartWalletProperties properties = new SmartWalletProperties();
        properties.setMinWinRate(new BigDecimal("0.60"));
        properties.setMinWalletScore(new BigDecimal("0.70"));
        properties.setMinTotalTrades(10);
        properties.setMinRealizedPnlUsdc(new BigDecimal("100"));
        properties.setMaxDrawdownRate(new BigDecimal("0.30"));

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

        service.recordObservedTradeActivity("0xrecover-cold", newTradeAt);

        assertEquals(newTradeAt, wallet.getLastTradeAt());
        assertEquals(Boolean.TRUE, wallet.getQualified());
        assertEquals(Boolean.TRUE, wallet.getWatchEnabled());
        assertEquals("HOT", wallet.getMonitorTier());
        verify(profileMapper).updateById(wallet);
    }
}
