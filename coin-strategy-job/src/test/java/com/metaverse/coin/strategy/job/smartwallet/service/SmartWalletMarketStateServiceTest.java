package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletMarketStateServiceTest {

    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;

    private SmartWalletMarketStateService service;

    @BeforeEach
    void setUp() {
        service = new SmartWalletMarketStateService(marketInfoService, marketMetadataService);
    }

    @Test
    void shouldNormalizeWeatherCategoryFromSourceMetadata() {
        SmartWalletMarketInfoEntity row = new SmartWalletMarketInfoEntity()
                .setId(1L)
                .setMarketId("market-weather")
                .setQuestion("Will it rain in Shanghai tomorrow?")
                .setOutcome("YES");
        when(marketInfoService.listByMarketId("market-weather")).thenReturn(List.of(row));
        when(marketMetadataService.getBestEffortByConditionId(
                eq("market-weather"),
                anyBoolean(),
                eq("Will it rain in Shanghai tomorrow?"),
                anyList()))
                .thenReturn(PolymarketApiClient.MarketMetadata.builder()
                        .conditionId("market-weather")
                        .question("Will it rain in Shanghai tomorrow?")
                        .category("Weather")
                        .acceptingOrders(true)
                        .closed(false)
                        .endAt(System.currentTimeMillis() + 3_600_000L)
                        .build());

        SmartWalletMarketStateService.MarketStateSnapshot snapshot = service.refreshMarket(
                "market-weather",
                "Will it rain in Shanghai tomorrow?",
                List.of("YES"),
                true
        );

        assertEquals("Weather", snapshot.sourceCategory());
        assertEquals(SmartWalletMarketStateService.CATEGORY_WEATHER, snapshot.normalizedCategory());
        assertEquals(SmartWalletMarketStateService.LIFECYCLE_ACTIVE, snapshot.marketLifecycle());
        verify(marketInfoService).update(any(SmartWalletMarketInfoEntity.class));
    }

    @Test
    void shouldPrioritizeResolvedLifecycleOverClosedFlags() {
        SmartWalletMarketInfoEntity row = new SmartWalletMarketInfoEntity()
                .setId(2L)
                .setMarketId("market-resolved")
                .setQuestion("Will resolved lifecycle win priority?")
                .setOutcome("YES");
        when(marketInfoService.listByMarketId("market-resolved")).thenReturn(List.of(row));
        when(marketMetadataService.getBestEffortByConditionId(
                eq("market-resolved"),
                anyBoolean(),
                eq("Will resolved lifecycle win priority?"),
                anyList()))
                .thenReturn(PolymarketApiClient.MarketMetadata.builder()
                        .conditionId("market-resolved")
                        .question("Will resolved lifecycle win priority?")
                        .category("Politics")
                        .closed(true)
                        .acceptingOrders(false)
                        .resolvedOutcome("YES")
                        .endAt(System.currentTimeMillis() - 60_000L)
                        .build());

        SmartWalletMarketStateService.MarketStateSnapshot snapshot = service.refreshMarket(
                "market-resolved",
                "Will resolved lifecycle win priority?",
                List.of("YES"),
                true
        );

        assertEquals(SmartWalletMarketStateService.LIFECYCLE_RESOLVED, snapshot.marketLifecycle());
        assertEquals("MARKET_RESOLVED", snapshot.blockingReasonCode());
    }

    @Test
    void shouldClassifyNonAcceptingOrdersAsClosedUnresolved() {
        SmartWalletMarketInfoEntity row = new SmartWalletMarketInfoEntity()
                .setId(3L)
                .setMarketId("market-closed")
                .setQuestion("Will closed unresolved be detected?")
                .setOutcome("YES");
        when(marketInfoService.listByMarketId("market-closed")).thenReturn(List.of(row));
        when(marketMetadataService.getBestEffortByConditionId(
                eq("market-closed"),
                anyBoolean(),
                eq("Will closed unresolved be detected?"),
                anyList()))
                .thenReturn(PolymarketApiClient.MarketMetadata.builder()
                        .conditionId("market-closed")
                        .question("Will closed unresolved be detected?")
                        .acceptingOrders(false)
                        .closed(false)
                        .endAt(System.currentTimeMillis() + 3_600_000L)
                        .build());

        SmartWalletMarketStateService.MarketStateSnapshot snapshot = service.refreshMarket(
                "market-closed",
                "Will closed unresolved be detected?",
                List.of("YES"),
                true
        );

        assertEquals(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED, snapshot.marketLifecycle());
        assertEquals("MARKET_NOT_ACCEPTING_ORDERS", snapshot.blockingReasonCode());
        assertTrue(snapshot.blockingReasonMessage().contains("[MARKET_NOT_ACCEPTING_ORDERS]"));
    }

    @Test
    void shouldLoadPersistedSnapshotWithoutRemoteRefresh() {
        SmartWalletMarketInfoEntity row = new SmartWalletMarketInfoEntity()
                .setId(4L)
                .setMarketId("market-db-only")
                .setQuestion("Will persisted rows be enough?")
                .setOutcome("YES")
                .setNormalizedCategory(SmartWalletMarketStateService.CATEGORY_POLITICS)
                .setMarketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                .setLastMetadataSyncAt(new java.util.Date(System.currentTimeMillis() - 3_600_000L));
        when(marketInfoService.batchListByMarketIds(List.of("market-db-only")))
                .thenReturn(Map.of("market-db-only", List.of(row)));

        Map<String, SmartWalletMarketStateService.MarketStateSnapshot> snapshots =
                service.loadPersistedMarkets(List.of("market-db-only"));

        assertEquals(1, snapshots.size());
        assertEquals("market-db-only", snapshots.get("market-db-only").marketId());
        assertEquals("Will persisted rows be enough?", snapshots.get("market-db-only").question());
        verify(marketMetadataService, never()).getBestEffortByConditionId(any(), anyBoolean(), any(), anyList());
    }

    @Test
    void shouldTreatPersistedExpiredActiveSnapshotAsClosedUnresolved() {
        SmartWalletMarketInfoEntity row = new SmartWalletMarketInfoEntity()
                .setId(5L)
                .setMarketId("market-expired")
                .setQuestion("Will expired persisted rows be hidden?")
                .setOutcome("YES")
                .setNormalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                .setMarketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                .setAcceptingOrders(true)
                .setClosed(false)
                .setMarketEndAt(System.currentTimeMillis() - 60_000L)
                .setLastMetadataSyncAt(new java.util.Date(System.currentTimeMillis() - 3_600_000L));
        when(marketInfoService.batchListByMarketIds(List.of("market-expired")))
                .thenReturn(Map.of("market-expired", List.of(row)));

        Map<String, SmartWalletMarketStateService.MarketStateSnapshot> snapshots =
                service.loadPersistedMarkets(List.of("market-expired"));

        assertEquals(1, snapshots.size());
        assertEquals(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED,
                snapshots.get("market-expired").marketLifecycle());
        assertFalse(snapshots.get("market-expired").isActive());
        verify(marketMetadataService, never()).getBestEffortByConditionId(any(), anyBoolean(), any(), anyList());
    }
}
