package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketMonitoringSummaryView;
import com.metaverse.coin.strategy.job.strategy.model.OffsetPageView;
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
                concentrationSnapshotService
        );
        when(followTaskMapper.selectList(any())).thenReturn(List.of());
        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(marketInfoService.batchGetByIds(any())).thenReturn(Map.of());
        when(marketInfoService.batchGetPrimaryByMarketIds(any())).thenReturn(Map.of());
        when(concentrationSnapshotService.batchGetLatestSnapshots(any(), isNull())).thenReturn(Map.of());
    }

    @Test
    void shouldOnlyReturnActiveMarketsInSummary() {
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(
                event("market-active", "Will active market stay visible?"),
                event("market-closed", "Will closed market disappear?")
        ));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of(
                "market-active", SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-active")
                        .question("Will active market stay visible?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_POLITICS)
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .build(),
                "market-closed", SmartWalletMarketStateService.MarketStateSnapshot.builder()
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
                        new MarketConcentrationService.ConcentrationSnapshot(
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
                "market-politics", SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-politics")
                        .question("Will election result change?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_POLITICS)
                        .sourceCategory("Politics")
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .build(),
                "market-weather", SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-weather")
                        .question("Will it rain tomorrow?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_WEATHER)
                        .sourceCategory("Weather")
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .build()
        ));

        OffsetPageView<com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketMonitorItemView> page =
                service.tablePage(1, 10, 14, null, "POLITICS", null, null, null);

        assertEquals(1, page.getItems().size());
        assertEquals("market-politics", page.getItems().get(0).getMarketId());
        assertTrue(page.getItems().get(0).getNormalizedCategory().contains("POLITICS"));
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
                "market-stale", SmartWalletMarketStateService.MarketStateSnapshot.builder()
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
                        new MarketConcentrationService.ConcentrationSnapshot(
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
    void shouldHideExpiredMarketsEvenIfPersistedSnapshotStillSaysActive() {
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(
                event("market-expired", "Will expired active market disappear?")
        ));
        when(marketStateService.loadPersistedMarkets(any())).thenReturn(Map.of(
                "market-expired", SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-expired")
                        .question("Will expired active market disappear?")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
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

    private SmartWalletTradeEventEntity event(String marketId, String question) {
        return new SmartWalletTradeEventEntity()
                .setSource("POLYMARKET")
                .setWalletAddress("0xabc")
                .setMarketId(marketId)
                .setQuestion(question)
                .setOutcome("YES")
                .setOpenedAt(new Date());
    }
}
