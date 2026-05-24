package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletScoringService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualBlocklistService;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolymarketWalletDiscoveryServiceTest {

    @Mock
    private PolymarketApiClient polymarketApiClient;
    @Mock
    private PolymarketSmartWalletMonitorService monitorService;
    @Mock
    private SmartWalletSelectionService selectionService;
    @Mock
    private SmartWalletProfileStoreService profileStoreService;
    @Mock
    private SmartWalletLeaderboardStateService leaderboardStateService;
    @Mock
    private WalletArchetypeClassifierService walletArchetypeClassifierService;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;
    @Mock
    private WalletOrderAggregationService walletOrderAggregationService;
    @Mock
    private LeaderTradeOutcomeRebuildService leaderTradeOutcomeRebuildService;
    @Mock
    private SmartWalletProfileTruthRefreshService profileTruthRefreshService;
    @Mock
    private SmartWalletManualAllowlistService manualAllowlistService;
    @Mock
    private SmartWalletManualBlocklistService manualBlocklistService;

    @Test
    void shouldDeduplicateAcrossLeaderboardQueriesAndTrackHitCount() {
        SmartWalletProperties properties = new SmartWalletProperties();
        SmartWalletProperties.PolymarketMonitor.LeaderboardQuery pnl = new SmartWalletProperties.PolymarketMonitor.LeaderboardQuery();
        pnl.setCategory("OVERALL");
        pnl.setTimePeriod("ALL");
        pnl.setOrderBy("PNL");
        pnl.setLimit(50);
        SmartWalletProperties.PolymarketMonitor.LeaderboardQuery volume = new SmartWalletProperties.PolymarketMonitor.LeaderboardQuery();
        volume.setCategory("OVERALL");
        volume.setTimePeriod("ALL");
        volume.setOrderBy("VOLUME");
        volume.setLimit(50);
        properties.getPolymarket().setLeaderboardQueries(List.of(pnl, volume));
        properties.getPolymarket().setLeaderboardRefreshTopN(10);
        properties.getPolymarket().setDiscoveryProfileTradeLimit(10);

        when(polymarketApiClient.fetchLeaderboard(any())).thenReturn(
                List.of(
                        PolymarketApiClient.LeaderboardEntry.builder().walletAddress("0xaaa").alias("A").build(),
                        PolymarketApiClient.LeaderboardEntry.builder().walletAddress("0xbbb").alias("B").build()
                ),
                List.of(
                        PolymarketApiClient.LeaderboardEntry.builder().walletAddress("0xaaa").alias("A2").build()
                )
        );
        lenient().when(walletOrderAggregationService.snapshot("0xaaa")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xaaa")
                .closedOrders(List.of(
                        WalletOrderAggregationService.ClosedOrderSnapshot.builder()
                                .walletAddress("0xaaa")
                                .investedUsdc(new BigDecimal("10"))
                                .realizedPnlUsdc(new BigDecimal("4"))
                                .settlementAt(new java.util.Date())
                                .build()
                ))
                .build());
        lenient().when(walletOrderAggregationService.snapshot("0xbbb")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xbbb")
                .closedOrders(List.of(
                        WalletOrderAggregationService.ClosedOrderSnapshot.builder()
                                .walletAddress("0xbbb")
                                .investedUsdc(new BigDecimal("10"))
                                .realizedPnlUsdc(new BigDecimal("2"))
                                .settlementAt(new java.util.Date())
                                .build()
                ))
                .build());
        when(polymarketApiClient.fetchUserTrades(any(), any())).thenReturn(Collections.emptyList());
        SmartWalletProfileEntity existingHot = new SmartWalletProfileEntity()
                .setWalletAddress("0xexisting")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE)
                .setMonitorTier("HOT");
        when(profileStoreService.listQualified()).thenReturn(List.of(existingHot));
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(existingHot));
        when(profileStoreService.listAll()).thenReturn(List.of(existingHot));
        when(profileTruthRefreshService.buildTruthMetrics(any(), any(), any()))
                .thenAnswer(invocation -> truthMetrics(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));

        PolymarketWalletDiscoveryService service = newService(properties);

        PolymarketDiscoverySummary summary = service.refreshLeaderboardWatchlist();

        assertEquals(2, summary.getCandidateWallets());
        ArgumentCaptor<PolymarketWalletMetricsRequest> captor = ArgumentCaptor.forClass(PolymarketWalletMetricsRequest.class);
        verify(monitorService, org.mockito.Mockito.times(2)).ingestWalletMetrics(captor.capture());
        PolymarketWalletMetricsRequest first = captor.getAllValues().stream()
                .filter(item -> "0xaaa".equals(item.getWalletAddress()))
                .findFirst()
                .orElseThrow();
        assertEquals(Integer.valueOf(2), first.getLeaderboardHitCount30d());
        assertEquals("OVERALL|ALL|VOLUME|10", first.getLastDiscoveryQuery());
        verify(selectionService, org.mockito.Mockito.never()).refreshQualifiedWallets();
    }

    @Test
    void shouldNormalizeTradeMarketIdToCanonicalConditionIdBeforeIngest() {
        SmartWalletProperties properties = new SmartWalletProperties();
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xwallet")
                .setChain("polygon")
                .setMonitorTier("HOT");
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(wallet));
        when(polymarketApiClient.fetchUserTrades("0xwallet")).thenReturn(List.of(
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-1")
                        .marketId("market-legacy")
                        .question("76ers vs. Celtics: O/U 217.5")
                        .outcome("OVER")
                        .side("BUY")
                        .price(new BigDecimal("0.46"))
                        .size(new BigDecimal("5.53"))
                        .tradedAt(System.currentTimeMillis())
                        .build()
        ));
        when(marketMetadataService.resolveCanonicalConditionId(
                "market-legacy",
                "76ers vs. Celtics: O/U 217.5",
                List.of("OVER")
        )).thenReturn("0xcondition");

        PolymarketWalletDiscoveryService service = newService(properties);

        service.syncWatchlistTrades();

        ArgumentCaptor<PolymarketTradeEventRequest> captor = ArgumentCaptor.forClass(PolymarketTradeEventRequest.class);
        verify(monitorService).ingestTradeEvent(captor.capture());
        assertEquals("0xcondition", captor.getValue().getMarketId());
    }

    @Test
    void shouldImportHotWalletRecentTradesChronologicallyAfterTimestampDescFetch() {
        SmartWalletProperties properties = new SmartWalletProperties();
        long checkpointMillis = System.currentTimeMillis() - 60_000L;
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xhot")
                .setChain("polygon")
                .setMonitorTier("HOT")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE)
                .setLastEventSyncAt(new java.util.Date(checkpointMillis));

        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(wallet));
        when(polymarketApiClient.fetchUserTrades("0xhot")).thenReturn(List.of(
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-2")
                        .walletAddress("0xhot")
                        .marketId("market-2")
                        .question("Q2")
                        .outcome("YES")
                        .side("BUY")
                        .price(new BigDecimal("0.51"))
                        .size(new BigDecimal("8"))
                        .tradedAt(checkpointMillis + 2_000L)
                        .build(),
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-1")
                        .walletAddress("0xhot")
                        .marketId("market-1")
                        .question("Q1")
                        .outcome("YES")
                        .side("BUY")
                        .price(new BigDecimal("0.49"))
                        .size(new BigDecimal("6"))
                        .tradedAt(checkpointMillis + 1_000L)
                        .build()
        ));
        when(marketMetadataService.resolveCanonicalConditionId(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PolymarketWalletDiscoveryService service = newService(properties);

        long beforeSync = System.currentTimeMillis();
        int imported = service.syncWatchlistTrades();

        assertEquals(2, imported);
        ArgumentCaptor<PolymarketTradeEventRequest> eventCaptor = ArgumentCaptor.forClass(PolymarketTradeEventRequest.class);
        verify(monitorService, org.mockito.Mockito.times(2)).ingestTradeEvent(eventCaptor.capture());
        assertEquals(List.of("trade-1", "trade-2"),
                eventCaptor.getAllValues().stream().map(PolymarketTradeEventRequest::getExternalTradeId).toList());
        ArgumentCaptor<java.util.Date> checkpointCaptor = ArgumentCaptor.forClass(java.util.Date.class);
        verify(profileStoreService).recordHotEventSync(eq(wallet), checkpointCaptor.capture());
        assertTrue(checkpointCaptor.getValue().getTime() >= checkpointMillis + 2_000L);
        assertTrue(checkpointCaptor.getValue().getTime() >= beforeSync);
    }

    @Test
    void shouldSyncManualAllowlistRuntimeHotProfileTrades() {
        SmartWalletProperties properties = new SmartWalletProperties();
        SmartWalletProfileEntity manualRuntimeProfile = new SmartWalletProfileEntity()
                .setWalletAddress("0xmanual")
                .setChain("polygon")
                .setMonitorTier("HOT")
                .setWatchEnabled(Boolean.TRUE);
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(manualRuntimeProfile));
        when(polymarketApiClient.fetchUserTrades("0xmanual")).thenReturn(List.of(
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("manual-trade-1")
                        .walletAddress("0xmanual")
                        .marketId("market-manual")
                        .question("Manual allowlist trade?")
                        .outcome("YES")
                        .side("BUY")
                        .price(new BigDecimal("0.57"))
                        .sizeShares(new BigDecimal("12"))
                        .tradedAt(System.currentTimeMillis())
                        .build()
        ));
        when(marketMetadataService.resolveCanonicalConditionId(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PolymarketWalletDiscoveryService service = newService(properties);

        int imported = service.syncWatchlistTradesBatch();

        assertEquals(1, imported);
        ArgumentCaptor<PolymarketTradeEventRequest> captor = ArgumentCaptor.forClass(PolymarketTradeEventRequest.class);
        verify(monitorService).ingestTradeEvent(captor.capture());
        assertEquals("0xmanual", captor.getValue().getWalletAddress());
        assertEquals("manual-trade-1", captor.getValue().getExternalTradeId());
        verify(profileStoreService).recordHotEventSync(eq(manualRuntimeProfile), any());
    }

    @Test
    void shouldSkipManualAllowlistRuntimeProfileUntilWalletSyncIsDue() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getPolymarket().setWalletTradeSyncMinIntervalMs(60_000L);
        SmartWalletProfileEntity manualRuntimeProfile = new SmartWalletProfileEntity()
                .setWalletAddress("0xmanual")
                .setChain("polygon")
                .setMonitorTier("HOT")
                .setWatchEnabled(Boolean.TRUE)
                .setLastEventSyncAt(new java.util.Date(System.currentTimeMillis()));
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(manualRuntimeProfile));

        PolymarketWalletDiscoveryService service = newService(properties);

        assertEquals(0, service.syncWatchlistTradesBatch());
        verify(monitorService, org.mockito.Mockito.never()).ingestTradeEvent(any());
    }

    @Test
    void shouldRefreshWarmWalletMetricsWithoutCreatingFollowTasks() {
        SmartWalletProperties properties = new SmartWalletProperties();
        SmartWalletProfileEntity warmWallet = new SmartWalletProfileEntity()
                .setId(11L)
                .setWalletAddress("0xwarm")
                .setAlias("warm-wallet")
                .setChain("polygon")
                .setMonitorTier("WARM");
        when(profileStoreService.listWarmWatchlist()).thenReturn(List.of(warmWallet));
        lenient().when(walletOrderAggregationService.snapshot("0xwarm")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xwarm")
                .closedOrders(List.of(
                        WalletOrderAggregationService.ClosedOrderSnapshot.builder()
                                .walletAddress("0xwarm")
                                .investedUsdc(new BigDecimal("10"))
                                .realizedPnlUsdc(new BigDecimal("2"))
                                .settlementAt(new java.util.Date())
                                .build()
                ))
                .build());
        when(polymarketApiClient.fetchUserTrades("0xwarm", properties.getPolymarket().getDiscoveryProfileTradeLimit()))
                .thenReturn(List.of());
        when(monitorService.ingestWalletMetrics(any())).thenReturn(new SmartWalletProfileEntity()
                .setId(11L)
                .setWalletAddress("0xwarm")
                .setAlias("warm-wallet")
                .setChain("polygon")
                .setMonitorTier("WARM"));
        when(profileTruthRefreshService.buildTruthMetrics(any(), any(), any()))
                .thenAnswer(invocation -> truthMetrics(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));

        PolymarketWalletDiscoveryService service = newService(properties);

        int refreshed = service.refreshWarmWalletMetricsBatch();

        assertEquals(1, refreshed);
        verify(monitorService).ingestWalletMetrics(any());
        verify(profileStoreService).recordWarmMetricsRefresh(any(), any());
        verify(monitorService, org.mockito.Mockito.never()).ingestTradeEvent(any());
        verify(profileStoreService, org.mockito.Mockito.never()).recordHotEventSync(any(), any());
    }

    @Test
    void shouldBackfillRecentTradesWhenWarmWalletPromotesToHot() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getPolymarket().setRecentTradeLimit(2);

        long checkpointMillis = System.currentTimeMillis() - 60_000L;
        SmartWalletProfileEntity warmWallet = new SmartWalletProfileEntity()
                .setId(21L)
                .setWalletAddress("0xhot")
                .setAlias("promoted-wallet")
                .setChain("polygon")
                .setMonitorTier("WARM")
                .setLastEventSyncAt(new java.util.Date(checkpointMillis));
        SmartWalletProfileEntity promotedWallet = new SmartWalletProfileEntity()
                .setId(21L)
                .setWalletAddress("0xhot")
                .setAlias("promoted-wallet")
                .setChain("polygon")
                .setMonitorTier("HOT")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE)
                .setLastEventSyncAt(new java.util.Date(checkpointMillis));

        when(profileStoreService.listWarmWatchlist()).thenReturn(List.of(warmWallet));
        lenient().when(walletOrderAggregationService.snapshot("0xhot")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xhot")
                .closedOrders(List.of(
                        WalletOrderAggregationService.ClosedOrderSnapshot.builder()
                                .walletAddress("0xhot")
                                .investedUsdc(new BigDecimal("12"))
                                .realizedPnlUsdc(new BigDecimal("3"))
                                .settlementAt(new java.util.Date())
                                .build()
                ))
                .build());
        when(polymarketApiClient.fetchUserTrades("0xhot", properties.getPolymarket().getDiscoveryProfileTradeLimit()))
                .thenReturn(List.of());
        when(monitorService.ingestWalletMetrics(any())).thenReturn(promotedWallet);
        when(profileTruthRefreshService.buildTruthMetrics(any(), any(), any()))
                .thenAnswer(invocation -> truthMetrics(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(polymarketApiClient.fetchUserTrades("0xhot", 2, 0)).thenReturn(List.of(
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-2")
                        .walletAddress("0xhot")
                        .marketId("market-2")
                        .question("Q2")
                        .outcome("YES")
                        .side("BUY")
                        .price(new BigDecimal("0.51"))
                        .size(new BigDecimal("8"))
                        .tradedAt(checkpointMillis + 2_000L)
                        .build(),
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-1")
                        .walletAddress("0xhot")
                        .marketId("market-1")
                        .question("Q1")
                        .outcome("YES")
                        .side("BUY")
                        .price(new BigDecimal("0.49"))
                        .size(new BigDecimal("6"))
                        .tradedAt(checkpointMillis + 1_000L)
                        .build()
        ));
        when(polymarketApiClient.fetchUserTrades("0xhot", 2, 2)).thenReturn(List.of(
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-1")
                        .walletAddress("0xhot")
                        .marketId("market-1")
                        .question("Q1")
                        .outcome("YES")
                        .side("BUY")
                        .price(new BigDecimal("0.49"))
                        .size(new BigDecimal("6"))
                        .tradedAt(checkpointMillis + 1_000L)
                        .build(),
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-old")
                        .walletAddress("0xhot")
                        .marketId("market-old")
                        .question("Q-old")
                        .outcome("YES")
                        .side("BUY")
                        .price(new BigDecimal("0.40"))
                        .size(new BigDecimal("4"))
                        .tradedAt(checkpointMillis)
                        .build()
        ));
        when(marketMetadataService.resolveCanonicalConditionId(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PolymarketWalletDiscoveryService service = newService(properties);

        long beforeSync = System.currentTimeMillis();
        int refreshed = service.refreshWarmWalletMetricsBatch();

        assertEquals(1, refreshed);
        ArgumentCaptor<PolymarketTradeEventRequest> captor = ArgumentCaptor.forClass(PolymarketTradeEventRequest.class);
        verify(monitorService, org.mockito.Mockito.times(2)).ingestTradeEvent(captor.capture());
        assertEquals(List.of("trade-1", "trade-2"),
                captor.getAllValues().stream().map(PolymarketTradeEventRequest::getExternalTradeId).toList());
        verify(profileStoreService).recordWarmMetricsRefresh(eq(promotedWallet), any());
        ArgumentCaptor<java.util.Date> checkpointCaptor = ArgumentCaptor.forClass(java.util.Date.class);
        verify(profileStoreService).recordHotEventSync(eq(promotedWallet), checkpointCaptor.capture());
        assertTrue(checkpointCaptor.getValue().getTime() >= checkpointMillis + 2_000L);
        assertTrue(checkpointCaptor.getValue().getTime() >= beforeSync);
    }

    @Test
    void shouldTruncateBackfillToLookbackWindowWhenCheckpointIsNull() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getPolymarket().setRecentTradeLimit(2);
        properties.getPolymarket().setWarmPromotionBackfillLookbackDays(2);

        long now = System.currentTimeMillis();
        SmartWalletProfileEntity warmWallet = new SmartWalletProfileEntity()
                .setId(31L)
                .setWalletAddress("0xhot")
                .setAlias("fresh-hot-wallet")
                .setChain("polygon")
                .setMonitorTier("WARM");
        // 新晋 HOT 钱包：lastEventSyncAt 为空，修复前会一路翻页回填全历史成交。
        SmartWalletProfileEntity promotedWallet = new SmartWalletProfileEntity()
                .setId(31L)
                .setWalletAddress("0xhot")
                .setAlias("fresh-hot-wallet")
                .setChain("polygon")
                .setMonitorTier("HOT")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE)
                .setLastMetricsRefreshAt(new java.util.Date(now))
                .setLastEventSyncAt(null);

        when(profileStoreService.listWarmWatchlist()).thenReturn(List.of(warmWallet));
        lenient().when(walletOrderAggregationService.snapshot("0xhot")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xhot")
                .closedOrders(List.of(
                        WalletOrderAggregationService.ClosedOrderSnapshot.builder()
                                .walletAddress("0xhot")
                                .investedUsdc(new BigDecimal("12"))
                                .realizedPnlUsdc(new BigDecimal("3"))
                                .settlementAt(new java.util.Date())
                                .build()
                ))
                .build());
        when(polymarketApiClient.fetchUserTrades("0xhot", properties.getPolymarket().getDiscoveryProfileTradeLimit()))
                .thenReturn(List.of());
        when(monitorService.ingestWalletMetrics(any())).thenReturn(promotedWallet);
        when(profileTruthRefreshService.buildTruthMetrics(any(), any(), any()))
                .thenAnswer(invocation -> truthMetrics(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        // offset 0：两笔在 2 天回填窗口内的成交。
        when(polymarketApiClient.fetchUserTrades("0xhot", 2, 0)).thenReturn(List.of(
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-recent-2").walletAddress("0xhot").marketId("market-r2")
                        .question("QR2").outcome("YES").side("BUY")
                        .price(new BigDecimal("0.51")).size(new BigDecimal("8"))
                        .tradedAt(now - 3_600_000L)
                        .build(),
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-recent-1").walletAddress("0xhot").marketId("market-r1")
                        .question("QR1").outcome("YES").side("BUY")
                        .price(new BigDecimal("0.49")).size(new BigDecimal("6"))
                        .tradedAt(now - 7_200_000L)
                        .build()
        ));
        // offset 2：两笔早于 2 天回填窗口的历史成交，命中时间下界后应停止翻页、不予回填。
        when(polymarketApiClient.fetchUserTrades("0xhot", 2, 2)).thenReturn(List.of(
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-old-1").walletAddress("0xhot").marketId("market-o1")
                        .question("QO1").outcome("YES").side("BUY")
                        .price(new BigDecimal("0.40")).size(new BigDecimal("4"))
                        .tradedAt(now - 5L * 24L * 3_600_000L)
                        .build(),
                PolymarketApiClient.UserTrade.builder()
                        .tradeId("trade-old-2").walletAddress("0xhot").marketId("market-o2")
                        .question("QO2").outcome("YES").side("BUY")
                        .price(new BigDecimal("0.38")).size(new BigDecimal("4"))
                        .tradedAt(now - 10L * 24L * 3_600_000L)
                        .build()
        ));
        when(marketMetadataService.resolveCanonicalConditionId(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PolymarketWalletDiscoveryService service = newService(properties);

        int refreshed = service.refreshWarmWalletMetricsBatch();

        assertEquals(1, refreshed);
        ArgumentCaptor<PolymarketTradeEventRequest> captor = ArgumentCaptor.forClass(PolymarketTradeEventRequest.class);
        // 仅回填窗口内的两笔成交进入风控链路，历史成交被时间下界拦截。
        verify(monitorService, org.mockito.Mockito.times(2)).ingestTradeEvent(captor.capture());
        assertEquals(List.of("trade-recent-1", "trade-recent-2"),
                captor.getAllValues().stream().map(PolymarketTradeEventRequest::getExternalTradeId).toList());
        // 命中时间下界后停止翻页，不再请求 offset=4。
        verify(polymarketApiClient, org.mockito.Mockito.never()).fetchUserTrades("0xhot", 2, 4);
    }

    @Test
    void scheduledTradeSyncShouldSubmitWorkToEventExecutorAndSkipOverlap() {
        SmartWalletProperties properties = new SmartWalletProperties();
        CapturingExecutor executor = new CapturingExecutor();
        when(monitorService.isEventSyncEnabled()).thenReturn(true);
        PolymarketWalletDiscoveryService service = newService(properties, executor);

        service.scheduledSyncWatchlistTrades();
        service.scheduledSyncWatchlistTrades();

        assertEquals(1, executor.executeCount.get());
        verify(profileStoreService, org.mockito.Mockito.never()).listExecutableWatchlist();

        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of());
        executor.runCaptured();

        service.scheduledSyncWatchlistTrades();
        assertEquals(2, executor.executeCount.get());
    }

    @Test
    void scheduledTradeSyncShouldResetRunningFlagWhenEventExecutorRejects() {
        SmartWalletProperties properties = new SmartWalletProperties();
        RejectingExecutor executor = new RejectingExecutor();
        when(monitorService.isEventSyncEnabled()).thenReturn(true);
        PolymarketWalletDiscoveryService service = newService(properties, executor);

        service.scheduledSyncWatchlistTrades();
        service.scheduledSyncWatchlistTrades();

        assertEquals(2, executor.executeCount.get());
        verify(profileStoreService, org.mockito.Mockito.never()).listExecutableWatchlist();
    }

    private PolymarketWalletDiscoveryService newService(SmartWalletProperties properties) {
        return newService(properties, Runnable::run);
    }

    private PolymarketWalletDiscoveryService newService(SmartWalletProperties properties, Executor eventSyncExecutor) {
        PolymarketWalletDiscoveryService service = new PolymarketWalletDiscoveryService(
                polymarketApiClient,
                monitorService,
                selectionService,
                profileStoreService,
                properties,
                new SmartWalletScoringService(),
                leaderboardStateService,
                walletArchetypeClassifierService,
                marketMetadataService,
                walletOrderAggregationService,
                leaderTradeOutcomeRebuildService,
                profileTruthRefreshService,
                manualAllowlistService,
                manualBlocklistService
        );
        service.setEventSyncExecutorForTest(eventSyncExecutor);
        return service;
    }

    private static class CapturingExecutor implements Executor {
        private final AtomicInteger executeCount = new AtomicInteger();
        private final AtomicReference<Runnable> captured = new AtomicReference<>();

        @Override
        public void execute(Runnable command) {
            executeCount.incrementAndGet();
            captured.set(command);
        }

        private void runCaptured() {
            Runnable command = captured.getAndSet(null);
            if (command != null) {
                command.run();
            }
        }
    }

    private static class RejectingExecutor implements Executor {
        private final AtomicInteger executeCount = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            executeCount.incrementAndGet();
            throw new RejectedExecutionException("full");
        }
    }

    private PolymarketWalletMetricsRequest truthMetrics(String walletAddress,
                                                        String alias,
                                                        SmartWalletProfileTruthRefreshService.DiscoveryMetadata metadata) {
        PolymarketWalletMetricsRequest request = new PolymarketWalletMetricsRequest();
        request.setWalletAddress(walletAddress);
        request.setAlias(alias);
        request.setWinRate(new BigDecimal("0.6000"));
        request.setRealizedPnlUsdc(new BigDecimal("12.0000"));
        request.setTotalTrades(3);
        request.setAverageSizeUsdc(new BigDecimal("10.0000"));
        request.setAverageRoiRate(new BigDecimal("0.1000"));
        request.setMaxDrawdownRate(BigDecimal.ZERO);
        if (metadata != null) {
            request.setLastDiscoveredAt(metadata.lastDiscoveredAt());
            request.setLastDiscoveryQuery(metadata.lastDiscoveryQuery());
            request.setLeaderboardHitCount30d(metadata.leaderboardHitCount30d());
        }
        return request;
    }
}
