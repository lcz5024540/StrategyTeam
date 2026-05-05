package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageFundingSettlement;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeArbitrageOrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeLiquidityAssessment;
import com.metaverse.coin.strategy.core.arbitrage.risk.SingleExchangeRiskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FundingArbLifecycleServiceTest {

    @Mock
    private BinanceApiService binanceApiService;
    @Mock
    private SingleExchangeArbitrageOrderExecutor orderExecutor;
    @Mock
    private SingleExchangeRiskService riskService;
    @Mock
    private SingleExchangeArbitragePositionMapper positionMapper;
    @Mock
    private SingleExchangeArbitrageOrderMapper orderMapper;
    @Mock
    private SingleExchangeArbitrageFundingSettlementMapper fundingSettlementMapper;

    private FundingArbLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new FundingArbLifecycleService(
                binanceApiService,
                orderExecutor,
                riskService,
                positionMapper,
                orderMapper,
                fundingSettlementMapper,
                new SingleExchangeSpotFuturesProperties()
        );
    }

    @Test
    void persistOpenResultMarksPositionOpenedAndStoresOrders() {
        SingleExchangeArbitragePosition position = position();
        SingleExchangeSpotFuturesOrderPlan plan = plan("0.0004");
        BinanceFuturesPremiumIndex premiumIndex = premiumIndex("0.0006", System.currentTimeMillis() + 3_600_000L);
        SingleExchangeArbitrageExecutionResult result = SingleExchangeArbitrageExecutionResult.builder()
                .success(true)
                .spotOrderId("101")
                .futuresOrderId("202")
                .orderRecords(List.of(order("SPOT"), order("FUTURES")))
                .message("Success")
                .build();

        service.persistOpenResult(position, plan, premiumIndex, result);

        verify(orderMapper, times(2)).insert(any(SingleExchangeArbitrageOrder.class));
        ArgumentCaptor<SingleExchangeArbitragePosition> captor = ArgumentCaptor.forClass(SingleExchangeArbitragePosition.class);
        verify(positionMapper).updateById(captor.capture());
        assertEquals(FundingArbLifecycleService.STATUS_OPENED, captor.getValue().getStatus());
        assertEquals("101", captor.getValue().getSpotBuyOrderId());
        assertEquals("202", captor.getValue().getFuturesSellOrderId());
    }

    @Test
    void persistOpenResultMarksFailedButKeepsOrderRecords() {
        SingleExchangeArbitragePosition position = position();
        SingleExchangeArbitrageExecutionResult result = SingleExchangeArbitrageExecutionResult.builder()
                .success(false)
                .message("Both orders failed.")
                .orderRecords(List.of(order("SPOT"), order("FUTURES")))
                .build();
        when(positionMapper.selectById(7L)).thenReturn(position);

        service.persistOpenResult(position, plan("0.0004"), premiumIndex("0.0006", null), result);

        verify(orderMapper, times(2)).insert(any(SingleExchangeArbitrageOrder.class));
        ArgumentCaptor<SingleExchangeArbitragePosition> captor = ArgumentCaptor.forClass(SingleExchangeArbitragePosition.class);
        verify(positionMapper).updateById(captor.capture());
        assertEquals(FundingArbLifecycleService.STATUS_FAILED, captor.getValue().getStatus());
        assertEquals("Both orders failed.", captor.getValue().getErrorMessage());
    }

    @Test
    void trackPositionRecordsFundingSettlementOnceWhenWindowAdvances() {
        SingleExchangeArbitragePosition position = position()
                .setEntryNextFundingTime(System.currentTimeMillis() - 1_000L);
        BinanceFuturesPremiumIndex premiumIndex = premiumIndex("0.0006", System.currentTimeMillis() + 28_800_000L);
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(binanceApiService.getFuturesFundingRate("BTCUSDT")).thenReturn(premiumIndex);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), eq(3)))
                .thenReturn(plan("0.0004"));
        when(fundingSettlementMapper.selectCount(any())).thenReturn(0L);
        when(positionMapper.selectById(7L)).thenReturn(position);
        when(riskService.getMarginRatio()).thenReturn(new BigDecimal("3.0"));

        service.trackPosition(position);

        verify(fundingSettlementMapper).insert(any(SingleExchangeArbitrageFundingSettlement.class));
        verify(positionMapper).updateById(argThat(row ->
                row.getEntryNextFundingTime().equals(premiumIndex.getNextFundingTime())
                        && row.getFundingFeesEarned().compareTo(BigDecimal.ZERO) > 0
        ));
    }

    @Test
    void trackPositionClosesWhenFundingDropsBelowThreshold() {
        SingleExchangeArbitragePosition position = position()
                .setEntryNextFundingTime(System.currentTimeMillis() + 3_600_000L);
        BinanceFuturesPremiumIndex premiumIndex = premiumIndex("0.00001", System.currentTimeMillis() + 28_800_000L);
        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(binanceApiService.getFuturesFundingRate("BTCUSDT")).thenReturn(premiumIndex);
        when(orderExecutor.createSpotFuturesPlan(eq("BTCUSDT"), any(BigDecimal.class), eq(3)))
                .thenReturn(plan("0.0004"));
        when(riskService.evaluateLiquidityRisk(any(SingleExchangeSpotFuturesOrderPlan.class), eq(SingleExchangeRiskService.PHASE_CLOSE)))
                .thenReturn(SingleExchangeLiquidityAssessment.builder().passed(true).build());
        when(positionMapper.selectById(7L)).thenReturn(position);
        when(orderExecutor.closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class)))
                .thenReturn(SingleExchangeArbitrageExecutionResult.builder()
                        .success(true)
                        .spotOrderId("303")
                        .futuresOrderId("404")
                        .orderRecords(List.of(order("SPOT"), order("FUTURES")))
                        .message("Success")
                        .build());

        service.trackPosition(position);

        verify(orderExecutor).closeArbitragePosition(any(SingleExchangeSpotFuturesOrderPlan.class));
        verify(positionMapper, atLeastOnce()).updateById(argThat(row ->
                FundingArbLifecycleService.STATUS_CLOSED.equals(row.getStatus())
                        && "303".equals(row.getSpotSellOrderId())
                        && "404".equals(row.getFuturesBuyOrderId())
        ));
    }

    @Test
    void createOpeningPositionPopulatesStrategyIdentity() {
        doAnswer(invocation -> {
            SingleExchangeArbitragePosition inserted = invocation.getArgument(0);
            inserted.setId(9L);
            return 1;
        }).when(positionMapper).insert(any(SingleExchangeArbitragePosition.class));

        SingleExchangeArbitragePosition position = service.createOpeningPosition(
                plan("0.0004"),
                premiumIndex("0.0006", 123L),
                new BigDecimal("50"),
                3
        );

        assertNotNull(position.getPositionCode());
        assertEquals(9L, position.getId());
        assertEquals(FundingArbLifecycleService.STRATEGY_ID, position.getStrategyType());
        assertEquals(FundingArbLifecycleService.EXCHANGE_NAME, position.getExchangeName());
        assertEquals(FundingArbLifecycleService.STATUS_OPENING, position.getStatus());
    }

    private SingleExchangeArbitragePosition position() {
        return new SingleExchangeArbitragePosition()
                .setId(7L)
                .setPositionCode("SEA-BTCUSDT-test")
                .setExchangeName(FundingArbLifecycleService.EXCHANGE_NAME)
                .setSymbol("BTCUSDT")
                .setSpotSymbol("BTCUSDT")
                .setFuturesSymbol("BTCUSDT")
                .setStrategyType(FundingArbLifecycleService.STRATEGY_ID)
                .setStatus(FundingArbLifecycleService.STATUS_OPENED)
                .setLeverage(3)
                .setPlannedUsdtAmount(new BigDecimal("50"))
                .setQuantity(new BigDecimal("0.01"))
                .setEntrySpotPrice(new BigDecimal("100"))
                .setEntryFuturesPrice(new BigDecimal("101"))
                .setEntryBasisRate(new BigDecimal("0.0001"))
                .setFundingFeesEarned(BigDecimal.ZERO)
                .setRealizedPnl(BigDecimal.ZERO);
    }

    private SingleExchangeSpotFuturesOrderPlan plan(String basisRate) {
        return SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol("BTCUSDT")
                .spotSymbol("BTCUSDT")
                .futuresSymbol("BTCUSDT")
                .quantity(new BigDecimal("0.01"))
                .spotPrice(new BigDecimal("100"))
                .futuresPrice(new BigDecimal("101"))
                .spotNotional(new BigDecimal("1.00"))
                .futuresNotional(new BigDecimal("1.01"))
                .basisSpread(new BigDecimal("1"))
                .basisRate(new BigDecimal(basisRate))
                .leverage(3)
                .build();
    }

    private BinanceFuturesPremiumIndex premiumIndex(String fundingRate, Long nextFundingTime) {
        return BinanceFuturesPremiumIndex.builder()
                .symbol("BTCUSDT")
                .lastFundingRate(new BigDecimal(fundingRate))
                .markPrice(new BigDecimal("101"))
                .nextFundingTime(nextFundingTime)
                .build();
    }

    private SingleExchangeArbitrageOrder order(String marketType) {
        return new SingleExchangeArbitrageOrder()
                .setExchangeName(FundingArbLifecycleService.EXCHANGE_NAME)
                .setSymbol("BTCUSDT")
                .setMarketType(marketType)
                .setTradePhase("OPEN")
                .setStatus("FILLED");
    }
}
