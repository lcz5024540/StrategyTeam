package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.MarketStateSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.model.ConcentrationSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.MarketConcentrationSnapshotService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletMarketMonitoringSummaryView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.OffsetPageView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletMarketMonitoringServiceTest {

    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private SmartWalletMarketStateService marketStateService;
    @Mock
    private MarketConcentrationSnapshotService concentrationSnapshotService;
    @Mock
    private MarketSnapshotCollectorService marketSnapshotCollectorService;

    private SmartWalletMarketMonitoringService service;
    private SmartWalletProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        service = new SmartWalletMarketMonitoringService(
                tradeEventMapper,
                followTaskMapper,
                positionMapper,
                properties,
                marketInfoService,
                marketStateService,
                concentrationSnapshotService,
                marketSnapshotCollectorService
        );
        when(followTaskMapper.selectList(any())).thenReturn(List.of());
        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(marketInfoService.batchGetByIds(any())).thenReturn(Map.of());
        when(marketInfoService.batchGetPrimaryByMarketIds(any())).thenReturn(Map.of());
        lenient().when(concentrationSnapshotService.batchGetLatestSnapshots(any(), isNull())).thenReturn(Map.of());
    }

    @Test
    void shouldOnlyReturnActiveMarketsInSummary() {
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(
                event("market-active", "Will active market stay visible?"),
                event("market-closed", "Will closed market disappear?")
        ));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of(
                "market-active", MarketStateSnapshot.builder()
                        .marketId("market-active")
                        .question("Will active market stay visible?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_POLITICS)
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .build(),
                "market-closed", MarketStateSnapshot.builder()
                        .marketId("market-closed")
                        .question("Will closed market disappear?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED)
                        .acceptingOrders(false)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .marketEndAt(System.currentTimeMillis() - 60_000L)
                        .build()
        ));
        when(concentrationSnapshotService.batchGetLatestSnapshots(any(), isNull())).thenReturn(Map.of(
                "market-active",
                new MarketConcentrationSnapshotService.SnapshotView(
                        new ConcentrationSnapshot(
                                "market-active",
                                "YES",
                                25,
                                new BigDecimal("0.10"),
                                new BigDecimal("0.18"),
                                new BigDecimal("0.25"),
                                900,
                                false,
                                false,
                                List.of("0x1"),
                                System.currentTimeMillis()
                        ),
                        new Date(),
                        false
                )
        ));

        SmartWalletMarketMonitoringSummaryView summary = service.summary(20, 14, 7);

        assertEquals(1, summary.getItems().size());
        assertEquals("market-active", summary.getItems().get(0).getMarketId());
        assertEquals(1, summary.getMonitoredMarketCount());
        verify(marketStateService).loadPersistedMarkets(any());
        verify(marketStateService, never()).refreshMarkets(any(), anyBoolean());
    }

    @Test
    void shouldFilterTableByNormalizedCategory() {
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(
                event("market-politics", "Will election result change?"),
                event("market-weather", "Will it rain tomorrow?")
        ));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of(
                "market-politics", MarketStateSnapshot.builder()
                        .marketId("market-politics")
                        .question("Will election result change?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_POLITICS)
                        .sourceCategory("Politics")
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .build(),
                "market-weather", MarketStateSnapshot.builder()
                        .marketId("market-weather")
                        .question("Will it rain tomorrow?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_WEATHER)
                        .sourceCategory("Weather")
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .build()
        ));

        OffsetPageView<com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletMarketMonitorItemView> page =
                service.tablePage(1, 10, 14, null, "POLITICS", null, null, null);

        assertEquals(1, page.items().size());
        assertEquals("market-politics", page.items().get(0).getMarketId());
        assertTrue(page.items().get(0).getNormalizedCategory().contains("POLITICS"));
        verify(marketStateService).loadPersistedMarkets(any());
        verify(marketStateService, never()).refreshMarkets(any(), anyBoolean());
    }

    @Test
    void shouldSurfacePersistedStaleFlagsWithoutRefreshingRemotely() {
        Date staleMetadata = new Date(System.currentTimeMillis() - 1_800_000L);
        Date staleConcentration = new Date(System.currentTimeMillis() - 1_200_000L);
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(
                event("market-stale", "Will stale data still render?")
        ));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of(
                "market-stale", MarketStateSnapshot.builder()
                        .marketId("market-stale")
                        .question("Will stale data still render?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .lastMetadataSyncAt(staleMetadata)
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .build()
        ));
        when(concentrationSnapshotService.batchGetLatestSnapshots(any(), isNull())).thenReturn(Map.of(
                "market-stale",
                new MarketConcentrationSnapshotService.SnapshotView(
                        new ConcentrationSnapshot(
                                "market-stale",
                                "ALL",
                                12,
                                new BigDecimal("0.12"),
                                new BigDecimal("0.22"),
                                new BigDecimal("0.30"),
                                1100,
                                false,
                                false,
                                List.of(),
                                staleConcentration.getTime()
                        ),
                        staleConcentration,
                        true
                )
        ));

        SmartWalletMarketMonitoringSummaryView summary = service.summary(20, 14, 7);

        assertEquals(1, summary.getItems().size());
        assertTrue(Boolean.TRUE.equals(summary.getItems().get(0).getMetadataStale()));
        assertTrue(Boolean.TRUE.equals(summary.getItems().get(0).getConcentrationStale()));
        assertEquals(staleMetadata, summary.getItems().get(0).getLastMetadataSyncAt());
        assertEquals(staleConcentration, summary.getItems().get(0).getLastConcentrationSnapshotAt());
        verify(marketStateService, never()).refreshMarkets(any(), anyBoolean());
    }

    @Test
    void shouldHideMarketsWhosePersistedSnapshotIsNoLongerActive() {
        // 契约变更：监控视图按 lifecycle/closed/acceptingOrders 判活跃，不再按 marketEndAt 过期
        // （shouldDisplayActiveMarket 的 marketEndAt 参数已弃用——Polymarket 长尾市场 endAt 常陈旧）。
        // 故"应隐藏"的市场以非活跃 lifecycle 表达，而非过去式 endAt。
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(
                event("market-expired", "Will a non-active market disappear?")
        ));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of(
                "market-expired", MarketStateSnapshot.builder()
                        .marketId("market-expired")
                        .question("Will a non-active market disappear?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED)
                        .acceptingOrders(false)
                        .closed(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .marketEndAt(System.currentTimeMillis() - 60_000L)
                        .build()
        ));

        SmartWalletMarketMonitoringSummaryView summary = service.summary(20, 14, 7);

        assertEquals(0, summary.getItems().size());
        assertEquals(0, summary.getMonitoredMarketCount());
        assertEquals(0, summary.getActiveMarketCount());
        verify(marketStateService).loadPersistedMarkets(any());
        verify(marketStateService, never()).refreshMarkets(any(), anyBoolean());
    }

    @Test
    void shouldExcludeExpiredMarketsFromRefreshCandidates() {
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(
                event("market-live", "Will live market stay monitored?"),
                event("market-expired", "Will expired market be dropped from candidates?")
        ));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of(
                "market-live", MarketStateSnapshot.builder()
                        .marketId("market-live")
                        .question("Will live market stay monitored?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .build(),
                // 契约变更：候选过滤按 lifecycle 判活跃，不再按 marketEndAt；
                // "应排除"的市场以非活跃 lifecycle 表达。
                "market-expired", MarketStateSnapshot.builder()
                        .marketId("market-expired")
                        .question("Will a non-active market be dropped from candidates?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED)
                        .acceptingOrders(false)
                        .closed(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .marketEndAt(System.currentTimeMillis() - 60_000L)
                        .build()
        ));

        List<String> candidates = service.listCandidateMarketIds(20, 14);

        assertEquals(List.of("market-live"), candidates);
        verify(marketStateService).loadPersistedMarkets(any());
        verify(marketStateService, never()).refreshMarkets(any(), anyBoolean());
    }

    @Test
    void shouldIncludeFreshPendingTaskAsRefreshCandidate() {
        SmartWalletMarketInfoEntity info = marketInfo(1L, "market-pending", System.currentTimeMillis() + 3_600_000L);
        when(tradeEventMapper.selectList(any())).thenReturn(List.of());
        when(followTaskMapper.selectList(any())).thenReturn(List.of(task(1L, "PENDING", secondsAgo(60))));
        when(marketInfoService.batchGetByIds(any())).thenReturn(Map.of(1L, info));
        when(marketInfoService.batchGetPrimaryByMarketIds(any())).thenReturn(Map.of("market-pending", info));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of("market-pending", activeSnapshot("market-pending")));

        List<String> candidates = service.listCandidateMarketIds(20, 14);

        assertEquals(List.of("market-pending"), candidates);
    }

    @Test
    void shouldKeepMonitoringTaskWindowIndependentFromExecutionSignalAge() {
        properties.getSignalRisk().setMaxSignalAgeSeconds(3600);
        properties.getMarketMonitoring().setActiveTaskMaxAgeSeconds(10800);
        SmartWalletMarketInfoEntity info = marketInfo(1L, "market-delayed-task", System.currentTimeMillis() + 3_600_000L);
        when(tradeEventMapper.selectList(any())).thenReturn(List.of());
        when(followTaskMapper.selectList(any())).thenReturn(List.of(task(1L, "PENDING", secondsAgo(7_200))));
        when(marketInfoService.batchGetByIds(any())).thenReturn(Map.of(1L, info));
        when(marketInfoService.batchGetPrimaryByMarketIds(any())).thenReturn(Map.of("market-delayed-task", info));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of("market-delayed-task", activeSnapshot("market-delayed-task")));

        List<String> candidates = service.listCandidateMarketIds(20, 14);

        assertEquals(List.of("market-delayed-task"), candidates);
    }

    @Test
    void shouldExcludePendingTaskOlderThanMarketMonitoringMaxAge() {
        SmartWalletMarketInfoEntity info = marketInfo(1L, "market-stale-task", System.currentTimeMillis() + 3_600_000L);
        when(tradeEventMapper.selectList(any())).thenReturn(List.of());
        when(followTaskMapper.selectList(any())).thenReturn(List.of(task(1L, "PENDING", secondsAgo(14_400))));
        when(marketInfoService.batchGetByIds(any())).thenReturn(Map.of(1L, info));
        when(marketInfoService.batchGetPrimaryByMarketIds(any())).thenReturn(Map.of("market-stale-task", info));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of("market-stale-task", activeSnapshot("market-stale-task")));

        List<String> candidates = service.listCandidateMarketIds(20, 14);

        assertEquals(List.of(), candidates);
    }

    @Test
    void shouldIncludeFreshExecutingTaskAsRefreshCandidate() {
        SmartWalletMarketInfoEntity info = marketInfo(1L, "market-executing", System.currentTimeMillis() + 3_600_000L);
        when(tradeEventMapper.selectList(any())).thenReturn(List.of());
        when(followTaskMapper.selectList(any())).thenReturn(List.of(task(1L, "EXECUTING", secondsAgo(60))));
        when(marketInfoService.batchGetByIds(any())).thenReturn(Map.of(1L, info));
        when(marketInfoService.batchGetPrimaryByMarketIds(any())).thenReturn(Map.of("market-executing", info));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of("market-executing", activeSnapshot("market-executing")));

        List<String> candidates = service.listCandidateMarketIds(20, 14);

        assertEquals(List.of("market-executing"), candidates);
    }

    @Test
    void shouldExcludeHistoricalTaskStatusesFromRefreshCandidates() {
        SmartWalletMarketInfoEntity skipped = marketInfo(1L, "market-skipped", System.currentTimeMillis() + 3_600_000L);
        SmartWalletMarketInfoEntity failed = marketInfo(2L, "market-failed", System.currentTimeMillis() + 3_600_000L);
        SmartWalletMarketInfoEntity followed = marketInfo(3L, "market-followed", System.currentTimeMillis() + 3_600_000L);
        when(tradeEventMapper.selectList(any())).thenReturn(List.of());
        when(followTaskMapper.selectList(any())).thenReturn(List.of(
                task(1L, "SKIPPED", secondsAgo(60)),
                task(2L, "FAILED", secondsAgo(60)),
                task(3L, "FOLLOWED", secondsAgo(60))
        ));
        when(marketInfoService.batchGetByIds(any())).thenReturn(Map.of(
                1L, skipped,
                2L, failed,
                3L, followed
        ));
        when(marketInfoService.batchGetPrimaryByMarketIds(any())).thenReturn(Map.of(
                "market-skipped", skipped,
                "market-failed", failed,
                "market-followed", followed
        ));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of(
                "market-skipped", activeSnapshot("market-skipped"),
                "market-failed", activeSnapshot("market-failed"),
                "market-followed", activeSnapshot("market-followed")
        ));

        List<String> candidates = service.listCandidateMarketIds(20, 14);

        assertEquals(List.of(), candidates);
    }

    @Test
    void shouldIncludeOpenPositionMarketWithoutActiveTasks() {
        SmartWalletMarketInfoEntity info = marketInfo(1L, "market-open-position", System.currentTimeMillis() + 3_600_000L);
        when(tradeEventMapper.selectList(any())).thenReturn(List.of());
        when(positionMapper.selectList(any())).thenReturn(List.of(position(1L, "OPEN")));
        when(marketInfoService.batchGetByIds(any())).thenReturn(Map.of(1L, info));
        when(marketInfoService.batchGetPrimaryByMarketIds(any())).thenReturn(Map.of("market-open-position", info));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of("market-open-position", activeSnapshot("market-open-position")));

        List<String> candidates = service.listCandidateMarketIds(20, 14);

        assertEquals(List.of("market-open-position"), candidates);
    }

    @Test
    void shouldKeepExcludingClosedResolvedAndEndedMarkets() {
        SmartWalletMarketInfoEntity closed = marketInfo(1L, "market-closed-task", System.currentTimeMillis() + 3_600_000L)
                .setClosed(true);
        SmartWalletMarketInfoEntity resolved = marketInfo(2L, "market-resolved-task", System.currentTimeMillis() + 3_600_000L)
                .setResolvedOutcome("YES");
        SmartWalletMarketInfoEntity ended = marketInfo(3L, "market-ended-task", System.currentTimeMillis() - 60_000L);
        when(tradeEventMapper.selectList(any())).thenReturn(List.of());
        when(followTaskMapper.selectList(any())).thenReturn(List.of(
                task(1L, "PENDING", secondsAgo(60)),
                task(2L, "PENDING", secondsAgo(60)),
                task(3L, "PENDING", secondsAgo(60))
        ));
        when(marketInfoService.batchGetByIds(any())).thenReturn(Map.of(
                1L, closed,
                2L, resolved,
                3L, ended
        ));
        when(marketInfoService.batchGetPrimaryByMarketIds(any())).thenReturn(Map.of(
                "market-closed-task", closed,
                "market-resolved-task", resolved,
                "market-ended-task", ended
        ));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of(
                "market-closed-task", activeSnapshot("market-closed-task"),
                "market-resolved-task", activeSnapshot("market-resolved-task"),
                // 契约变更：仅 endAt 过去已不再排除；"ended" 市场以非活跃 lifecycle 表达
                // （真实链路中已结束的市场最终会被元数据同步标记为 CLOSED）。
                "market-ended-task", MarketStateSnapshot.builder()
                        .marketId("market-ended-task")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED)
                        .acceptingOrders(false)
                        .closed(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .build()
        ));

        List<String> candidates = service.listCandidateMarketIds(20, 14);

        assertEquals(List.of(), candidates);
    }

    private SmartWalletTradeEventEntity event(String marketId, String question) {
        return new SmartWalletTradeEventEntity()
                .setSource("POLYMARKET")
                .setWalletAddress("0xabc")
                .setMarketId(marketId)
                .setQuestion(question)
                .setOutcome("YES")
                .setOpenedAt(new Date());
    }

    private SmartWalletFollowTaskEntity task(Long marketInfoId, String status, Date createdAt) {
        return new SmartWalletFollowTaskEntity()
                .setMarketInfoId(marketInfoId)
                .setStatus(status)
                .setCreatedAt(createdAt);
    }

    private SmartWalletPositionEntity position(Long marketInfoId, String status) {
        return new SmartWalletPositionEntity()
                .setMarketInfoId(marketInfoId)
                .setStatus(status)
                .setOutcome("YES")
                .setUpdatedAt(new Date());
    }

    private SmartWalletMarketInfoEntity marketInfo(Long id, String marketId, Long marketEndAt) {
        return new SmartWalletMarketInfoEntity()
                .setId(id)
                .setMarketId(marketId)
                .setQuestion("Question for " + marketId)
                .setOutcome("YES")
                .setMarketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                .setAcceptingOrders(true)
                .setClosed(false)
                .setMarketEndAt(marketEndAt);
    }

    private MarketStateSnapshot activeSnapshot(String marketId) {
        return MarketStateSnapshot.builder()
                .marketId(marketId)
                .question("Question for " + marketId)
                .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                .acceptingOrders(true)
                .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                .build();
    }

    private Date secondsAgo(long seconds) {
        return new Date(System.currentTimeMillis() - seconds * 1000L);
    }
}
