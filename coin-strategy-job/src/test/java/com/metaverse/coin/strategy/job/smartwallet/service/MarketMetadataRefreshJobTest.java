package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.MarketStateSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketMetadataRefreshJobTest {

    @Mock
    private SmartWalletMarketMonitoringService marketMonitoringService;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private SmartWalletMarketStateService marketStateService;
    @Mock
    private PolymarketApiClient polymarketApiClient;

    private SmartWalletProperties properties;
    private MarketMetadataRefreshJob job;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        properties.getMarketMonitoring().getMetadataRefresh().setRefreshBatchSize(2);
        properties.getMarketMonitoring().getMetadataRefresh().setMaxCandidateMarkets(3);
        properties.getMarketMonitoring().getMetadataRefresh().setCandidateLookbackDays(30);
        properties.getMarketMonitoring().getMetadataRefresh().setGammaChunkSize(20);
        properties.getMarketMonitoring().getMetadataRefresh().setMaxStaleMs(900000L);
        job = new MarketMetadataRefreshJob(properties, marketMonitoringService, marketInfoService, marketStateService, polymarketApiClient);
    }

    @Test
    void shouldRefreshMostStaleOrMissingMarketsFirst() {
        Date stale = new Date(System.currentTimeMillis() - 1_800_000L);
        Date fresh = new Date(System.currentTimeMillis() - 60_000L);
        when(marketInfoService.listIncompleteActiveMarketIds(3)).thenReturn(List.of());
        when(marketMonitoringService.listCandidateMarketIds(3, 30))
                .thenReturn(List.of("market-missing", "market-stale", "market-fresh"));
        when(marketStateService.loadPersistedMarkets(List.of("market-missing", "market-stale", "market-fresh")))
                .thenReturn(Map.of(
                        "market-stale", MarketStateSnapshot.builder()
                                .marketId("market-stale")
                                .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                                .lastMetadataSyncAt(stale)
                                .acceptingOrders(true)
                                .build(),
                        "market-fresh", MarketStateSnapshot.builder()
                                .marketId("market-fresh")
                                .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                                .lastMetadataSyncAt(fresh)
                                .acceptingOrders(true)
                                .build()
                ));
        when(marketStateService.refreshPersistedMarkets(List.of("market-missing", "market-stale"), 20))
                .thenReturn(Map.of());

        job.refreshMetadata();

        verify(marketMonitoringService).listCandidateMarketIds(3, 30);
        verify(marketStateService).loadPersistedMarkets(List.of("market-missing", "market-stale", "market-fresh"));
        verify(marketStateService).refreshPersistedMarkets(List.of("market-missing", "market-stale"), 20);
    }

    @Test
    void shouldRefreshIncompleteActiveMarketInfoEvenWhenNotMonitored() {
        when(marketInfoService.listIncompleteActiveMarketIds(3)).thenReturn(List.of("market-incomplete"));
        when(marketMonitoringService.listCandidateMarketIds(3, 30)).thenReturn(List.of());
        when(marketStateService.loadPersistedMarkets(List.of("market-incomplete"))).thenReturn(Map.of());
        when(marketStateService.refreshPersistedMarkets(List.of("market-incomplete"), 20)).thenReturn(Map.of());

        job.refreshMetadata();

        verify(marketStateService).loadPersistedMarkets(List.of("market-incomplete"));
        verify(marketStateService).refreshPersistedMarkets(List.of("market-incomplete"), 20);
    }

    @Test
    void shouldPrioritizeIncompleteMarketInfoEvenWhenRecentlySynced() {
        properties.getMarketMonitoring().getMetadataRefresh().setRefreshBatchSize(1);
        Date stale = new Date(System.currentTimeMillis() - 1_800_000L);
        Date fresh = new Date(System.currentTimeMillis() - 60_000L);
        when(marketInfoService.listIncompleteActiveMarketIds(3)).thenReturn(List.of("market-incomplete"));
        when(marketMonitoringService.listCandidateMarketIds(3, 30)).thenReturn(List.of("market-stale"));
        when(marketStateService.loadPersistedMarkets(List.of("market-incomplete", "market-stale")))
                .thenReturn(Map.of(
                        "market-incomplete", MarketStateSnapshot.builder()
                                .marketId("market-incomplete")
                                .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                                .lastMetadataSyncAt(fresh)
                                .acceptingOrders(true)
                                .build(),
                        "market-stale", MarketStateSnapshot.builder()
                                .marketId("market-stale")
                                .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                                .lastMetadataSyncAt(stale)
                                .acceptingOrders(true)
                                .build()
                ));
        when(marketStateService.refreshPersistedMarkets(List.of("market-incomplete"), 20)).thenReturn(Map.of());

        job.refreshMetadata();

        verify(marketStateService).refreshPersistedMarkets(List.of("market-incomplete"), 20);
    }

    @Test
    void shouldSkipRefreshWhenMetadataRefreshDisabled() {
        properties.getMarketMonitoring().getMetadataRefresh().setEnabled(false);

        job.refreshMetadata();

        verify(marketInfoService, never()).listIncompleteActiveMarketIds(anyInt());
        verify(marketMonitoringService, never()).listCandidateMarketIds(anyInt(), anyInt());
        verify(marketStateService, never()).refreshPersistedMarkets(any(), eq(20));
    }
}
