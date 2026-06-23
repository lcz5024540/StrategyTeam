package com.metaverse.coin.strategy.job.service;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageOrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.market.BinanceQueryDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskService;
import com.metaverse.coin.strategy.job.funding.service.FundingArbLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BinanceSingleExchangeFundingRateArbitrageServiceTest {

    @Mock
    private BinanceApiService binanceApiService;

    @Mock
    private SingleExchangeArbitrageOrderExecutor orderExecutor;

    @Mock
    private SingleExchangeRiskService riskService;

    @Mock
    private FundingArbLifecycleService lifecycleService;

    @Mock
    private BinanceQueryDataProvider queryDataProvider;

    @InjectMocks
    private BinanceSingleExchangeFundingRateArbitrageService arbitrageService;

    @Test
    void shouldExecuteMultipleSymbolsWhenFundingRateAndRiskPass() {
        BinanceFuturesPremiumIndex btcRate = BinanceFuturesPremiumIndex.builder()
                .symbol("BTCUSDT")
                .lastFundingRate(new BigDecimal("0.0010"))
                .build();
        BinanceFuturesPremiumIndex ethRate = BinanceFuturesPremiumIndex.builder()
                .symbol("ETHUSDT")
                .lastFundingRate(new BigDecimal("0.0012"))
                .build();

        SingleExchangeSpotFuturesOrderPlan btcPlan = SingleExchangeSpotFuturesOrderPlan.builder()
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
        SingleExchangeSpotFuturesOrderPlan ethPlan = SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol("ETHUSDT")
                .spotSymbol("ETHUSDT")
                .futuresSymbol("ETHUSDT")
                .quantity(new BigDecimal("0.02"))
                .spotPrice(new BigDecimal("2500"))
                .futuresPrice(new BigDecimal("2505"))
                .spotNotional(new BigDecimal("50"))
                .futuresNotional(new BigDecimal("50.10"))
                .leverage(3)
                .build();

        when(queryDataProvider.getPremiumIndex("BTCUSDT")).thenReturn(btcRate);
        when(queryDataProvider.getPremiumIndex("ETHUSDT")).thenReturn(ethRate);
        when(orderExecutor.createSpotFuturesPlan("BTCUSDT", new BigDecimal("50"), 3)).thenReturn(btcPlan);
        when(orderExecutor.createSpotFuturesPlan("ETHUSDT", new BigDecimal("50"), 3)).thenReturn(ethPlan);
        when(riskService.checkTradeRisk(btcPlan)).thenReturn(true);
        when(riskService.checkTradeRisk(ethPlan)).thenReturn(true);
        when(orderExecutor.openArbitragePosition(btcPlan)).thenReturn(
                SingleExchangeArbitrageExecutionResult.success("BTCUSDT", "0.001", "1", "2"));
        when(orderExecutor.openArbitragePosition(ethPlan)).thenReturn(
                SingleExchangeArbitrageExecutionResult.success("ETHUSDT", "0.02", "3", "4"));

        String result = arbitrageService.executeArbitrage(List.of("BTCUSDT", "ETHUSDT"), new BigDecimal("50"), new BigDecimal("0.0005"), 3);

        verify(orderExecutor).createSpotFuturesPlan("BTCUSDT", new BigDecimal("50"), 3);
        verify(orderExecutor).createSpotFuturesPlan("ETHUSDT", new BigDecimal("50"), 3);
        verify(orderExecutor).openArbitragePosition(btcPlan);
        verify(orderExecutor).openArbitragePosition(ethPlan);
        assertTrue(result.contains("BTCUSDT -> Opened"));
        assertTrue(result.contains("ETHUSDT -> Opened"));
    }

    @Test
    void shouldSkipWhenFundingRateBelowThreshold() {
        BinanceFuturesPremiumIndex lowRate = BinanceFuturesPremiumIndex.builder()
                .symbol("BTCUSDT")
                .lastFundingRate(new BigDecimal("0.0001"))
                .build();
        when(queryDataProvider.getPremiumIndex("BTCUSDT")).thenReturn(lowRate);

        String result = arbitrageService.executeArbitrage(List.of("BTCUSDT"), new BigDecimal("50"), new BigDecimal("0.0005"), 3);

        verify(orderExecutor, never()).createSpotFuturesPlan(eq("BTCUSDT"), any(), any());
        assertTrue(result.contains("Skipped: funding rate below threshold"));
    }
}
