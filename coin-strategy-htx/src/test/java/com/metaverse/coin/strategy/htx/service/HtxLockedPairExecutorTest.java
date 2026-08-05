package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.HtxTradeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HtxLockedPairExecutorTest {

    HtxApiService api;
    HtxSpotService spot;
    HtxTradeProperties props;
    HtxTradeRecorder recorder;
    HtxLockedPairExecutor executor;

    @BeforeEach void setup() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        when(api.getSpotService()).thenReturn(spot);
        props = new HtxTradeProperties();
        props.getApi().setSpotAccountId("acc-1");
        props.getTrade().setFillTimeoutMs(300);
        props.getTrade().setFillPollIntervalMs(10);
        recorder = mock(HtxTradeRecorder.class);
        // 写前默认成功：买腿 row=100, 卖腿 row=200
        when(recorder.insertOrderIntent(anyLong(), contains("BUY"), eq("buy"), any(), any(), any())).thenReturn(100L);
        when(recorder.insertOrderIntent(anyLong(), contains("SELL"), eq("sell"), any(), any(), any())).thenReturn(200L);
        executor = new HtxLockedPairExecutor(api, props, recorder);
    }

    private HtxLockedPairRequest req(String qty) {
        return HtxLockedPairRequest.builder()
                .symbol("btcusdt").quantity(new BigDecimal(qty))
                .buyPrice(new BigDecimal("60000")).sellPrice(new BigDecimal("60050"))
                .clientOrderId("key-1").build();
    }

    private HtxRestResponse<String> okPlace(String id) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("ok"); r.setData(id); return r;
    }

    private HtxRestResponse<HtxOrder> okOrder(String state, String filled, String fees, String cash) {
        HtxOrder o = new HtxOrder();
        o.setState(state);
        o.setFilledAmount(new BigDecimal(filled));
        o.setFilledFees(new BigDecimal(fees));
        o.setFilledCashAmount(new BigDecimal(cash));
        HtxRestResponse<HtxOrder> r = new HtxRestResponse<>();
        r.setStatus("ok"); r.setData(o); return r;
    }

    @Test void buy_writeAhead_failure_aborts_before_any_REST() {
        when(recorder.insertOrderIntent(anyLong(), contains("BUY"), any(), any(), any(), any()))
                .thenThrow(new HtxPersistenceException("db down", null));
        assertThrows(HtxPersistenceException.class, () -> executor.execute(req("0.01"), 1L));
        verify(spot, never()).placeOrder(any(), any(), any(), any(), any(), any());
    }

    @Test void sell_amount_deducts_buy_base_fee() {
        // 买腿全成 0.01，手续费 0.00002 BTC → 应挂卖 0.00998
        when(spot.placeOrder(any(), any(), eq("buy-limit"), any(), any(), any())).thenReturn(okPlace("111"));
        when(spot.placeOrder(any(), any(), eq("sell-limit"), any(), any(), any())).thenReturn(okPlace("222"));
        when(spot.queryOrder(111L)).thenReturn(okOrder("filled", "0.01", "0.00002", "600"));
        when(spot.queryOrder(222L)).thenReturn(okOrder("filled", "0.00998", "0.6", "599"));

        HtxLockedPairResult res = executor.execute(req("0.01"), 1L);

        assertEquals(HtxLockedPairResult.Code.OK, res.getCode());
        ArgumentCaptor<String> amt = ArgumentCaptor.forClass(String.class);
        verify(spot).placeOrder(any(), any(), eq("sell-limit"), amt.capture(), any(), any());
        assertEquals(0, new BigDecimal("0.00998").compareTo(new BigDecimal(amt.getValue())));
    }

    @Test void sell_reject_after_buy_fill_records_exposure() {
        when(spot.placeOrder(any(), any(), eq("buy-limit"), any(), any(), any())).thenReturn(okPlace("111"));
        HtxRestResponse<String> rej = new HtxRestResponse<>();
        rej.setStatus("error"); rej.setErrMsg("sell-rejected");
        when(spot.placeOrder(any(), any(), eq("sell-limit"), any(), any(), any())).thenReturn(rej);
        when(spot.queryOrder(111L)).thenReturn(okOrder("filled", "0.01", "0.00002", "600"));

        HtxLockedPairResult res = executor.execute(req("0.01"), 1L);

        assertEquals(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL, res.getCode());
        verify(recorder).recordExposure(eq(1L), eq("btcusdt"), eq("base-long"), any(), anyString());
    }
}
