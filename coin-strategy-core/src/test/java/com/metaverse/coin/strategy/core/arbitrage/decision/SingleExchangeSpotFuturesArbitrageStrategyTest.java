package com.metaverse.coin.strategy.core.arbitrage.decision;

import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageOrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeFundingRateMonitor;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SingleExchangeSpotFuturesArbitrageStrategyTest {

    @Mock
    private SingleExchangeFundingRateMonitor fundingRateMonitor;

    @Mock
    private SingleExchangeArbitrageOrderExecutor orderExecutor;

    @Mock
    private SingleExchangeRiskController riskController;

    @Mock
    private SingleExchangeArbitragePositionMapper positionMapper;

    @Spy
    private SingleExchangeSpotFuturesProperties properties = new SingleExchangeSpotFuturesProperties();

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

    @SuppressWarnings("unchecked")
    private Map<String, ?> getActivePositions() throws Exception {
        Field field = SingleExchangeSpotFuturesArbitrageStrategy.class.getDeclaredField("activePositions");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(strategy);
    }

    private void restorePersistedPositions() throws Exception {
        Method method = SingleExchangeSpotFuturesArbitrageStrategy.class.getDeclaredMethod("restorePersistedPositions");
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

        doAnswer(invocation -> {
            SingleExchangeArbitragePosition position = invocation.getArgument(0);
            position.setId(1L);
            return 1;
        }).when(positionMapper).insert(any(SingleExchangeArbitragePosition.class));
        when(positionMapper.updateById(any(SingleExchangeArbitragePosition.class))).thenReturn(1);
        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(index);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), any())).thenReturn(plan);
        when(riskController.checkTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class))).thenReturn(true);
        when(orderExecutor.openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(SingleExchangeArbitrageExecutionResult.success("BTCUSDT", "0.001", "1", "2"));

        setStrategyRunning(true);
        executeStrategyLoop();

        verify(riskController, times(1)).checkTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(orderExecutor, times(1)).openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(positionMapper, times(1)).insert(any(SingleExchangeArbitragePosition.class));
        verify(positionMapper, atLeastOnce()).updateById(any(SingleExchangeArbitragePosition.class));
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

        doAnswer(invocation -> {
            SingleExchangeArbitragePosition position = invocation.getArgument(0);
            position.setId(2L);
            return 1;
        }).when(positionMapper).insert(any(SingleExchangeArbitragePosition.class));
        when(positionMapper.selectById(2L)).thenAnswer(invocation -> new SingleExchangeArbitragePosition().setId(2L));
        when(positionMapper.updateById(any(SingleExchangeArbitragePosition.class))).thenReturn(1);
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
        ArgumentCaptor<SingleExchangeArbitragePosition> captor = ArgumentCaptor.forClass(SingleExchangeArbitragePosition.class);
        verify(positionMapper, atLeastOnce()).updateById(captor.capture());
        assertFalse(captor.getAllValues().isEmpty());
        assertEquals("OPENED", captor.getAllValues().get(0).getStatus());
        assertEquals("CLOSED", captor.getAllValues().get(captor.getAllValues().size() - 1).getStatus());
    }

    @Test
    public void testRestorePersistedOpenPosition() throws Exception {
        SingleExchangeArbitragePosition persisted = new SingleExchangeArbitragePosition()
                .setId(9L)
                .setPositionCode("SEA-BTCUSDT-RESTORED")
                .setSymbol("BTCUSDT")
                .setStrategyType("SINGLE_EXCHANGE_SPOT_FUTURES_ARBITRAGE_V1")
                .setStatus("OPENED")
                .setQuantity(new BigDecimal("0.001"))
                .setEntryBasisRate(new BigDecimal("0.0004"))
                .setEntryFundingRate(new BigDecimal("0.0006"))
                .setLeverage(3)
                .setEntryNextFundingTime(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));

        when(positionMapper.selectList(any())).thenReturn(List.of(persisted));

        restorePersistedPositions();

        assertEquals(1, getActivePositions().size());
        assertFalse(getActivePositions().isEmpty());
    }
}
