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
    private PolymarketApiClient polymarketApiClient;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;

    private SmartWalletOrderAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new SmartWalletOrderAnalysisService(orderAnalysisMapper, polymarketApiClient, marketMetadataService);
    }

    @Test
    void shouldClassifyEarlyProfitAndPersistOrderAnalysis() {
        when(polymarketApiClient.fetchClosedPositions("0xabc")).thenReturn(List.of(
                PolymarketApiClient.ClosedPosition.builder()
                        .marketId("market-1")
                        .question("Question")
                        .outcome("YES")
                        .sizeBought(new BigDecimal("100"))
                        .realizedPnl(new BigDecimal("20"))
                        .cashPnl(BigDecimal.ZERO)
                        .avgPrice(new BigDecimal("0.62"))
                        .closedAt(1_000_000L)
                        .build()
        ));
        when(polymarketApiClient.fetchUserTrades("0xabc", 200)).thenReturn(List.of(
                PolymarketApiClient.UserTrade.builder()
                        .marketId("market-1")
                        .outcome("YES")
                        .tradedAt(700_000L)
                        .build()
        ));
        when(marketMetadataService.getBestEffortByConditionId(any(), any(), any(), any())).thenReturn(
                PolymarketApiClient.MarketMetadata.builder()
                        .conditionId("market-1")
                        .marketId("market-1")
                        .endAt(2_000_000L)
                        .build()
        );
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
        when(polymarketApiClient.fetchClosedPositions("0xabc")).thenReturn(List.of(
                PolymarketApiClient.ClosedPosition.builder()
                        .marketId("market-1")
                        .question("Question")
                        .outcome("NO")
                        .sizeBought(new BigDecimal("50"))
                        .realizedPnl(new BigDecimal("-5"))
                        .cashPnl(BigDecimal.ZERO)
                        .closedAt(1_000_000L)
                        .build()
        ));
        when(polymarketApiClient.fetchUserTrades("0xabc", 200)).thenReturn(List.of());
        when(marketMetadataService.getBestEffortByConditionId(any(), any(), any(), any())).thenReturn(
                PolymarketApiClient.MarketMetadata.builder()
                        .conditionId("market-1")
                        .marketId("market-1")
                        .build()
        );
        when(orderAnalysisMapper.selectList(any())).thenReturn(List.of());

        List<SmartWalletOrderAnalysisEntity> rows = service.analyzeAndPersist("0xabc");

        assertEquals(1, rows.size());
        assertEquals("UNKNOWN", rows.get(0).getExitStyle());
        assertNull(rows.get(0).getExitLeadMinutes());
    }
}
