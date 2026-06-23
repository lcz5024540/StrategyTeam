package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.MarketStateSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualBlocklistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletLeaderTradeOutcomeMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletFollowCandidateMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletFollowCandidateEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityResolutionResult;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.service.WalletEntityResolutionService;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletFollowTaskServiceTest {

    @Mock
    private SmartWalletProperties properties;
    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private SmartWalletLeaderTradeOutcomeMapper leaderOutcomeMapper;
    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletProfileStoreService profileStoreService;
    @Mock
    private PolymarketExecutionGateway executionGateway;
    @Mock
    private SmartWalletSignalRiskService signalRiskService;
    @Mock
    private PolymarketConsensusSignalService consensusSignalService;
    @Mock
    private WalletEntityResolutionService entityResolutionService;
    @Mock
    private WalletFollowCandidateMapper followCandidateMapper;
    @Mock
    private SmartWalletAccountingService accountingService;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;
    @Mock
    private SmartWalletTimeFormatter timeFormatter;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private SmartWalletMarketStateService marketStateService;
    @Mock
    private SmartWalletManualAllowlistService manualAllowlistService;
    @Mock
    private SmartWalletManualBlocklistService manualBlocklistService;
    @Mock
    private FollowPositionAdjustmentService followPositionAdjustmentService;

    @InjectMocks
    private SmartWalletFollowTaskService service;

    @BeforeEach
    void setUp() {
        AtomicReference<SmartWalletMarketInfoEntity> marketInfoRef = new AtomicReference<>();
        lenient().when(marketInfoService.populate(any(SmartWalletFollowTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(marketInfoService.getOrCreate(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    SmartWalletMarketInfoEntity marketInfo = new SmartWalletMarketInfoEntity()
                        .setId(9001L)
                        .setMarketId(invocation.getArgument(0))
                        .setQuestion(invocation.getArgument(2))
                        .setOutcome(invocation.getArgument(1))
                        .setMarketEndAt(invocation.getArgument(5));
                    marketInfoRef.set(marketInfo);
                    return marketInfo;
                });
        lenient().when(marketInfoService.getById(anyLong()))
                .thenAnswer(invocation -> {
                    SmartWalletMarketInfoEntity marketInfo = marketInfoRef.get();
                    if (marketInfo != null && marketInfo.getId().equals(invocation.getArgument(0))) {
                        return marketInfo;
                    }
                    return new SmartWalletMarketInfoEntity().setId(invocation.getArgument(0));
                });
        lenient().when(marketStateService.refreshMarket(any(), any(), any()))
                .thenReturn(MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .build());
        lenient().when(manualAllowlistService.findActive(any())).thenReturn(java.util.Optional.empty());
    }

    @Test
    void shouldSortExecutableTasksByEvBeforeStake() {
        SmartWalletFollowTaskEntity lowerEvHigherStake = followTask(1L, new BigDecimal("20.00"));
        SmartWalletFollowTaskEntity higherEvLowerStake = followTask(2L, new BigDecimal("10.00"));
        SmartWalletTradeEventEntity event = tradeEvent();
        SmartWalletProfileEntity profile = profile();

        // loadPendingAndSkippedTasks 拆成 PENDING / SKIPPED 两段查询：首段返回任务，次段返回空，
        // 避免单桩对两段都返回同一列表造成结果翻倍。
        when(followTaskMapper.selectList(any()))
                .thenReturn(List.of(lowerEvHigherStake, higherEvLowerStake))
                .thenReturn(List.of());
        when(tradeEventMapper.selectById(anyLong())).thenReturn(event);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(1L))).thenReturn(acceptedDecision("1.50", "20.00", 12));
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(2L))).thenReturn(acceptedDecision("3.00", "10.00", 25));
        when(consensusSignalService.listRecentConsensusSignals()).thenReturn(List.of());
        when(timeFormatter.formatEpochMillisToBjt(any())).thenReturn("-");

        List<SmartWalletFollowTaskView> result = service.listExecutableTasks(10);

        assertEquals(List.of(2L, 1L), result.stream().map(SmartWalletFollowTaskView::getId).toList());
    }

    @Test
    void shouldReuseCachedRiskDecisionForExecutableTasks() {
        SmartWalletFollowTaskEntity task = followTask(1L, new BigDecimal("20.00"))
                .setUpdatedAt(new Date(123456789L));
        SmartWalletTradeEventEntity event = tradeEvent();
        SmartWalletProfileEntity profile = profile();

        when(followTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(tradeEventMapper.selectById(anyLong())).thenReturn(event);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(1L)))
                .thenReturn(acceptedDecision("1.50", "20.00", 12));
        when(consensusSignalService.listRecentConsensusSignals()).thenReturn(List.of());
        when(timeFormatter.formatEpochMillisToBjt(any())).thenReturn("-");

        List<SmartWalletFollowTaskView> first = service.listExecutableTasks(10, 1);
        List<SmartWalletFollowTaskView> second = service.listExecutableTasks(10, 1);

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        verify(signalRiskService, times(1)).evaluate(eq(profile), eq(event), isNull(), eq(1L));
    }

    @Test
    void shouldSortOpportunityTasksByLowerBlockersThenHigherEv() {
        SmartWalletFollowTaskEntity lowBlockerLowEv = followTask(11L, new BigDecimal("15.00"));
        SmartWalletFollowTaskEntity lowBlockerHighEv = followTask(12L, new BigDecimal("8.00"));
        SmartWalletTradeEventEntity event = tradeEvent();
        SmartWalletProfileEntity profile = profile();

        // loadPendingAndSkippedTasks 拆成 PENDING / SKIPPED 两段查询：首段返回任务，次段返回空。
        when(followTaskMapper.selectList(any()))
                .thenReturn(List.of(lowBlockerLowEv, lowBlockerHighEv))
                .thenReturn(List.of());
        // listOpportunityTasks 直接复用 selectList 返回的任务对象评估，不再按 id 二次查询；
        // 原 selectById(11L/12L) 桩已成多余（STRICT_STUBS 会报 UnnecessaryStubbing），移除。
        when(tradeEventMapper.selectById(anyLong())).thenReturn(event);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(11L))).thenReturn(blockedDecision("0.50", "15.00", List.of("Consensus score is below threshold."), 18));
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(12L))).thenReturn(blockedDecision("2.20", "8.00", List.of("Consensus wallet count is below threshold."), 30));
        when(consensusSignalService.listRecentConsensusSignals()).thenReturn(List.of());
        when(timeFormatter.formatEpochMillisToBjt(any())).thenReturn("-");

        List<SmartWalletFollowTaskView> result = service.listOpportunityTasks(10, 3, false);

        assertEquals(List.of(12L, 11L), result.stream().map(SmartWalletFollowTaskView::getId).toList());
    }

    @Test
    void shouldReevaluateDeferredCandidateAndRestoreBlockedTask() {
        SmartWalletProperties.Intelligence intelligence = new SmartWalletProperties.Intelligence();
        intelligence.setMinClusterConfidenceForDedup(new BigDecimal("0.40"));
        SmartWalletProfileEntity profile = profile();
        SmartWalletTradeEventEntity event = tradeEvent().setId(101L);
        WalletFollowCandidateEntity candidate = new WalletFollowCandidateEntity()
                .setId(301L)
                .setSourceEventId(101L)
                .setWalletAddress("0xabc")
                .setEntityId("0xabc")
                .setEnrichmentStatus("PENDING")
                .setRejectionReason("Entity resolution is pending. Follow task generation is deferred for this signal.")
                .setFollowTaskId(501L);
        SmartWalletFollowTaskEntity task = followTask(501L, BigDecimal.ZERO)
                .setId(501L)
                .setSourceEventId(101L)
                .setStatus("SKIPPED")
                .setErrorMessage("Entity resolution is pending. Follow task generation is deferred for this signal.");

        when(properties.getIntelligence()).thenReturn(intelligence);
        lenient().when(properties.getFollowDelaySeconds()).thenReturn(3);
        when(followCandidateMapper.selectList(any())).thenReturn(List.of(candidate));
        when(tradeEventMapper.selectById(101L)).thenReturn(event);
        when(followTaskMapper.selectById(501L)).thenReturn(task);
        when(profileStoreService.listWatchEnabled()).thenReturn(List.of(profile));
        when(entityResolutionService.resolveEntity("0xabc")).thenReturn(EntityResolutionResult.builder()
                .walletAddress("0xabc")
                .entityId("entity-1")
                .clusterMethod("BEHAVIOR_SIM")
                .confidenceScore(new BigDecimal("0.90"))
                .entityWalletCount(2)
                .resolvedFromMap(true)
                .pendingClustering(false)
                .build());
        when(entityResolutionService.getWalletsByEntityId("entity-1")).thenReturn(List.of("0xabc", "0xdef"));
        when(tradeEventMapper.selectOne(any())).thenReturn(null);
        when(consensusSignalService.buildConsensusForEvent(event)).thenReturn(ConsensusSignalView.builder()
                .dedupedEntityCount(2)
                .build());
        when(signalRiskService.evaluate(profile, event)).thenReturn(acceptedDecision("1.80", "12.34", 14));

        int updated = service.reevaluateDeferredCandidates(10);

        assertEquals(1, updated);
        ArgumentCaptor<WalletFollowCandidateEntity> candidateCaptor = ArgumentCaptor.forClass(WalletFollowCandidateEntity.class);
        verify(followCandidateMapper).updateById(candidateCaptor.capture());
        assertEquals("entity-1", candidateCaptor.getValue().getEntityId());
        assertEquals("ENRICHED", candidateCaptor.getValue().getEnrichmentStatus());
        assertEquals(2, candidateCaptor.getValue().getEntityConsensusDedupCount());

        ArgumentCaptor<SmartWalletFollowTaskEntity> taskCaptor = ArgumentCaptor.forClass(SmartWalletFollowTaskEntity.class);
        verify(followTaskMapper).updateById(taskCaptor.capture());
        assertEquals("PENDING", taskCaptor.getValue().getStatus());
        assertEquals("entity-1", taskCaptor.getValue().getEntityId());
        assertEquals(new BigDecimal("12.34").setScale(2), taskCaptor.getValue().getRecommendedStakeUsdc());
        assertEquals("Signal passed watchlist, freshness, size and price checks.", taskCaptor.getValue().getErrorMessage());
        assertNotNull(taskCaptor.getValue().getScheduledAt());
    }

    @Test
    void shouldReplayRecentIntelligenceCandidatesAndCountChangedEvents() {
        SmartWalletTradeEventEntity event = tradeEvent().setId(201L).setProcessed(Boolean.FALSE);
        SmartWalletProfileEntity profile = profile();
        AtomicReference<WalletFollowCandidateEntity> candidateRef = new AtomicReference<>();
        AtomicReference<SmartWalletFollowTaskEntity> taskRef = new AtomicReference<>();
        AtomicLong candidateIdSeq = new AtomicLong(701L);
        AtomicLong taskIdSeq = new AtomicLong(801L);

        lenient().when(properties.getFollowDelaySeconds()).thenReturn(3);
        lenient().when(properties.isAutoExecutionEnabled()).thenReturn(false);
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(event));
        when(tradeEventMapper.selectById(201L)).thenAnswer(invocation -> event);
        when(profileStoreService.listWatchEnabled()).thenReturn(List.of(profile));
        when(followCandidateMapper.selectOne(any())).thenAnswer(invocation -> candidateRef.get());
        when(followTaskMapper.selectOne(any())).thenAnswer(invocation -> taskRef.get());
        doAnswer(invocation -> {
            WalletFollowCandidateEntity inserted = invocation.getArgument(0);
            if (inserted.getId() == null) {
                inserted.setId(candidateIdSeq.getAndIncrement());
            }
            candidateRef.set(inserted);
            return 1;
        }).when(followCandidateMapper).insert(any());
        doAnswer(invocation -> {
            WalletFollowCandidateEntity updated = invocation.getArgument(0);
            candidateRef.set(updated);
            return 1;
        }).when(followCandidateMapper).updateById(any());
        doAnswer(invocation -> {
            SmartWalletFollowTaskEntity inserted = invocation.getArgument(0);
            if (inserted.getId() == null) {
                inserted.setId(taskIdSeq.getAndIncrement());
            }
            taskRef.set(inserted);
            return 1;
        }).when(followTaskMapper).insert(any());
        when(entityResolutionService.resolveEntity("0xabc")).thenReturn(EntityResolutionResult.selfEntity("0xabc"));
        when(consensusSignalService.buildConsensusForEvent(event)).thenReturn(ConsensusSignalView.builder()
                .dedupedEntityCount(1)
                .build());
        when(tradeEventMapper.selectOne(any())).thenReturn(null);
        // 修复六：createFollowTaskIfQualified 现以 5 参 evaluate 透传 marketState。
        when(signalRiskService.evaluate(eq(profile), eq(event), any(), any(), any()))
                .thenReturn(acceptedDecision("2.50", "9.00", 18));
        when(marketMetadataService.getByConditionId("market-1", false, "Will this market resolve yes?"))
                .thenReturn(PolymarketApiClient.MarketMetadata.builder().endAt(123456789L).build());

        SmartWalletFollowTaskService.IntelligenceReplaySummary summary =
                service.replayRecentIntelligenceCandidates(10, false);

        assertEquals(1, summary.getScanned());
        assertEquals(1, summary.getChanged());
        assertEquals(0, summary.getSkipped());
        assertEquals(Boolean.TRUE, event.getProcessed());
        assertNotNull(candidateRef.get());
        assertEquals("ENRICHED", candidateRef.get().getEnrichmentStatus());
        assertNotNull(taskRef.get());
        assertEquals("PENDING", taskRef.get().getStatus());
        assertEquals(candidateRef.get().getId(), taskRef.get().getFollowCandidateId());
        assertEquals(Long.valueOf(123456789L), taskRef.get().getMarketEndAt());
    }

    @Test
    void shouldRefreshObservedActivityWhenTradeEventAlreadyExists() {
        Date openedAt = new Date(1700000020000L);
        SmartWalletTradeEventEntity incoming = tradeEvent()
                .setId(null)
                .setSource("POLYMARKET")
                .setExternalTradeId("trade-existing")
                .setOpenedAt(new Date(1700000030000L));
        SmartWalletTradeEventEntity existing = tradeEvent()
                .setId(301L)
                .setSource("POLYMARKET")
                .setExternalTradeId("trade-existing")
                .setOpenedAt(openedAt);

        when(tradeEventMapper.selectOne(any())).thenReturn(existing);

        SmartWalletTradeEventEntity recorded = service.recordTradeEvent(incoming);

        assertEquals(existing, recorded);
        verify(profileStoreService).recordObservedTradeActivity(existing.getWalletAddress(), openedAt);
        verify(tradeEventMapper, org.mockito.Mockito.never()).insert(any());
        verify(followTaskMapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void shouldCreateSkippedTaskWhenMarketLifecycleIsClosed() {
        SmartWalletTradeEventEntity event = tradeEvent()
                .setId(null)
                .setSource("POLYMARKET")
                .setExternalTradeId("trade-closed")
                .setProcessed(Boolean.FALSE);
        SmartWalletProfileEntity profile = profile();
        AtomicReference<WalletFollowCandidateEntity> candidateRef = new AtomicReference<>();
        AtomicReference<SmartWalletFollowTaskEntity> taskRef = new AtomicReference<>();

        lenient().when(properties.getFollowDelaySeconds()).thenReturn(3);
        lenient().when(properties.isAutoExecutionEnabled()).thenReturn(false);
        when(tradeEventMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            SmartWalletTradeEventEntity inserted = invocation.getArgument(0);
            inserted.setId(1001L);
            return 1;
        }).when(tradeEventMapper).insert(any());
        when(profileStoreService.listWatchEnabled()).thenReturn(List.of(profile));
        // 修复三：市场检查前移后，死市场不再走 S3 候选增强；以下三个桩在本用例已不被命中，置 lenient。
        lenient().when(entityResolutionService.resolveEntity("0xabc")).thenReturn(EntityResolutionResult.selfEntity("0xabc"));
        lenient().when(consensusSignalService.buildConsensusForEvent(any(SmartWalletTradeEventEntity.class))).thenReturn(ConsensusSignalView.builder()
                .dedupedEntityCount(1)
                .build());
        lenient().doAnswer(invocation -> {
            WalletFollowCandidateEntity inserted = invocation.getArgument(0);
            inserted.setId(501L);
            candidateRef.set(inserted);
            return 1;
        }).when(followCandidateMapper).insert(any());
        doAnswer(invocation -> {
            SmartWalletFollowTaskEntity inserted = invocation.getArgument(0);
            inserted.setId(601L);
            taskRef.set(inserted);
            return 1;
        }).when(followTaskMapper).insert(any());
        when(marketInfoService.getOrCreate(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SmartWalletMarketInfoEntity()
                        .setId(901L)
                        .setMarketId("market-1")
                        .setQuestion("Will this market resolve yes?")
                        .setOutcome("YES"));
        when(marketInfoService.getById(901L)).thenReturn(new SmartWalletMarketInfoEntity()
                .setId(901L)
                .setMarketId("market-1")
                .setQuestion("Will this market resolve yes?")
                .setOutcome("YES"));
        when(marketStateService.refreshMarket(eq("market-1"), any(), any()))
                .thenReturn(MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED)
                        .acceptingOrders(false)
                        .closed(true)
                        .marketEndAt(System.currentTimeMillis() - 60_000L)
                        .build());

        SmartWalletTradeEventEntity recorded = service.recordTradeEvent(event);

        assertNotNull(recorded.getId());
        // 修复三：死市场在 S3 候选增强之前被前移拦截，candidate 不再被创建（这正是前移的目的）。
        assertNull(candidateRef.get());
        assertNotNull(taskRef.get());
        assertEquals("SKIPPED", taskRef.get().getStatus());
        assertTrue(taskRef.get().getErrorMessage().contains("[MARKET_CLOSED]")
                || taskRef.get().getErrorMessage().contains("[MARKET_NOT_ACCEPTING_ORDERS]"));
        // 修复二（6.4）：primary 仍为 NON_TRADABLE（兼容下游），细分原因写入 skip_reason_tags。
        assertEquals("NON_TRADABLE", taskRef.get().getPrimarySkipReason());
        assertTrue(taskRef.get().getSkipReasonTags().contains("NON_TRADABLE"));
        assertTrue(taskRef.get().getSkipReasonTags().contains("MARKET_NOT_ACCEPTING_ORDERS")
                || taskRef.get().getSkipReasonTags().contains("MARKET_CLOSED"));
        assertEquals(Boolean.TRUE, event.getProcessed());
    }

    @Test
    void shouldRouteSoftRejectedBuySignalToPaperShadowWithPrimaryReason() {
        SmartWalletTradeEventEntity event = tradeEvent()
                .setId(null)
                .setSource("POLYMARKET")
                .setExternalTradeId("trade-soft-rejected")
                .setProcessed(Boolean.FALSE);
        SmartWalletProfileEntity profile = profile();
        AtomicReference<SmartWalletFollowTaskEntity> taskRef = new AtomicReference<>();

        lenient().when(properties.getFollowDelaySeconds()).thenReturn(3);
        lenient().when(properties.isAutoExecutionEnabled()).thenReturn(false);
        when(tradeEventMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            SmartWalletTradeEventEntity inserted = invocation.getArgument(0);
            inserted.setId(1002L);
            return 1;
        }).when(tradeEventMapper).insert(any());
        when(profileStoreService.listWatchEnabled()).thenReturn(List.of(profile));
        when(entityResolutionService.resolveEntity("0xabc")).thenReturn(EntityResolutionResult.selfEntity("0xabc"));
        when(consensusSignalService.buildConsensusForEvent(any(SmartWalletTradeEventEntity.class))).thenReturn(ConsensusSignalView.builder()
                .dedupedEntityCount(1)
                .build());
        doAnswer(invocation -> {
            WalletFollowCandidateEntity inserted = invocation.getArgument(0);
            inserted.setId(502L);
            return 1;
        }).when(followCandidateMapper).insert(any());
        doAnswer(invocation -> {
            SmartWalletFollowTaskEntity inserted = invocation.getArgument(0);
            inserted.setId(602L);
            taskRef.set(inserted);
            return 1;
        }).when(followTaskMapper).insert(any());
        when(signalRiskService.evaluate(eq(profile), any(SmartWalletTradeEventEntity.class), any(), any(), any())).thenReturn(blockedDecision(
                "0.10",
                "1.00",
                List.of(
                        "Trigger price is outside follow range.",
                        // [ARCHETYPE] 是每个信号都带的信息性 reason、非阻断项；
                        // 归类 PROFILE_BEHAVIOR 需用真实行为阻断 reason [BEHAVIOR_BLOCK]。
                        "[BEHAVIOR_BLOCK] Wallet behavior profile blocks follow entry.",
                        "Open position count has reached the portfolio limit."
                ),
                5
        ));

        service.recordTradeEvent(event);

        assertNotNull(taskRef.get());
        assertEquals(SmartWalletFollowTaskService.STATUS_PAPER_SHADOW, taskRef.get().getStatus());
        assertEquals("PAPER_SHADOW", taskRef.get().getExecutionMode());
        assertEquals("PRICE", taskRef.get().getPrimarySkipReason());
        assertEquals("PRICE,PROFILE_BEHAVIOR,CAPACITY", taskRef.get().getSkipReasonTags());
        assertTrue(taskRef.get().getWouldHaveSkippedReason().contains("Trigger price is outside follow range."));
    }

    @Test
    void shouldCreateStaleSkippedTaskWhenEventOpenedAtExceedsIngestFreshnessLimit() {
        // openedAt 早于入口新鲜度上限（默认 10800s）的历史成交，应在 S3/S4 之前直接落 SKIPPED/STALE。
        long fiveHoursMs = 5L * 3600_000L;
        SmartWalletTradeEventEntity event = tradeEvent()
                .setId(null)
                .setSource("POLYMARKET")
                .setExternalTradeId("trade-stale-ingest")
                .setProcessed(Boolean.FALSE)
                .setOpenedAt(new Date(System.currentTimeMillis() - fiveHoursMs));
        SmartWalletProfileEntity profile = profile();
        AtomicReference<SmartWalletFollowTaskEntity> taskRef = new AtomicReference<>();

        lenient().when(properties.isAutoExecutionEnabled()).thenReturn(false);
        when(properties.getSignalRisk()).thenReturn(new SmartWalletProperties.SignalRisk());
        when(tradeEventMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            SmartWalletTradeEventEntity inserted = invocation.getArgument(0);
            inserted.setId(1003L);
            return 1;
        }).when(tradeEventMapper).insert(any());
        when(profileStoreService.listWatchEnabled()).thenReturn(List.of(profile));
        doAnswer(invocation -> {
            SmartWalletFollowTaskEntity inserted = invocation.getArgument(0);
            inserted.setId(603L);
            taskRef.set(inserted);
            return 1;
        }).when(followTaskMapper).insert(any());

        service.recordTradeEvent(event);

        assertNotNull(taskRef.get());
        assertEquals("SKIPPED", taskRef.get().getStatus());
        assertEquals("STALE", taskRef.get().getPrimarySkipReason());
        assertTrue(taskRef.get().getSkipReasonTags().contains("SIGNAL_STALE_INGEST"));
        assertTrue(taskRef.get().getErrorMessage().contains("[SIGNAL_STALE_INGEST]"));
        assertEquals(Boolean.TRUE, event.getProcessed());
        // 入口闸门在 S3 候选增强之前命中，实体解析不应被触发。
        verify(entityResolutionService, org.mockito.Mockito.never()).resolveEntity(any());
    }

    @Test
    void shouldApplyTighterIngestWindowForFastCategoryMarket() {
        // 修复一（6.2）：SPORTS 类别窗口默认 600s。30 分钟前的快市场信号超 600s 窗口 → STALE；
        // 该信号仍在 10800s 兜底窗口内——若按 OTHER 处理则不会被拦，故本用例证明分级窗口生效。
        long thirtyMinutesMs = 30L * 60_000L;
        SmartWalletTradeEventEntity event = tradeEvent()
                .setId(null)
                .setSource("POLYMARKET")
                .setExternalTradeId("trade-fast-category-ingest")
                .setQuestion("Will the Lakers win the NBA game tonight?")
                .setProcessed(Boolean.FALSE)
                .setOpenedAt(new Date(System.currentTimeMillis() - thirtyMinutesMs));
        SmartWalletProfileEntity profile = profile();
        AtomicReference<SmartWalletFollowTaskEntity> taskRef = new AtomicReference<>();

        lenient().when(properties.isAutoExecutionEnabled()).thenReturn(false);
        // new SignalRisk() 默认带分级 map（SPORTS=600）。
        when(properties.getSignalRisk()).thenReturn(new SmartWalletProperties.SignalRisk());
        when(tradeEventMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            SmartWalletTradeEventEntity inserted = invocation.getArgument(0);
            inserted.setId(1004L);
            return 1;
        }).when(tradeEventMapper).insert(any());
        when(profileStoreService.listWatchEnabled()).thenReturn(List.of(profile));
        doAnswer(invocation -> {
            SmartWalletFollowTaskEntity inserted = invocation.getArgument(0);
            inserted.setId(604L);
            taskRef.set(inserted);
            return 1;
        }).when(followTaskMapper).insert(any());

        service.recordTradeEvent(event);

        assertNotNull(taskRef.get());
        assertEquals("SKIPPED", taskRef.get().getStatus());
        assertEquals("STALE", taskRef.get().getPrimarySkipReason());
        assertTrue(taskRef.get().getSkipReasonTags().contains("SIGNAL_STALE_INGEST"));
        assertEquals(Boolean.TRUE, event.getProcessed());
    }

    @Test
    void shouldDrainStaleBacklogEventsAsSkippedStale() {
        // 修复七（6.7）：积压的 trade_event（openedAt 超分级窗口）应被批量落 SKIPPED/STALE 并标记 processed；
        // 仍在窗口内的事件留给正常链路、不被处理。
        long fourHoursMs = 4L * 3600_000L;
        SmartWalletTradeEventEntity staleEvent = tradeEvent()
                .setId(7001L)
                .setSource("POLYMARKET")
                .setExternalTradeId("backlog-stale")
                .setProcessed(Boolean.FALSE)
                .setOpenedAt(new Date(System.currentTimeMillis() - fourHoursMs));   // OTHER 窗口 3h → 超
        SmartWalletTradeEventEntity freshEvent = tradeEvent()
                .setId(7002L)
                .setSource("POLYMARKET")
                .setExternalTradeId("backlog-fresh")
                .setProcessed(Boolean.FALSE)
                .setOpenedAt(new Date(System.currentTimeMillis() - 60_000L));        // 1 分钟前 → 窗口内
        AtomicReference<SmartWalletFollowTaskEntity> taskRef = new AtomicReference<>();

        lenient().when(properties.isAutoExecutionEnabled()).thenReturn(false);
        when(properties.getSignalRisk()).thenReturn(new SmartWalletProperties.SignalRisk());
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(staleEvent, freshEvent));
        when(followTaskMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            SmartWalletFollowTaskEntity inserted = invocation.getArgument(0);
            inserted.setId(7100L);
            taskRef.set(inserted);
            return 1;
        }).when(followTaskMapper).insert(any());

        service.drainStaleSignalBacklog();

        // 仅 staleEvent 落 STALE 任务并标记 processed
        assertNotNull(taskRef.get());
        assertEquals("STALE", taskRef.get().getPrimarySkipReason());
        assertEquals(Long.valueOf(7001L), taskRef.get().getSourceEventId());
        assertEquals(Boolean.TRUE, staleEvent.getProcessed());
        // freshEvent 留给正常链路，不被批量排空触碰
        assertEquals(Boolean.FALSE, freshEvent.getProcessed());
        verify(followTaskMapper, times(1)).insert(any());
    }

    @Test
    void shouldSkipSellFollowExitWhenFollowerHasNoOpenPosition() {
        SmartWalletFollowTaskEntity task = followTask(2597L, new BigDecimal("37.39"))
                .setSide("SELL")
                .setExecutionMode("PAPER")
                .setStatus("PENDING");
        SmartWalletTradeEventEntity event = tradeEvent()
                .setId(task.getSourceEventId())
                .setSide("SELL")
                .setSizeUsdc(new BigDecimal("5964.24400000"));
        SmartWalletProfileEntity profile = profile();
        SignalRiskDecision decision = SignalRiskDecision.builder()
                .accepted(true)
                .entryLane("DYNAMIC_FOLLOW_EXIT")
                .exitIntent("SOURCE_SELL")
                .recommendedStakeUsdc(new BigDecimal("37.39"))
                .reasons(List.of("[MARKET_TYPE] marketType=FAST_EVENT, entryLane=DYNAMIC_FOLLOW_EXIT, exitIntent=SOURCE_SELL."))
                .build();

        when(followTaskMapper.selectById(2597L)).thenReturn(task);
        when(tradeEventMapper.selectById(task.getSourceEventId())).thenReturn(event);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(2597L))).thenReturn(decision);
        when(followPositionAdjustmentService.adjust(any(), eq(event), eq("SOURCE_SELL"), eq(ExecutionMode.PAPER)))
                .thenReturn(FollowPositionAdjustmentService.AdjustmentResult.builder()
                        .action(FollowPositionAdjustmentService.AdjustmentAction.NO_POSITION_SKIP)
                        .success(true)
                        .message("Follower has no open position for this market+outcome. Safe skip.")
                        .exitedQuantity(BigDecimal.ZERO)
                        .exitedNotionalUsdc(BigDecimal.ZERO)
                        .build());

        SmartWalletExecutionResult result = service.executeTaskNow(2597L, ExecutionMode.PAPER);

        assertFalse(result.isSuccess());
        assertEquals("SKIPPED", result.getStatus());
        assertTrue(result.getMessage().contains("NO_POSITION_TO_EXIT"));
        ArgumentCaptor<SmartWalletFollowTaskEntity> taskCaptor = ArgumentCaptor.forClass(SmartWalletFollowTaskEntity.class);
        verify(followTaskMapper).updateById(taskCaptor.capture());
        assertEquals("SKIPPED", taskCaptor.getValue().getStatus());
        assertEquals("NO_POSITION_TO_EXIT", taskCaptor.getValue().getPrimarySkipReason());
        assertTrue(taskCaptor.getValue().getSkipReasonTags().contains("DYNAMIC_FOLLOW_EXIT"));
        verify(executionGateway, org.mockito.Mockito.never()).execute(any(), any());
        verify(accountingService, org.mockito.Mockito.never()).recordExecution(any(), any());
    }

    private SmartWalletFollowTaskEntity followTask(Long id, BigDecimal stake) {
        return new SmartWalletFollowTaskEntity()
                .setId(id)
                .setSourceEventId(1000L + id)
                .setWalletAddress("0xabc")
                .setMarketId("market-1")
                .setQuestion("Will this market resolve yes?")
                .setOutcome("YES")
                .setMarketEndAt(System.currentTimeMillis() + 3600_000L)
                .setSide("BUY")
                .setTriggerPrice(new BigDecimal("0.61"))
                .setTriggerSizeUsdc(new BigDecimal("100.00"))
                .setRecommendedStakeUsdc(stake)
                .setStatus("PENDING")
                .setScheduledAt(new Date());
    }

    private SmartWalletTradeEventEntity tradeEvent() {
        return new SmartWalletTradeEventEntity()
                .setId(1L)
                .setWalletAddress("0xabc")
                .setMarketId("market-1")
                .setQuestion("Will this market resolve yes?")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.61"))
                .setSizeUsdc(new BigDecimal("100.00"))
                .setOpenedAt(new Date());
    }

    private SmartWalletProfileEntity profile() {
        return new SmartWalletProfileEntity()
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE);
    }

    private SignalRiskDecision acceptedDecision(String ev, String stake, Integer sampleSize) {
        return SignalRiskDecision.builder()
                .accepted(true)
                .recommendedStakeUsdc(new BigDecimal(stake))
                .estimatedNetProfitabilityUsdc(new BigDecimal(ev))
                .estimateSampleSize(sampleSize)
                .reasons(List.of("Signal passed watchlist, freshness, size and price checks."))
                .build();
    }

    private SignalRiskDecision blockedDecision(String ev, String stake, List<String> reasons, Integer sampleSize) {
        return SignalRiskDecision.builder()
                .accepted(false)
                .recommendedStakeUsdc(new BigDecimal(stake))
                .estimatedNetProfitabilityUsdc(new BigDecimal(ev))
                .estimateSampleSize(sampleSize)
                .reasons(reasons)
                .build();
    }
}
