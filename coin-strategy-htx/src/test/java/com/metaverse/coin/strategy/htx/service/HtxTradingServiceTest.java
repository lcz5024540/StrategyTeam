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
        // 默认把风控上限放到极大，保证既有测试走到执行分支；具体限额测试再各自收紧
        props.getRisk().setMaxSingleQuoteNotional(new BigDecimal("100000"));
        props.getRisk().setMaxDailyQuoteNotional(new BigDecimal("100000000"));
        service = new HtxTradingService(htxApiService, depthGuard, executor, props, recorder);

        // 让深度前置校验通过，使持久化分支可达
        HtxMarketDataService md = mock(HtxMarketDataService.class);
        when(htxApiService.getMarketDataService()).thenReturn(md);
        when(md.depth(anyString(), anyInt()))
                .thenReturn(HtxRestResponse.<HtxDepth>builder().status("ok").data(new HtxDepth()).build());
        when(depthGuard.check(any(), any(), any(), anyInt()))
                .thenReturn(new HtxDepthGuard.Verdict(true, true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        // 默认当日累计为 0，使既有测试不被每日上限拦截
        when(recorder.sumTodayLiveQuoteNotional()).thenReturn(BigDecimal.ZERO);
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
    void buyLegPersistFailure_failsClosed_returnsPersistWriteFailed() {
        when(recorder.createPair(any(), eq(false))).thenReturn(42L);
        when(executor.execute(any(), eq(42L)))
                .thenThrow(new HtxPersistenceException("buy-leg intent write failed", null));

        HtxLockedPairResult r = service.submitLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.PERSIST_WRITE_FAILED, r.getCode());
    }

    @Test
    void preflight_enabledButNoSpotAccountId_rejects() {
        props.getApi().setSpotAccountId("");

        HtxLockedPairResult r = service.submitLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.REJECT_PREFLIGHT, r.getCode());
        verify(executor, never()).execute(any(), anyLong());
        verify(recorder, never()).createPair(any(), eq(false));
    }

    @Test
    void singleTradeExceedsCap_rejects_noExecute() {
        // req 买腿名义 = 0.01 * 60000 = 600 USDT > 50 上限
        props.getRisk().setMaxSingleQuoteNotional(new BigDecimal("50"));

        HtxLockedPairResult r = service.submitLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.REJECT_MAX_SINGLE_NOTIONAL, r.getCode());
        verify(recorder, never()).createPair(any(), eq(false));
        verify(executor, never()).execute(any(), anyLong());
        // 单笔上限应在读取当日累计之前拦截，避免无谓 DB 读
        verify(recorder, never()).sumTodayLiveQuoteNotional();
    }

    @Test
    void dailyCumulativeExceedsCap_rejects_noExecute() {
        // 单笔上限放开，仅测每日累计：已用 900 + 本单 600 = 1500 > 1000
        props.getRisk().setMaxSingleQuoteNotional(new BigDecimal("100000"));
        props.getRisk().setMaxDailyQuoteNotional(new BigDecimal("1000"));
        when(recorder.sumTodayLiveQuoteNotional()).thenReturn(new BigDecimal("900"));

        HtxLockedPairResult r = service.submitLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.REJECT_MAX_DAILY_NOTIONAL, r.getCode());
        verify(recorder, never()).createPair(any(), eq(false));
        verify(executor, never()).execute(any(), anyLong());
    }

    @Test
    void dailyCumulativeWithinCap_proceeds() {
        // 已用 300 + 本单 600 = 900 <= 1000，放行
        props.getRisk().setMaxSingleQuoteNotional(new BigDecimal("100000"));
        props.getRisk().setMaxDailyQuoteNotional(new BigDecimal("1000"));
        when(recorder.sumTodayLiveQuoteNotional()).thenReturn(new BigDecimal("300"));
        when(recorder.createPair(any(), eq(false))).thenReturn(7L);
        when(executor.execute(any(), eq(7L)))
                .thenReturn(HtxLockedPairResult.builder().code(HtxLockedPairResult.Code.OK).build());

        HtxLockedPairResult r = service.submitLockedPair(req());

        assertEquals(HtxLockedPairResult.Code.OK, r.getCode());
        verify(executor).execute(any(), eq(7L));
    }

    @Test
    void dailyNotionalReadFailure_failsClosed_noExecute() {
        props.getRisk().setMaxSingleQuoteNotional(new BigDecimal("100000"));
        props.getRisk().setMaxDailyQuoteNotional(new BigDecimal("100000"));
        when(recorder.sumTodayLiveQuoteNotional())
                .thenThrow(new HtxPersistenceException("daily sum read failed", null));

        HtxLockedPairResult r = service.submitLockedPair(req());

        // 读不到当日累计 → 无法确认未超限 → fail-closed 拒单
        assertEquals(HtxLockedPairResult.Code.REJECT_MAX_DAILY_NOTIONAL, r.getCode());
        verify(recorder, never()).createPair(any(), eq(false));
        verify(executor, never()).execute(any(), anyLong());
    }
}
