package com.metaverse.coin.strategy.core.arbitrage.decision;

import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageOrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeFundingRateMonitor;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SingleExchangeSpotFuturesArbitrageStrategyTest {

    @Mock
    private SingleExchangeFundingRateMonitor fundingRateMonitor;

    @Mock
    private SingleExchangeArbitrageOrderExecutor orderExecutor;

    @Mock
    private SingleExchangeRiskController riskController;

    @InjectMocks
    private SingleExchangeSpotFuturesArbitrageStrategy strategy;

    @BeforeEach
    public void setup() {
        // Use field defaults on the strategy under test.
    }

    private void setStrategyRunning(boolean isRunning) throws Exception {
        Field runningField = SingleExchangeSpotFuturesArbitrageStrategy.class.getDeclaredField("running");
        runningField.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) runningField.get(strategy);
        running.set(isRunning);
    }

    private void executeStrategyLoop() throws Exception {
        Method method = SingleExchangeSpotFuturesArbitrageStrategy.class.getDeclaredMethod("executeLogic");
        method.setAccessible(true);
        method.invoke(strategy);
    }

    private BinanceFuturesPremiumIndex buildFundingIndex(String fundingRate, long nextFundingTime) {
        BinanceFuturesPremiumIndex index = new BinanceFuturesPremiumIndex();
        index.setSymbol("BTCUSDT");
        index.setLastFundingRate(new BigDecimal(fundingRate));
        index.setMarkPrice(new BigDecimal("50000"));
        index.setNextFundingTime(nextFundingTime);
        return index;
    }

    private SingleExchangeSpotFuturesOrderPlan buildPlan(String basisRate) {
        return SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol("BTCUSDT")
                .spotSymbol("BTCUSDT")
                .futuresSymbol("BTCUSDT")
                .quantity(new BigDecimal("0.001"))
                .spotPrice(new BigDecimal("50000"))
                .futuresPrice(new BigDecimal("50020"))
                .basisSpread(new BigDecimal("20"))
                .basisRate(new BigDecimal(basisRate))
                .spotNotional(new BigDecimal("50"))
                .futuresNotional(new BigDecimal("50.02"))
                .leverage(3)
                .build();
    }

    @Test
    public void testOpenPositionLogic() throws Exception {
        BinanceFuturesPremiumIndex index = buildFundingIndex(
                "0.0006",
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20)
        );
        SingleExchangeSpotFuturesOrderPlan plan = buildPlan("0.0004");

        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(index);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), any())).thenReturn(plan);
        when(riskController.checkTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class))).thenReturn(true);
        when(orderExecutor.openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(SingleExchangeArbitrageExecutionResult.success("BTCUSDT", "0.001", "1", "2"));

        setStrategyRunning(true);
        executeStrategyLoop();

        verify(riskController, times(1)).checkTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(orderExecutor, times(1)).openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
    }

    @Test
    public void testSkipOpenNearSettlementWindow() throws Exception {
        BinanceFuturesPremiumIndex index = buildFundingIndex(
                "0.0008",
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2)
        );
        SingleExchangeSpotFuturesOrderPlan plan = buildPlan("0.0004");

        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(index);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), any())).thenReturn(plan);

        setStrategyRunning(true);
        executeStrategyLoop();

        verify(riskController, never()).checkTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(orderExecutor, never()).openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
    }

    @Test
    public void testClosePositionWhenFundingTurnsNegative() throws Exception {
        BinanceFuturesPremiumIndex openIndex = buildFundingIndex(
                "0.0006",
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20)
        );
        BinanceFuturesPremiumIndex closeIndex = buildFundingIndex(
                "-0.0001",
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)
        );
        SingleExchangeSpotFuturesOrderPlan plan = buildPlan("0.0004");

        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(openIndex, closeIndex);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), any())).thenReturn(plan, plan);
        when(riskController.checkTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class))).thenReturn(true);
        when(orderExecutor.openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(SingleExchangeArbitrageExecutionResult.success("BTCUSDT", "0.001", "1", "2"));
        when(orderExecutor.closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class))).thenReturn(true);

        setStrategyRunning(true);
        executeStrategyLoop();
        executeStrategyLoop();

        verify(orderExecutor, times(1)).openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(orderExecutor, times(1)).closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
    }
}
