package com.metaverse.coin.strategy.core.arbitrage.decision;

import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageRiskSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageRiskSnapshot;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageOrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeFundingRateMonitor;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeLiquidityAssessment;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskController;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskSnapshotData;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeTradeRiskAssessment;
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

    @Mock
    private SingleExchangeArbitrageOrderMapper orderMapper;

    @Mock
    private SingleExchangeArbitrageRiskSnapshotMapper riskSnapshotMapper;

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

    private SingleExchangeLiquidityAssessment buildLiquidityAssessment(boolean passed,
                                                                       boolean fallbackUsed,
                                                                       String phase,
                                                                       String rejectionReason,
                                                                       String slippageRate) {
        return SingleExchangeLiquidityAssessment.builder()
                .passed(passed)
                .fallbackUsed(fallbackUsed)
                .phase(phase)
                .estimatedSlippageRate(new BigDecimal(slippageRate))
                .rejectionReason(rejectionReason)
                .spotTopLevelEnough(true)
                .futuresTopLevelEnough(true)
                .build();
    }

    private SingleExchangeArbitrageExecutionResult buildExecutionResult(String symbol,
                                                                       String quantity,
                                                                       String spotOrderId,
                                                                       String futuresOrderId) {
        return SingleExchangeArbitrageExecutionResult.builder()
                .success(true)
                .symbol(symbol)
                .quantity(quantity)
                .spotOrderId(spotOrderId)
                .futuresOrderId(futuresOrderId)
                .message("Success")
                .orderRecords(List.of(
                        new SingleExchangeArbitrageOrder().setTradePhase("OPEN").setHedgeSide("SPOT"),
                        new SingleExchangeArbitrageOrder().setTradePhase("OPEN").setHedgeSide("FUTURES")
                ))
                .build();
    }

    private SingleExchangeTradeRiskAssessment buildTradeRiskAssessment(boolean passed, String rejectionReason) {
        return SingleExchangeTradeRiskAssessment.builder()
                .passed(passed)
                .rejectionReason(rejectionReason)
                .snapshotData(SingleExchangeRiskSnapshotData.builder()
                        .spotUsdtBalance(new BigDecimal("500"))
                        .futuresAvailableBalance(new BigDecimal("80"))
                        .requiredMargin(new BigDecimal("16.67"))
                        .bufferedRequiredMargin(new BigDecimal("25.01"))
                        .marginRatio(new BigDecimal("2.50"))
                        .build())
                .build();
    }

    private SingleExchangeRiskSnapshotData buildRiskSnapshotData(String marginRatio) {
        return SingleExchangeRiskSnapshotData.builder()
                .spotUsdtBalance(new BigDecimal("500"))
                .futuresAvailableBalance(new BigDecimal("80"))
                .requiredMargin(new BigDecimal("16.67"))
                .bufferedRequiredMargin(new BigDecimal("25.01"))
                .marginRatio(new BigDecimal(marginRatio))
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
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_OPEN)))
                .thenReturn(buildLiquidityAssessment(true, false, SingleExchangeRiskController.PHASE_OPEN, null, "0.0004"));
        when(riskController.evaluateTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildTradeRiskAssessment(true, null));
        when(orderExecutor.openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildExecutionResult("BTCUSDT", "0.001", "1", "2"));

        setStrategyRunning(true);
        executeStrategyLoop();

        verify(riskController, times(1)).evaluateTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(orderExecutor, times(1)).openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(positionMapper, times(1)).insert(any(SingleExchangeArbitragePosition.class));
        verify(positionMapper, atLeastOnce()).updateById(any(SingleExchangeArbitragePosition.class));
        verify(orderMapper, atLeastOnce()).insert(any());
        verify(riskSnapshotMapper).insert(argThat(snapshot ->
                "PRE_OPEN".equals(snapshot.getCheckStage())
                        && Boolean.TRUE.equals(snapshot.getRiskPassed())
                        && Long.valueOf(1L).equals(snapshot.getPositionId())));
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
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_OPEN)))
                .thenReturn(buildLiquidityAssessment(true, false, SingleExchangeRiskController.PHASE_OPEN, null, "0.0004"));

        setStrategyRunning(true);
        executeStrategyLoop();

        verify(riskController, never()).evaluateTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class));
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
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_OPEN)))
                .thenReturn(buildLiquidityAssessment(true, false, SingleExchangeRiskController.PHASE_OPEN, null, "0.0004"));
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_CLOSE)))
                .thenReturn(buildLiquidityAssessment(true, false, SingleExchangeRiskController.PHASE_CLOSE, null, "0.0004"));
        when(riskController.evaluateTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildTradeRiskAssessment(true, null));
        when(riskController.captureRiskSnapshotData(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildRiskSnapshotData("2.50"));
        when(orderExecutor.openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildExecutionResult("BTCUSDT", "0.001", "1", "2"));
        when(orderExecutor.closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildExecutionResult("BTCUSDT", "0.001", "3", "4"));

        setStrategyRunning(true);
        executeStrategyLoop();
        executeStrategyLoop();

        verify(orderExecutor, times(1)).openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(orderExecutor, times(1)).closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(riskSnapshotMapper, atLeastOnce()).insert(argThat(snapshot ->
                "PRE_CLOSE".equals(snapshot.getCheckStage())
                        && Boolean.TRUE.equals(snapshot.getRiskPassed())
                        && Long.valueOf(2L).equals(snapshot.getPositionId())));
        ArgumentCaptor<SingleExchangeArbitragePosition> captor = ArgumentCaptor.forClass(SingleExchangeArbitragePosition.class);
        verify(positionMapper, atLeastOnce()).updateById(captor.capture());
        verify(orderMapper, atLeastOnce()).insert(any());
        verify(riskSnapshotMapper, atLeast(2)).insert(any(SingleExchangeArbitrageRiskSnapshot.class));
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

    @Test
    public void testRejectOpenWhenLiquidityRiskFails() throws Exception {
        BinanceFuturesPremiumIndex index = buildFundingIndex(
                "0.0006",
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20)
        );
        SingleExchangeSpotFuturesOrderPlan plan = buildPlan("0.0004");

        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(index);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), any())).thenReturn(plan);
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_OPEN)))
                .thenReturn(buildLiquidityAssessment(false, false, SingleExchangeRiskController.PHASE_OPEN, "order-book depth is insufficient", "0.0004"));
        when(riskController.captureRiskSnapshotData(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildRiskSnapshotData("2.50"));

        setStrategyRunning(true);
        executeStrategyLoop();

        verify(riskController, never()).evaluateTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(orderExecutor, never()).openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(riskSnapshotMapper).insert(argThat(snapshot ->
                "PRE_OPEN".equals(snapshot.getCheckStage())
                        && Boolean.FALSE.equals(snapshot.getRiskPassed())
                        && snapshot.getRejectionReason().contains("LIQUIDITY_REJECTED")));
    }

    @Test
    public void testRejectOpenWhenLiquidityDataIsUnavailable() throws Exception {
        BinanceFuturesPremiumIndex index = buildFundingIndex(
                "0.0006",
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20)
        );
        SingleExchangeSpotFuturesOrderPlan plan = buildPlan("0.0004");

        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(index);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), any())).thenReturn(plan);
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_OPEN)))
                .thenReturn(buildLiquidityAssessment(false, true, SingleExchangeRiskController.PHASE_OPEN, "depth data unavailable after retries", "0.0005"));
        when(riskController.captureRiskSnapshotData(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildRiskSnapshotData("2.50"));

        setStrategyRunning(true);
        executeStrategyLoop();

        verify(riskController, never()).evaluateTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(orderExecutor, never()).openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(riskSnapshotMapper).insert(argThat(snapshot ->
                "PRE_OPEN".equals(snapshot.getCheckStage())
                        && Boolean.FALSE.equals(snapshot.getRiskPassed())
                        && snapshot.getRejectionReason().contains("LIQUIDITY_REJECTED")));
    }

    @Test
    public void testCloseWhenLiquidityRiskFailsOnNormalClose() throws Exception {
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
            position.setId(4L);
            return 1;
        }).when(positionMapper).insert(any(SingleExchangeArbitragePosition.class));
        when(positionMapper.selectById(4L)).thenAnswer(invocation -> new SingleExchangeArbitragePosition().setId(4L));
        when(positionMapper.updateById(any(SingleExchangeArbitragePosition.class))).thenReturn(1);
        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(openIndex, closeIndex);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), any())).thenReturn(plan, plan);
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_OPEN)))
                .thenReturn(buildLiquidityAssessment(true, false, SingleExchangeRiskController.PHASE_OPEN, null, "0.0004"));
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_CLOSE)))
                .thenReturn(buildLiquidityAssessment(false, false, SingleExchangeRiskController.PHASE_CLOSE, "slippage too high", "0.0008"));
        when(riskController.evaluateTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildTradeRiskAssessment(true, null));
        when(riskController.captureRiskSnapshotData(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildRiskSnapshotData("2.50"));
        when(orderExecutor.openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildExecutionResult("BTCUSDT", "0.001", "1", "2"));
        when(orderExecutor.closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildExecutionResult("BTCUSDT", "0.001", "3", "4"));

        setStrategyRunning(true);
        executeStrategyLoop();
        executeStrategyLoop();

        verify(orderExecutor, times(1)).openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(orderExecutor, times(1)).closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(riskSnapshotMapper, atLeastOnce()).insert(argThat(snapshot ->
                "PRE_CLOSE".equals(snapshot.getCheckStage())
                        && Boolean.FALSE.equals(snapshot.getRiskPassed())
                        && Long.valueOf(4L).equals(snapshot.getPositionId())));
    }

    @Test
    public void testEmergencyCloseStillExecutesWhenLiquidityRiskFails() throws Exception {
        BinanceFuturesPremiumIndex openIndex = buildFundingIndex(
                "0.0006",
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20)
        );
        BinanceFuturesPremiumIndex closeIndex = buildFundingIndex(
                "0.0006",
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)
        );
        SingleExchangeSpotFuturesOrderPlan plan = buildPlan("0.0004");

        doAnswer(invocation -> {
            SingleExchangeArbitragePosition position = invocation.getArgument(0);
            position.setId(5L);
            return 1;
        }).when(positionMapper).insert(any(SingleExchangeArbitragePosition.class));
        when(positionMapper.selectById(5L)).thenAnswer(invocation -> new SingleExchangeArbitragePosition().setId(5L));
        when(positionMapper.updateById(any(SingleExchangeArbitragePosition.class))).thenReturn(1);
        when(fundingRateMonitor.getBinanceFundingRate("BTCUSDT")).thenReturn(openIndex, closeIndex);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), any())).thenReturn(plan, plan);
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_OPEN)))
                .thenReturn(buildLiquidityAssessment(true, false, SingleExchangeRiskController.PHASE_OPEN, null, "0.0004"));
        when(riskController.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskController.PHASE_CLOSE)))
                .thenReturn(buildLiquidityAssessment(false, false, SingleExchangeRiskController.PHASE_CLOSE, "slippage too high", "0.0008"));
        when(riskController.evaluateTradeRisk(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildTradeRiskAssessment(true, null));
        when(riskController.captureRiskSnapshotData(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildRiskSnapshotData("1.70"));
        when(riskController.getMarginRatio()).thenReturn(new BigDecimal("1.70"));
        when(orderExecutor.openArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildExecutionResult("BTCUSDT", "0.001", "1", "2"));
        when(orderExecutor.closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(buildExecutionResult("BTCUSDT", "0.001", "3", "4"));

        setStrategyRunning(true);
        executeStrategyLoop();
        executeStrategyLoop();

        verify(orderExecutor, times(1)).closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
    }

    @Test
    public void testEstimateExpectedNetYieldUsesProvidedSlippage() throws Exception {
        Method method = SingleExchangeSpotFuturesArbitrageStrategy.class.getDeclaredMethod(
                "estimateExpectedNetYield",
                BigDecimal.class,
                BigDecimal.class
        );
        method.setAccessible(true);

        BigDecimal result = (BigDecimal) method.invoke(strategy, new BigDecimal("0.0006"), new BigDecimal("0.0002"));

        assertEquals(new BigDecimal("0.00120000"), result);
    }
}
