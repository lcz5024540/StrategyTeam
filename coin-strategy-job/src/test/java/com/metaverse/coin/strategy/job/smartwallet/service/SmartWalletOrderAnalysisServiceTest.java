package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderAnalysisMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletOrderAnalysisServiceTest {

    @Mock
    private SmartWalletOrderAnalysisMapper orderAnalysisMapper;
    @Mock
    private WalletOrderAggregationService walletOrderAggregationService;

    private SmartWalletOrderAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new SmartWalletOrderAnalysisService(orderAnalysisMapper, walletOrderAggregationService);
    }

    @Test
    void shouldClassifyEarlyProfitAndPersistOrderAnalysis() {
        when(walletOrderAggregationService.snapshot("0xabc")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xabc")
                .closedOrders(List.of(
                        WalletOrderAggregationService.ClosedOrderSnapshot.builder()
                                .walletAddress("0xabc")
                        .marketId("market-1")
                        .question("Question")
                        .outcome("YES")
                                .openedAt(new java.util.Date(700_000L))
                                .settlementAt(new java.util.Date(1_000_000L))
                                .marketEndAt(2_000_000L)
                                .investedUsdc(new BigDecimal("100"))
                                .realizedPnlUsdc(new BigDecimal("20"))
                                .roiRate(new BigDecimal("0.20000000"))
                                .holdMinutes(new BigDecimal("5.00"))
                                .exitStyle("EARLY_PROFIT")
                                .exitLeadMinutes(new BigDecimal("16.67"))
                                .isWin(true)
                                .sourceConfidence(new BigDecimal("1.0000"))
                                .earlySettlement(true)
                                .build()
                ))
                .build());
        when(orderAnalysisMapper.selectList(any())).thenReturn(List.of());

        List<SmartWalletOrderAnalysisEntity> rows = service.analyzeAndPersist("0xabc");

        assertEquals(1, rows.size());
        assertEquals("EARLY_PROFIT", rows.get(0).getExitStyle());
        assertEquals(new BigDecimal("0.20000000"), rows.get(0).getRoiRate());
        assertEquals(new BigDecimal("5.00"), rows.get(0).getHoldMinutes());
        assertTrue(rows.get(0).getExitLeadMinutes().compareTo(BigDecimal.ZERO) > 0);
        verify(orderAnalysisMapper).insert(any(SmartWalletOrderAnalysisEntity.class));
    }

    @Test
    void shouldReturnUnknownExitStyleWithoutMarketEndTime() {
        when(walletOrderAggregationService.snapshot("0xabc")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xabc")
                .closedOrders(List.of(
                        WalletOrderAggregationService.ClosedOrderSnapshot.builder()
                                .walletAddress("0xabc")
                                .marketId("market-1")
                                .question("Question")
                                .outcome("NO")
                                .settlementAt(new java.util.Date(1_000_000L))
                                .investedUsdc(new BigDecimal("50"))
                                .realizedPnlUsdc(new BigDecimal("-5"))
                                .roiRate(new BigDecimal("-0.10000000"))
                                .exitStyle("UNKNOWN")
                                .isWin(false)
                                .sourceConfidence(new BigDecimal("0.7500"))
                                .build()
                ))
                .build());
        when(orderAnalysisMapper.selectList(any())).thenReturn(List.of());

        List<SmartWalletOrderAnalysisEntity> rows = service.analyzeAndPersist("0xabc");

        assertEquals(1, rows.size());
        assertEquals("UNKNOWN", rows.get(0).getExitStyle());
        assertNull(rows.get(0).getExitLeadMinutes());
    }

    @Test
    void shouldReuseFreshPersistedRowsWithoutRefreshing() {
        SmartWalletOrderAnalysisEntity fresh = rowWithUpdatedAt("market-fresh",
                new Date(System.currentTimeMillis() - 60_000L));
        when(orderAnalysisMapper.selectList(any())).thenReturn(List.of(fresh));

        List<SmartWalletOrderAnalysisEntity> rows = service.listByWalletAddress("0xabc", false, null, null);

        assertEquals(1, rows.size());
        assertEquals("market-fresh", rows.get(0).getMarketId());
        verify(walletOrderAggregationService, never()).snapshot(any());
    }

    @Test
    void shouldDowngradeLegacyClosedPositionsConfidenceInView() {
        SmartWalletOrderAnalysisEntity row = rowWithUpdatedAt("market-legacy", new Date())
                .setSourceConfidence(new BigDecimal("1.0000"));

        assertEquals(new BigDecimal("0.5000"), service.toView(row).sourceConfidence());
    }

    @Test
    void shouldRefreshStalePersistedRows() {
        SmartWalletOrderAnalysisEntity stale = rowWithUpdatedAt("market-stale",
                new Date(System.currentTimeMillis() - SmartWalletOrderAnalysisService.ORDER_ANALYSIS_STALE_MS - 1_000L));
        when(orderAnalysisMapper.selectList(any())).thenReturn(List.of(stale), List.of());
        when(walletOrderAggregationService.snapshot("0xabc")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xabc")
                .closedOrders(List.of(
                        WalletOrderAggregationService.ClosedOrderSnapshot.builder()
                                .walletAddress("0xabc")
                                .marketId("market-refreshed")
                                .outcome("YES")
                                .settlementAt(new Date(2_000_000L))
                                .investedUsdc(new BigDecimal("10"))
                                .realizedPnlUsdc(new BigDecimal("2"))
                                .roiRate(new BigDecimal("0.2"))
                                .exitStyle("SETTLED_WIN")
                                .isWin(true)
                                .build()
                ))
                .build());

        List<SmartWalletOrderAnalysisEntity> rows = service.listByWalletAddress("0xabc", false, null, null);

        assertEquals(1, rows.size());
        assertEquals("market-refreshed", rows.get(0).getMarketId());
        verify(walletOrderAggregationService).snapshot("0xabc");
        verify(orderAnalysisMapper).insert(any(SmartWalletOrderAnalysisEntity.class));
    }

    @Test
    void shouldForceRefreshEvenWhenPersistedRowsAreFresh() {
        when(walletOrderAggregationService.snapshot("0xabc")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xabc")
                .closedOrders(List.of(
                        WalletOrderAggregationService.ClosedOrderSnapshot.builder()
                                .walletAddress("0xabc")
                                .marketId("market-forced")
                                .outcome("NO")
                                .settlementAt(new Date(3_000_000L))
                                .investedUsdc(new BigDecimal("20"))
                                .realizedPnlUsdc(new BigDecimal("-4"))
                                .roiRate(new BigDecimal("-0.2"))
                                .exitStyle("SETTLED_LOSS")
                                .isWin(false)
                                .build()
                ))
                .build());
        when(orderAnalysisMapper.selectList(any())).thenReturn(List.of());

        List<SmartWalletOrderAnalysisEntity> rows = service.listByWalletAddress("0xabc", true, null, null);

        assertEquals(1, rows.size());
        assertEquals("market-forced", rows.get(0).getMarketId());
        verify(walletOrderAggregationService).snapshot("0xabc");
    }

    @Test
    void shouldRefreshWhenNoPersistedRowsExist() {
        when(orderAnalysisMapper.selectList(any())).thenReturn(List.of());
        when(walletOrderAggregationService.snapshot("0xabc")).thenReturn(WalletOrderAggregationService.WalletSnapshot.builder()
                .walletAddress("0xabc")
                .closedOrders(List.of())
                .build());

        List<SmartWalletOrderAnalysisEntity> rows = service.listByWalletAddress("0xabc", false, null, null);

        assertTrue(rows.isEmpty());
        verify(walletOrderAggregationService).snapshot("0xabc");
        verify(orderAnalysisMapper, times(2)).selectList(any());
    }

    private SmartWalletOrderAnalysisEntity rowWithUpdatedAt(String marketId, Date updatedAt) {
        return new SmartWalletOrderAnalysisEntity()
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setMarketId(marketId)
                .setOutcome("YES")
                .setClosedAt(new Date(1_000_000L))
                .setInvestedUsdc(new BigDecimal("100"))
                .setRealizedPnlUsdc(BigDecimal.TEN)
                .setRoiRate(new BigDecimal("0.1"))
                .setExitStyle("SETTLED_WIN")
                .setIsWin(true)
                .setUpdatedAt(updatedAt);
    }
}
