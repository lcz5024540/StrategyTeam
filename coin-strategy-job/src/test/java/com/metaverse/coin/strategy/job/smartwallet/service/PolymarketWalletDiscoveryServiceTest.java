package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSelectionSummary;
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
        when(polymarketApiClient.fetchClosedPositions("0xaaa")).thenReturn(List.of(
                PolymarketApiClient.ClosedPosition.builder()
                        .sizeBought(new BigDecimal("10"))
                        .realizedPnl(new BigDecimal("4"))
                        .cashPnl(BigDecimal.ZERO)
                        .closedAt(System.currentTimeMillis())
                        .build()
        ));
        when(polymarketApiClient.fetchClosedPositions("0xbbb")).thenReturn(List.of(
                PolymarketApiClient.ClosedPosition.builder()
                        .sizeBought(new BigDecimal("10"))
                        .realizedPnl(new BigDecimal("2"))
                        .cashPnl(BigDecimal.ZERO)
                        .closedAt(System.currentTimeMillis())
                        .build()
        ));
        when(polymarketApiClient.fetchUserTrades(any(), any())).thenReturn(Collections.emptyList());
        when(selectionService.refreshQualifiedWallets()).thenReturn(SmartWalletSelectionSummary.builder().qualifiedCount(1).build());
        when(profileStoreService.listWatchEnabled()).thenReturn(List.of(new SmartWalletProfileEntity().setWalletAddress("0xaaa")));

        PolymarketWalletDiscoveryService service = new PolymarketWalletDiscoveryService(
                polymarketApiClient,
                monitorService,
                selectionService,
                profileStoreService,
                properties,
                new SmartWalletScoringService(),
                leaderboardStateService,
                walletArchetypeClassifierService,
                marketMetadataService
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
        assertEquals("OVERALL|ALL|VOLUME|50", first.getLastDiscoveryQuery());
    }

    @Test
    void shouldNormalizeTradeMarketIdToCanonicalConditionIdBeforeIngest() {
        SmartWalletProperties properties = new SmartWalletProperties();
        SmartWalletProfileEntity wallet = new SmartWalletProfileEntity()
                .setWalletAddress("0xwallet")
                .setChain("polygon");
        when(profileStoreService.listWatchEnabled()).thenReturn(List.of(wallet));
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
                marketMetadataService
        );

        service.syncWatchlistTrades();

        ArgumentCaptor<PolymarketTradeEventRequest> captor = ArgumentCaptor.forClass(PolymarketTradeEventRequest.class);
        verify(monitorService).ingestTradeEvent(captor.capture());
        assertEquals("0xcondition", captor.getValue().getMarketId());
    }
}
