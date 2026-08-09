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
        props.setLeverage(50);
        props.setMaxLeverage(50);
        props.setAutoTestVolume(2L);
        when(api.getSwapService()).thenReturn(swap);
        scheduler = new HtxContractAutoTestScheduler(api, tradingService, recorder, props);
    }

    @Test
    void simulatesXautOpenCloseAt50xAndPersistsAudit() {
        HtxSwapContractInfo contract = new HtxSwapContractInfo();
        contract.setContractCode("XAUT-USDT");
        contract.setPriceTick(new BigDecimal("0.01"));
        HtxDepth depth = new HtxDepth();
        depth.setAsks(List.of(new HtxPriceLevel(new BigDecimal("4333.34"), new BigDecimal("100"))));
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract)));
        when(swap.depth("XAUT-USDT", 6)).thenReturn(ok(depth));
        when(tradingService.configureLeverage(any())).thenReturn(HtxContractTradeResult.builder()
                .code(HtxContractTradeResult.Code.LEVERAGE_DRY_RUN_OK).build());
        when(tradingService.submitLongCycle(any())).thenReturn(HtxContractTradeResult.builder()
                .code(HtxContractTradeResult.Code.DRY_RUN_OK).message("dry-run").build());
        when(recorder.recordDryRun(any(), anyString(), anyInt(), anyString())).thenReturn(88L);

        scheduler.runOnce();

        ArgumentCaptor<HtxContractCycleRequest> request = ArgumentCaptor.forClass(HtxContractCycleRequest.class);
        verify(tradingService).submitLongCycle(request.capture());
        assertEquals("XAUT-USDT", request.getValue().getContractCode());
        assertEquals(2L, request.getValue().getVolume());
        assertEquals(50, request.getValue().getLeverage());
        assertEquals(new BigDecimal("4333.34"), request.getValue().getOpenPrice());
        assertEquals(new BigDecimal("4359.35"), request.getValue().getClosePrice());
        verify(recorder).recordDryRun(request.getValue(), "XAUT-USDT", 50, "dry-run");
    }

    @Test
    void refusesToRunWhenDryRunGateIsOff() {
        props.setDryRun(false);

        scheduler.runOnce();

        verify(swap, never()).contractInfo(anyString());
        verify(tradingService, never()).submitLongCycle(any());
        verify(recorder, never()).recordDryRun(any(), anyString(), anyInt(), anyString());
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
