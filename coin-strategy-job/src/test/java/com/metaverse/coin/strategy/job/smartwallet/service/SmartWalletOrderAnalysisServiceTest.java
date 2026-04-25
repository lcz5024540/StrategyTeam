package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderAnalysisMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
}
