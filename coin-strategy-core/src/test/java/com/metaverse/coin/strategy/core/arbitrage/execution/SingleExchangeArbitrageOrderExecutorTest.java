package com.metaverse.coin.strategy.core.arbitrage.execution;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SingleExchangeArbitrageOrderExecutorTest {

    @Mock
    private BinanceApiService binanceApiService;

    @Mock
    private SingleExchangeSpotFuturesProperties properties;

    private SingleExchangeArbitrageOrderExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SingleExchangeArbitrageOrderExecutor(binanceApiService, properties);
    }

    @Test
    void binanceTestModeOpenUsesTestOrdersAndBuildsFilledRecords() {
        when(properties.getOrderExecutionMode()).thenReturn("BINANCE_TEST");
        when(binanceApiService.getTickerPrice("BTCUSDT"))
                .thenReturn(BinancePriceTicker.builder().symbol("BTCUSDT").price(new BigDecimal("50000")).build());
        when(binanceApiService.getFuturesTickerPrice("BTCUSDT"))
                .thenReturn(BinancePriceTicker.builder().symbol("BTCUSDT").price(new BigDecimal("50010")).build());

        SingleExchangeArbitrageExecutionResult result = executor.openArbitragePosition(plan());

        assertTrue(result.isSuccess());
        assertEquals(2, result.getOrderRecords().size());
        assertTrue(result.getOrderRecords().stream().allMatch(order -> "FILLED".equals(order.getStatus())));
        assertTrue(result.getOrderRecords().stream().allMatch(order -> order.getRawResponse().contains("BINANCE_TEST")));
        verify(binanceApiService).testSpotOrder("BTCUSDT", "BUY", "MARKET", null, "0.001", null);
        verify(binanceApiService).testFuturesOrder("BTCUSDT", "SELL", "SHORT", "MARKET", null, "0.001", null, null);
        verify(binanceApiService, never()).placeSpotOrder(any(), any(), any(), any(), any(), any());
        verify(binanceApiService, never()).openShort(any(), any(), any(), any(), any());
    }

    @Test
    void binanceTestModeRecordsFailureWhenOneLegTestOrderFails() {
        when(properties.getOrderExecutionMode()).thenReturn("BINANCE_TEST");
        when(binanceApiService.getTickerPrice("BTCUSDT"))
                .thenReturn(BinancePriceTicker.builder().symbol("BTCUSDT").price(new BigDecimal("50000")).build());
        doThrow(new IllegalStateException("futures test rejected"))
                .when(binanceApiService)
                .testFuturesOrder("BTCUSDT", "SELL", "SHORT", "MARKET", null, "0.001", null, null);

        SingleExchangeArbitrageExecutionResult result = executor.openArbitragePosition(plan());

        assertFalse(result.isSuccess());
        assertTrue(result.getOrderRecords().stream().anyMatch(order ->
                "FAILED".equals(order.getStatus()) && order.getErrorMessage().contains("futures test rejected")));
        verify(binanceApiService).testSpotOrder("BTCUSDT", "BUY", "MARKET", null, "0.001", null);
        verify(binanceApiService).testFuturesOrder("BTCUSDT", "SELL", "SHORT", "MARKET", null, "0.001", null, null);
        verify(binanceApiService, never()).openShort(any(), any(), any(), any(), any());
    }

    @Test
    void binanceLiveModeKeepsRealOrderPath() {
        when(properties.getOrderExecutionMode()).thenReturn("BINANCE_LIVE");
        when(binanceApiService.placeSpotOrder("BTCUSDT", "BUY", "MARKET", null, "0.001", null))
                .thenReturn(BinanceOrder.builder()
                        .symbol("BTCUSDT")
                        .orderId(1L)
                        .status("FILLED")
                        .executedQty(new BigDecimal("0.001"))
                        .cummulativeQuoteQty(new BigDecimal("50"))
                        .build());
        when(binanceApiService.openShort("BTCUSDT", "MARKET", "0.001", null, null))
                .thenReturn(BinanceFuturesOrder.builder()
                        .symbol("BTCUSDT")
                        .orderId(2L)
                        .status("FILLED")
                        .executedQty(new BigDecimal("0.001"))
                        .cumQuote(new BigDecimal("50"))
                        .build());

        SingleExchangeArbitrageExecutionResult result = executor.openArbitragePosition(plan());

        assertTrue(result.isSuccess());
        verify(binanceApiService).placeSpotOrder("BTCUSDT", "BUY", "MARKET", null, "0.001", null);
        verify(binanceApiService).openShort("BTCUSDT", "MARKET", "0.001", null, null);
        verify(binanceApiService, never()).testSpotOrder(any(), any(), any(), any(), any(), any());
        verify(binanceApiService, never()).testFuturesOrder(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void openArbitragePositionEmitsSlippageAlertWhenSpotFillExceedsThreshold() {
        when(properties.getOrderExecutionMode()).thenReturn("BINANCE_LIVE");
        when(properties.getEstimatedSlippageRate()).thenReturn(new BigDecimal("0.0005"));
        // Spot BUY 实际成交 50100（参考价 spotPrice=50000）→ 实际滑点 100/50000 = 0.002 > 0.0005 阈值。
        when(binanceApiService.placeSpotOrder("BTCUSDT", "BUY", "MARKET", null, "0.001", null))
                .thenReturn(BinanceOrder.builder()
                        .symbol("BTCUSDT")
                        .orderId(1L)
                        .status("FILLED")
                        .executedQty(new BigDecimal("0.001"))
                        .cummulativeQuoteQty(new BigDecimal("50.1"))
                        .build());
        // 合约 SELL 实际成交 avgPrice=50010（参考价 futuresPrice=50010）→ 滑点 0，不告警。
        when(binanceApiService.openShort("BTCUSDT", "MARKET", "0.001", null, null))
                .thenReturn(BinanceFuturesOrder.builder()
                        .symbol("BTCUSDT")
                        .orderId(2L)
                        .status("FILLED")
                        .executedQty(new BigDecimal("0.001"))
                        .avgPrice(new BigDecimal("50010"))
                        .cumQuote(new BigDecimal("50.01"))
                        .build());

        SingleExchangeArbitrageExecutionResult result = executor.openArbitragePosition(plan());

        assertTrue(result.isSuccess());
        assertEquals(1, result.getSlippageAlerts().size());
        SingleExchangeSlippageAlert alert = result.getSlippageAlerts().get(0);
        assertEquals("SPOT", alert.getHedgeSide());
        assertEquals("OPEN", alert.getTradePhase());
        assertEquals("BUY", alert.getSide());
        assertEquals(0, alert.getActualSlippageRate().compareTo(new BigDecimal("0.002")));
        assertEquals(0, alert.getThresholdRate().compareTo(new BigDecimal("0.0005")));
    }

    @Test
    void openArbitragePositionEmitsNakedPositionAlertWhenSpotRollbackFails() {
        when(properties.getOrderExecutionMode()).thenReturn("BINANCE_LIVE");
        // 现货 BUY 成交（多头建立）
        when(binanceApiService.placeSpotOrder("BTCUSDT", "BUY", "MARKET", null, "0.001", null))
                .thenReturn(filledSpotOrder("BUY"));
        // 合约 SELL 开空失败 → 触发现货腿 rollback（反向 SELL）
        when(binanceApiService.openShort("BTCUSDT", "MARKET", "0.001", null, null))
                .thenThrow(new IllegalStateException("futures short rejected"));
        // rollback 现货 SELL 也失败 → 现货多头裸露
        when(binanceApiService.placeSpotOrder("BTCUSDT", "SELL", "MARKET", null, "0.001", null))
                .thenThrow(new IllegalStateException("rollback sell rejected"));

        SingleExchangeArbitrageExecutionResult result = executor.openArbitragePosition(plan());

        assertFalse(result.isSuccess());
        assertEquals(1, result.getNakedPositionAlerts().size());
        SingleExchangeNakedPositionAlert alert = result.getNakedPositionAlerts().get(0);
        assertEquals("SPOT", alert.getNakedHedgeSide());
        assertEquals("LONG", alert.getNakedPositionSide());
        assertEquals("OPEN", alert.getTradePhase());
        assertEquals(0, alert.getQuantity().compareTo(new BigDecimal("0.001")));
        assertTrue(alert.getRollbackErrorMessage().contains("rollback sell rejected"));
    }

    @Test
    void openArbitragePositionEmitsNakedPositionAlertWhenFuturesRollbackFails() {
        when(properties.getOrderExecutionMode()).thenReturn("BINANCE_LIVE");
        // 现货 BUY 失败 → 触发合约腿 rollback（平空 BUY）
        when(binanceApiService.placeSpotOrder("BTCUSDT", "BUY", "MARKET", null, "0.001", null))
                .thenThrow(new IllegalStateException("spot buy rejected"));
        // 合约 SELL 开空成交（空头建立）
        when(binanceApiService.openShort("BTCUSDT", "MARKET", "0.001", null, null))
                .thenReturn(filledFuturesOrder("SELL"));
        // rollback 合约平空（closeShort）也失败 → 合约空头裸露
        when(binanceApiService.closeShort("BTCUSDT", "MARKET", "0.001", null))
                .thenThrow(new IllegalStateException("rollback close short rejected"));

        SingleExchangeArbitrageExecutionResult result = executor.openArbitragePosition(plan());

        assertFalse(result.isSuccess());
        assertEquals(1, result.getNakedPositionAlerts().size());
        SingleExchangeNakedPositionAlert alert = result.getNakedPositionAlerts().get(0);
        assertEquals("FUTURES", alert.getNakedHedgeSide());
        assertEquals("SHORT", alert.getNakedPositionSide());
        assertEquals("OPEN", alert.getTradePhase());
        assertTrue(alert.getRollbackErrorMessage().contains("rollback close short rejected"));
    }

    @Test
    void closeArbitragePositionEmitsNakedPositionAlertWhenSpotRollbackFails() {
        when(properties.getOrderExecutionMode()).thenReturn("BINANCE_LIVE");
        // 现货平仓 SELL 成交（现货已平），合约平仓 BUY 失败 → 合约空头仍在
        when(binanceApiService.placeSpotOrder("BTCUSDT", "SELL", "MARKET", null, "0.001", null))
                .thenReturn(filledSpotOrder("SELL"));
        when(binanceApiService.closeShort("BTCUSDT", "MARKET", "0.001", null))
                .thenThrow(new IllegalStateException("futures close rejected"));
        // rollback 现货重新 BUY（恢复对冲）也失败 → 合约空头裸露
        when(binanceApiService.placeSpotOrder("BTCUSDT", "BUY", "MARKET", null, "0.001", null))
                .thenThrow(new IllegalStateException("rollback rebuy rejected"));

        SingleExchangeArbitrageExecutionResult result = executor.closeArbitragePosition(plan());

        assertFalse(result.isSuccess());
        assertEquals(1, result.getNakedPositionAlerts().size());
        SingleExchangeNakedPositionAlert alert = result.getNakedPositionAlerts().get(0);
        assertEquals("FUTURES", alert.getNakedHedgeSide());
        assertEquals("SHORT", alert.getNakedPositionSide());
        assertEquals("CLOSE", alert.getTradePhase());
        assertTrue(alert.getRollbackErrorMessage().contains("rollback rebuy rejected"));
    }

    @Test
    void openArbitragePositionNoNakedAlertWhenRollbackSucceeds() {
        when(properties.getOrderExecutionMode()).thenReturn("BINANCE_LIVE");
        when(binanceApiService.placeSpotOrder("BTCUSDT", "BUY", "MARKET", null, "0.001", null))
                .thenReturn(filledSpotOrder("BUY"));
        when(binanceApiService.openShort("BTCUSDT", "MARKET", "0.001", null, null))
                .thenThrow(new IllegalStateException("futures short rejected"));
        // rollback 现货 SELL 成功 → 敞口已恢复，不应有裸仓告警
        when(binanceApiService.placeSpotOrder("BTCUSDT", "SELL", "MARKET", null, "0.001", null))
                .thenReturn(filledSpotOrder("SELL"));

        SingleExchangeArbitrageExecutionResult result = executor.openArbitragePosition(plan());

        assertFalse(result.isSuccess());
        assertTrue(result.getNakedPositionAlerts().isEmpty());
    }

    private BinanceOrder filledSpotOrder(String side) {
        return BinanceOrder.builder()
                .symbol("BTCUSDT")
                .orderId(1L)
                .side(side)
                .status("FILLED")
                .executedQty(new BigDecimal("0.001"))
                .cummulativeQuoteQty(new BigDecimal("50"))
                .build();
    }

    private BinanceFuturesOrder filledFuturesOrder(String side) {
        return BinanceFuturesOrder.builder()
                .symbol("BTCUSDT")
                .orderId(2L)
                .side(side)
                .status("FILLED")
                .executedQty(new BigDecimal("0.001"))
                .avgPrice(new BigDecimal("50010"))
                .cumQuote(new BigDecimal("50.01"))
                .build();
    }

    private SingleExchangeSpotFuturesOrderPlan plan() {
        return SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol("BTCUSDT")
                .spotSymbol("BTCUSDT")
                .futuresSymbol("BTCUSDT")
                .quantity(new BigDecimal("0.001"))
                .spotPrice(new BigDecimal("50000"))
                .futuresPrice(new BigDecimal("50010"))
                .spotNotional(new BigDecimal("50"))
                .futuresNotional(new BigDecimal("50.01"))
                .leverage(3)
                .build();
    }
}