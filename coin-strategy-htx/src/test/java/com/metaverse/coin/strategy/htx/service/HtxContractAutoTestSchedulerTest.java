package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxContractTradeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxContractAutoTestSchedulerTest {

    private HtxUsdtSwapService swap;
    private HtxContractTradingService tradingService;
    private HtxContractTradeRecorder recorder;
    private HtxContractProperties props;
    private HtxContractAutoTestScheduler scheduler;

    @BeforeEach
    void setUp() {
        HtxUsdtSwapApiService api = mock(HtxUsdtSwapApiService.class);
        swap = mock(HtxUsdtSwapService.class);
        tradingService = mock(HtxContractTradingService.class);
        recorder = mock(HtxContractTradeRecorder.class);
        props = new HtxContractProperties();
        props.setContractCodes(List.of("XAUT-USDT"));
        props.setLeverage(5);
        props.setMaxLeverage(5);
        props.setAutoTestEnabled(true);
        props.setAutoTestVolume(2L);
        when(api.getSwapService()).thenReturn(swap);
        scheduler = new HtxContractAutoTestScheduler(api, tradingService, recorder, props);
    }

    @Test
    void sizesProfitGridFromPerSideQuoteNotionalAndPersistsAudit() {
        HtxSwapContractInfo contract = new HtxSwapContractInfo();
        contract.setContractCode("XAUT-USDT");
        contract.setContractSize(new BigDecimal("0.001"));
        contract.setPriceTick(new BigDecimal("0.01"));
        HtxDepth depth = new HtxDepth();
        depth.setBids(List.of(new HtxPriceLevel(new BigDecimal("4333.32"), new BigDecimal("100"))));
        depth.setAsks(List.of(new HtxPriceLevel(new BigDecimal("4333.34"), new BigDecimal("100"))));
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract)));
        when(swap.depth("XAUT-USDT", 6)).thenReturn(ok(depth));
        when(tradingService.configureLeverage(any())).thenReturn(HtxContractTradeResult.builder()
                .code(HtxContractTradeResult.Code.LEVERAGE_DRY_RUN_OK).build());
        when(tradingService.submitDualCycle(any())).thenReturn(HtxContractTradeResult.builder()
                .code(HtxContractTradeResult.Code.DRY_RUN_OK).message("dry-run").build());
        when(recorder.recordDryRun(any(), anyString(), anyInt(), anyString())).thenReturn(88L);

        scheduler.runOnce();

        ArgumentCaptor<HtxContractCycleRequest> request = ArgumentCaptor.forClass(HtxContractCycleRequest.class);
        verify(tradingService).submitDualCycle(request.capture());
        assertEquals("XAUT-USDT", request.getValue().getContractCode());
        assertEquals(5L, request.getValue().getVolume());
        assertEquals(5, request.getValue().getLeverage());
        assertEquals(new BigDecimal("4333.32"), request.getValue().getLongOpenPrice());
        assertEquals(new BigDecimal("4333.34"), request.getValue().getShortOpenPrice());
        assertEquals(Boolean.FALSE, request.getValue().getFastIoc());
        assertEquals(Boolean.TRUE, request.getValue().getMakerGrid());
        verify(recorder).recordDryRun(request.getValue(), "XAUT-USDT", 5, "dry-run");
    }

    @Test
    void refusesToRunWhenDryRunGateIsOff() {
        props.setDryRun(false);

        scheduler.runOnce();

        verify(swap, never()).contractInfo(anyString());
        verify(tradingService, never()).submitDualCycle(any());
        verify(recorder, never()).recordDryRun(any(), anyString(), anyInt(), anyString());
    }

    @Test
    void liveAutoRequiresExplicitAutoLiveGateAndDoesNotWriteDryRunAudit() {
        props.setAutoTestEnabled(false);
        props.setAutoLiveEnabled(true);
        props.setDryRun(false);
        props.setLiveEnabled(true);
        HtxSwapContractInfo contract = new HtxSwapContractInfo();
        contract.setContractCode("XAUT-USDT");
        contract.setContractSize(new BigDecimal("0.001"));
        contract.setPriceTick(new BigDecimal("0.01"));
        HtxDepth depth = new HtxDepth();
        depth.setBids(List.of(new HtxPriceLevel(new BigDecimal("4333.32"), new BigDecimal("100"))));
        depth.setAsks(List.of(new HtxPriceLevel(new BigDecimal("4333.34"), new BigDecimal("100"))));
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract)));
        when(swap.depth("XAUT-USDT", 6)).thenReturn(ok(depth));
        when(tradingService.submitDualCycle(any())).thenReturn(HtxContractTradeResult.builder()
                .code(HtxContractTradeResult.Code.OK).cycleId(99L).build());

        scheduler.runOnce();

        verify(tradingService, never()).configureLeverage(any());
        verify(tradingService).submitDualCycle(any());
        verify(recorder, never()).recordDryRun(any(), anyString(), anyInt(), anyString());
    }

    @Test
    void insufficientMarginBackoffSkipsImmediateRetry() {
        props.setAutoTestEnabled(false);
        props.setAutoLiveEnabled(true);
        props.setDryRun(false);
        props.setLiveEnabled(true);
        props.setInsufficientMarginBackoffMs(300_000L);
        HtxSwapContractInfo contract = new HtxSwapContractInfo();
        contract.setContractCode("XAUT-USDT");
        contract.setContractSize(new BigDecimal("0.001"));
        contract.setPriceTick(new BigDecimal("0.01"));
        HtxDepth depth = new HtxDepth();
        depth.setBids(List.of(new HtxPriceLevel(new BigDecimal("4333.32"), new BigDecimal("100"))));
        depth.setAsks(List.of(new HtxPriceLevel(new BigDecimal("4333.34"), new BigDecimal("100"))));
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract)));
        when(swap.depth("XAUT-USDT", 6)).thenReturn(ok(depth));
        when(tradingService.submitDualCycle(any())).thenReturn(HtxContractTradeResult.builder()
                .code(HtxContractTradeResult.Code.REJECT_INSUFFICIENT_MARGIN).build());

        scheduler.runOnce();
        scheduler.runOnce();

        verify(tradingService, times(1)).submitDualCycle(any());
        verify(swap, times(1)).contractInfo("XAUT-USDT");
    }

    @Test
    void activeMakerGridIsReconciledWithoutOpeningAnotherCycle() {
        when(recorder.hasActiveMakerGridCycle("XAUT-USDT")).thenReturn(true);
        when(tradingService.reconcileMakerGridCycle("XAUT-USDT"))
                .thenReturn(HtxContractTradeResult.builder()
                        .code(HtxContractTradeResult.Code.CLOSE_PENDING).cycleId(77L).build());

        scheduler.runOnce();

        verify(tradingService).reconcileMakerGridCycle("XAUT-USDT");
        verify(tradingService, never()).submitDualCycle(any());
        verify(swap, never()).contractInfo(anyString());
    }

    @Test
    void dailyLossCircuitBlocksLiveEntryBeforeMarketDataQueries() {
        props.setAutoTestEnabled(false);
        props.setAutoLiveEnabled(true);
        props.setDryRun(false);
        props.setLiveEnabled(true);
        when(recorder.sumTodayMakerGridNet()).thenReturn(new BigDecimal("-1.00"));

        scheduler.runOnce();

        verify(tradingService, never()).submitDualCycle(any());
        verify(swap, never()).contractInfo(anyString());
    }

    @Test
    void restingMakerExitsDoNotTriggerMarginBackoff() {
        props.setAutoTestEnabled(false);
        props.setAutoLiveEnabled(true);
        props.setDryRun(false);
        props.setLiveEnabled(true);
        HtxSwapContractInfo contract = new HtxSwapContractInfo();
        contract.setContractCode("XAUT-USDT");
        contract.setContractSize(new BigDecimal("0.001"));
        contract.setPriceTick(new BigDecimal("0.01"));
        HtxDepth depth = new HtxDepth();
        depth.setBids(List.of(new HtxPriceLevel(new BigDecimal("4333.32"), new BigDecimal("100"))));
        depth.setAsks(List.of(new HtxPriceLevel(new BigDecimal("4333.34"), new BigDecimal("100"))));
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract)));
        when(swap.depth("XAUT-USDT", 6)).thenReturn(ok(depth));
        when(tradingService.submitDualCycle(any())).thenReturn(HtxContractTradeResult.builder()
                .code(HtxContractTradeResult.Code.CLOSE_PENDING).cycleId(99L).build());

        scheduler.runOnce();

        verify(tradingService).submitDualCycle(any());
    }

    @Test
    void closePriceAlwaysRoundsUpToContractTick() {
        assertEquals(new BigDecimal("4359.35"), HtxContractAutoTestScheduler.roundUpToTick(
                new BigDecimal("4359.34004"), new BigDecimal("0.01")));
    }

    private static <T> HtxRestResponse<T> ok(T data) {
        HtxRestResponse<T> response = new HtxRestResponse<>();
        response.setStatus("ok");
        response.setData(data);
        return response;
    }
}
