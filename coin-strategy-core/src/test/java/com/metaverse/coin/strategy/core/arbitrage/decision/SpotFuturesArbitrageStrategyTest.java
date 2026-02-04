package com.metaverse.coin.strategy.core.arbitrage.decision;

import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.arbitrage.execution.ArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.OrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.monitor.FundingRateMonitor;
import com.metaverse.coin.strategy.core.arbitrage.risk.RiskController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SpotFuturesArbitrageStrategyTest {

    @Mock
    private FundingRateMonitor fundingRateMonitor;

    @Mock
    private OrderExecutor orderExecutor;

    @Mock
    private RiskController riskController;

    @InjectMocks
    private SpotFuturesArbitrageStrategy strategy;

    @BeforeEach
    public void setup() {
        // Reset mocks
    }

    private void setStrategyRunning(boolean isRunning) throws Exception {
        Field runningField = SpotFuturesArbitrageStrategy.class.getDeclaredField("running");
        runningField.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) runningField.get(strategy);
        running.set(isRunning);
    }

    @Test
    public void testOpenPositionLogic() throws Exception {
        // Mock Data
        BinanceFuturesPremiumIndex index = new BinanceFuturesPremiumIndex();
        index.setSymbol("BTCUSDT");
        index.setLastFundingRate(new BigDecimal("0.0006")); // > 0.0005 Threshold
        index.setMarkPrice(new BigDecimal("50000"));

        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(index);
        when(riskController.checkTradeRisk(eq("BTCUSDT"), any(), any())).thenReturn(true);
        when(orderExecutor.openArbitragePosition(eq("BTCUSDT"), eq("BTCUSDT"), any()))
                .thenReturn(ArbitrageExecutionResult.success("1", "2"));

        // Set running = true without starting scheduler
        setStrategyRunning(true);

        // Invoke private method via reflection
        Method method = SpotFuturesArbitrageStrategy.class.getDeclaredMethod("executeLogic");
        method.setAccessible(true);
        method.invoke(strategy);

        // Verify
        verify(orderExecutor, times(1)).openArbitragePosition(eq("BTCUSDT"), eq("BTCUSDT"), any());
    }

    @Test
    public void testRiskRejection() throws Exception {
        // Mock Data
        BinanceFuturesPremiumIndex index = new BinanceFuturesPremiumIndex();
        index.setSymbol("BTCUSDT");
        index.setLastFundingRate(new BigDecimal("0.0006")); // > 0.0005 Threshold
        index.setMarkPrice(new BigDecimal("50000"));

        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(index);
        when(riskController.checkTradeRisk(eq("BTCUSDT"), any(), any())).thenReturn(false); // Risk Rejected

        // Set running = true without starting scheduler
        setStrategyRunning(true);

        // Invoke logic
        Method method = SpotFuturesArbitrageStrategy.class.getDeclaredMethod("executeLogic");
        method.setAccessible(true);
        method.invoke(strategy);

        // Verify NO order executed
        verify(orderExecutor, never()).openArbitragePosition(any(), any(), any());
    }
}
