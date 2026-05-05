package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(walletOrderAggregationService.snapshot("0xaaa")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
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
        when(walletOrderAggregationService.snapshot("0xbbb")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
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
                walletOrderAggregationService
        );

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
                walletOrderAggregationService
        );

        service.syncWatchlistTrades();

        ArgumentCaptor<PolymarketTradeEventRequest> captor = ArgumentCaptor.forClass(PolymarketTradeEventRequest.class);
        verify(monitorService).ingestTradeEvent(captor.capture());
        assertEquals("0xcondition", captor.getValue().getMarketId());
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
        when(walletOrderAggregationService.snapshot("0xwarm")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
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
                walletOrderAggregationService
        );

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
        when(walletOrderAggregationService.snapshot("0xhot")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
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
                walletOrderAggregationService
        );

        int refreshed = service.refreshWarmWalletMetricsBatch();

        assertEquals(1, refreshed);
        ArgumentCaptor<PolymarketTradeEventRequest> captor = ArgumentCaptor.forClass(PolymarketTradeEventRequest.class);
        verify(monitorService, org.mockito.Mockito.times(2)).ingestTradeEvent(captor.capture());
        assertEquals(List.of("trade-1", "trade-2"),
                captor.getAllValues().stream().map(PolymarketTradeEventRequest::getExternalTradeId).toList());
        verify(profileStoreService).recordWarmMetricsRefresh(eq(promotedWallet), any());
        verify(profileStoreService).recordHotEventSync(eq(promotedWallet), any());
    }
}
