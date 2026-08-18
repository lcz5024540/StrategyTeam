package com.metaverse.coin.strategy.core.arbitrage.risk;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceFuturesService;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceSpotService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesCommissionRate;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceCommissionResponse;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeDepthMonitor;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeDepthSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SingleExchangeRiskServiceTest {

    @Mock
    private BinanceApiService binanceApiService;

    @Mock
    private SingleExchangeDepthMonitor depthMonitor;

    @Spy
    private SingleExchangeSpotFuturesProperties properties = new SingleExchangeSpotFuturesProperties();

    @InjectMocks
    private SingleExchangeRiskService riskService;

    @Test
    void testRejectOpenWhenOrderBookDepthIsInsufficient() {
        when(depthMonitor.getDepthSnapshot("BTCUSDT")).thenReturn(SingleExchangeDepthSnapshot.builder()
                .fallbackUsed(false)
                .spotOrderBook(buildSpotOrderBook(
                        List.of(level("50000", "1")),
                        List.of(level("50010", "0.0005"))
                ))
                .futuresOrderBook(buildFuturesOrderBook(
                        List.of(level("50000", "0.0004")),
                        List.of(level("50010", "1"))
                ))
                .build());

        SingleExchangeLiquidityAssessment assessment = riskService.evaluateLiquidityRisk(buildPlan(), SingleExchangeRiskService.PHASE_OPEN);

        assertFalse(assessment.isPassed());
        assertFalse(assessment.isFallbackUsed());
        assertTrue(assessment.getRejectionReason().contains("order-book depth is insufficient"));
    }

    @Test
    void testRejectOpenWhenEstimatedSlippageExceedsThreshold() throws Exception {
        setProperty("estimatedSlippageRate", new BigDecimal("0.0003"));
        when(depthMonitor.getDepthSnapshot("BTCUSDT")).thenReturn(SingleExchangeDepthSnapshot.builder()
                .fallbackUsed(false)
                .spotOrderBook(buildSpotOrderBook(
                        List.of(level("50000", "1")),
                        List.of(level("50100", "1"))
                ))
                .futuresOrderBook(buildFuturesOrderBook(
                        List.of(level("49900", "1")),
                        List.of(level("50000", "1"))
                ))
                .build());

        SingleExchangeLiquidityAssessment assessment = riskService.evaluateLiquidityRisk(buildPlan(), SingleExchangeRiskService.PHASE_OPEN);

        assertFalse(assessment.isPassed());
        assertTrue(assessment.getRejectionReason().contains("estimated slippage"));
    }

    @Test
    void testUseVwapAcrossMultipleLevelsWhenDepthIsSufficient() {
        when(depthMonitor.getDepthSnapshot("BTCUSDT")).thenReturn(SingleExchangeDepthSnapshot.builder()
                .fallbackUsed(false)
                .spotOrderBook(buildSpotOrderBook(
                        List.of(level("50000", "1")),
                        List.of(level("50010", "0.0006"), level("50020", "0.0006"))
                ))
                .futuresOrderBook(buildFuturesOrderBook(
                        List.of(level("50000", "0.0006"), level("49990", "0.0006")),
                        List.of(level("50010", "1"))
                ))
                .build());

        SingleExchangeLiquidityAssessment assessment = riskService.evaluateLiquidityRisk(buildPlan(), SingleExchangeRiskService.PHASE_OPEN);

        assertTrue(assessment.isPassed());
        assertEquals(new BigDecimal("50014.00000000"), assessment.getSpotExpectedPrice());
        assertEquals(new BigDecimal("49996.00000000"), assessment.getFuturesExpectedPrice());
    }

    @Test
    void testRejectOpenWhenDepthSnapshotFallsBack() {
        when(depthMonitor.getDepthSnapshot("BTCUSDT")).thenReturn(SingleExchangeDepthSnapshot.builder()
                .fallbackUsed(true)
                .build());

        SingleExchangeLiquidityAssessment assessment = riskService.evaluateLiquidityRisk(buildPlan(), SingleExchangeRiskService.PHASE_OPEN);

        assertFalse(assessment.isPassed());
        assertTrue(assessment.isFallbackUsed());
        assertTrue(assessment.getRejectionReason().contains("depth data unavailable"));
    }

    @Test
    void testResolveRoundTripFeeRateSumsBothLegTakerTimesTwo() {
        // 往返手续费 = (现货 taker 0.0004 + 合约 taker 0.0006) × 2 = 0.0020。
        BinanceSpotService spotService = mock(BinanceSpotService.class);
        BinanceFuturesService futuresService = mock(BinanceFuturesService.class);
        when(binanceApiService.getSpotService()).thenReturn(spotService);
        when(binanceApiService.getFuturesService()).thenReturn(futuresService);
        BinanceCommissionResponse spotCommission = BinanceCommissionResponse.builder()
                .symbol("BTCUSDT")
                .standardCommission(new BinanceCommissionResponse.Commission(
                        new BigDecimal("0.0002"), new BigDecimal("0.0004"), null, null))
                .build();
        BinanceFuturesCommissionRate futuresCommission = new BinanceFuturesCommissionRate();
        futuresCommission.setSymbol("BTCUSDT");
        futuresCommission.setTakerCommissionRate(new BigDecimal("0.0006"));
        when(spotService.getCommissionRate("BTCUSDT")).thenReturn(spotCommission);
        when(futuresService.getCommissionRate("BTCUSDT")).thenReturn(futuresCommission);

        BigDecimal roundTripFee = riskService.resolveRoundTripFeeRate("BTCUSDT");

        assertEquals(0, new BigDecimal("0.0020").compareTo(roundTripFee));
    }

    @Test
    void testResolveRoundTripFeeRateFailOpenReturnsNullOnException() {
        // fail-open: 佣金档查询抛异常时返回 null，由调用方回落静态兜底费率(不误拒开仓)。
        when(binanceApiService.getSpotService()).thenThrow(new RuntimeException("commission query failed"));

        assertNull(riskService.resolveRoundTripFeeRate("BTCUSDT"));
    }

    private SingleExchangeSpotFuturesOrderPlan buildPlan() {
        return SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol("BTCUSDT")
                .spotSymbol("BTCUSDT")
                .futuresSymbol("BTCUSDT")
                .quantity(new BigDecimal("0.001"))
                .build();
    }

    private BinanceOrderBook buildSpotOrderBook(List<List<String>> bids, List<List<String>> asks) {
        BinanceOrderBook orderBook = new BinanceOrderBook();
        orderBook.setBids(bids);
        orderBook.setAsks(asks);
        return orderBook;
    }

    private BinanceFuturesOrderBook buildFuturesOrderBook(List<List<String>> bids, List<List<String>> asks) {
        BinanceFuturesOrderBook orderBook = new BinanceFuturesOrderBook();
        orderBook.setBids(bids);
        orderBook.setAsks(asks);
        return orderBook;
    }

    private List<String> level(String price, String quantity) {
        return List.of(price, quantity);
    }

    private void setProperty(String fieldName, Object value) throws Exception {
        Field field = SingleExchangeSpotFuturesProperties.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(properties, value);
    }
}
