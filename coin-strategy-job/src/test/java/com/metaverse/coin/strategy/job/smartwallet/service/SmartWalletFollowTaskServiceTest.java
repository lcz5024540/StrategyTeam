package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.MarketStateSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;
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
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        when(followTaskMapper.selectList(any())).thenReturn(List.of(lowerEvHigherStake, higherEvLowerStake));
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

        when(followTaskMapper.selectList(any())).thenReturn(List.of(lowBlockerLowEv, lowBlockerHighEv));
        when(followTaskMapper.selectById(11L)).thenReturn(lowBlockerLowEv);
        when(followTaskMapper.selectById(12L)).thenReturn(lowBlockerHighEv);
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
        when(signalRiskService.evaluate(profile, event)).thenReturn(acceptedDecision("2.50", "9.00", 18));
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
        when(entityResolutionService.resolveEntity("0xabc")).thenReturn(EntityResolutionResult.selfEntity("0xabc"));
        when(consensusSignalService.buildConsensusForEvent(any(SmartWalletTradeEventEntity.class))).thenReturn(ConsensusSignalView.builder()
                .dedupedEntityCount(1)
                .build());
        doAnswer(invocation -> {
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
        assertNotNull(candidateRef.get());
        assertNotNull(taskRef.get());
        assertEquals("SKIPPED", taskRef.get().getStatus());
        assertTrue(taskRef.get().getErrorMessage().contains("[MARKET_CLOSED]")
                || taskRef.get().getErrorMessage().contains("[MARKET_NOT_ACCEPTING_ORDERS]"));
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
        when(signalRiskService.evaluate(eq(profile), any(SmartWalletTradeEventEntity.class))).thenReturn(blockedDecision(
                "0.10",
                "1.00",
                List.of(
                        "Trigger price is outside follow range.",
                        "[ARCHETYPE] archetype=HF_SCALPER, lane=HF_FOLLOW.",
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
