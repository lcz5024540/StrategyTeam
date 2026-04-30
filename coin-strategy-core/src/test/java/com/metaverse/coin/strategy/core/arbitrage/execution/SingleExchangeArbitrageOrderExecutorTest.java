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
    void liveModeKeepsRealOrderPath() {
        when(properties.getOrderExecutionMode()).thenReturn("LIVE");
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
