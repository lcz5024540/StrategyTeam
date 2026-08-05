package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HtxTradingServiceTest {

    private HtxApiService htxApiService;
    private HtxDepthGuard depthGuard;
    private HtxLockedPairExecutor executor;
    private HtxTradeRecorder recorder;
    private HtxTradeProperties props;
    private HtxTradingService service;

    private HtxLockedPairRequest req() {
        return HtxLockedPairRequest.builder()
                .symbol("btcusdt")
                .quantity(new BigDecimal("0.01"))
                .buyPrice(new BigDecimal("60000"))
                .sellPrice(new BigDecimal("60100"))
                .clientOrderId("key-1")
                .build();
    }

    @BeforeEach
    void setUp() {
        htxApiService = mock(HtxApiService.class);
        depthGuard = mock(HtxDepthGuard.class);
        executor = mock(HtxLockedPairExecutor.class);
        recorder = mock(HtxTradeRecorder.class);
        props = new HtxTradeProperties();
        props.getTrade().setEnabled(true);
        props.getApi().setSpotAccountId("123");
        service = new HtxTradingService(htxApiService, depthGuard, executor, props, recorder);

        // 让深度前置校验通过，使持久化分支可达
        HtxMarketDataService md = mock(HtxMarketDataService.class);
        when(htxApiService.getMarketDataService()).thenReturn(md);
        when(md.depth(anyString(), anyInt()))
                .thenReturn(HtxRestResponse.<HtxDepth>builder().status("ok").data(new HtxDepth()).build());
        when(depthGuard.check(any(), any(), any(), anyInt()))
                .thenReturn(new HtxDepthGuard.Verdict(true, true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void realPath_persistsPairThenExecutesWithPairId() {
        when(recorder.createPair(any(), eq(false))).thenReturn(42L);
        when(executor.execute(any(), eq(42L)))
                .thenReturn(HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.OK).build());

        HtxLockedPairResult r = service.submitLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.OK, r.getCode());
        verify(recorder).createPair(any(), eq(false));
        verify(executor).execute(any(), eq(42L));
    }

    @Test
    void dbWriteFailure_failsClosed_noExecute() {
        when(recorder.createPair(any(), eq(false)))
                .thenThrow(new HtxPersistenceException("db down", null));

        HtxLockedPairResult r = service.submitLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.PERSIST_WRITE_FAILED, r.getCode());
        verify(executor, never()).execute(any(), anyLong());
    }

    @Test
    void preflight_enabledButNoSpotAccountId_rejects() {
        props.getApi().setSpotAccountId("");

        HtxLockedPairResult r = service.submitLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.REJECT_PREFLIGHT, r.getCode());
        verify(executor, never()).execute(any(), anyLong());
        verify(recorder, never()).createPair(any(), eq(false));
    }
}
