package com.metaverse.coin.strategy.htx.recovery;

import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
import com.metaverse.coin.strategy.htx.persistence.HtxSpotLedgerRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxOrderMapper;
import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class HtxStartupRecoveryTest {

    private HtxOrderMapper orderMapper;
    private HtxApiService api;
    private HtxSpotService spot;
    private HtxTradeProperties props;
    private HtxStartupRecovery recovery;

    @BeforeEach
    void setUp() {
        orderMapper = mock(HtxOrderMapper.class);
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        when(api.getSpotService()).thenReturn(spot);
        props = new HtxTradeProperties();
        props.getTrade().setEnabled(true);
        recovery = new HtxStartupRecovery(orderMapper, api, props);
    }

    @Test
    void pendingSend_htxHasOrder_backfillsSent() {
        HtxOrderEntity e = new HtxOrderEntity();
        e.setId(1L);
        e.setClientOrderId("key-1-BUY");
        e.setIntentStatus(HtxStatus.INTENT_PENDING_SEND);
        when(orderMapper.selectList(any())).thenReturn(List.of(e));

        HtxOrder o = new HtxOrder();
        o.setOrderId(9001L);
        o.setState("submitted");
        when(spot.queryOrderByClientId("key-1-BUY"))
                .thenReturn(HtxRestResponse.<HtxOrder>builder().status("ok").data(o).build());

        recovery.recover();

        verify(orderMapper).updateById(argThat(u ->
                Long.valueOf(9001L).equals(u.getHtxOrderId())
                        && HtxStatus.INTENT_SENT.equals(u.getIntentStatus())));
    }

    @Test
    void pendingSend_htxHasFilledOrder_recordsExchangeFirstLedgerSnapshot() {
        HtxSpotLedgerRecorder ledger = mock(HtxSpotLedgerRecorder.class);
        recovery = new HtxStartupRecovery(orderMapper, api, props, ledger);
        HtxOrderEntity e = new HtxOrderEntity();
        e.setId(3L);
        e.setPairId(77L);
        e.setClientOrderId("key-3-BUY");
        e.setSide("buy");
        e.setIntentStatus(HtxStatus.INTENT_PENDING_SEND);
        when(orderMapper.selectList(any())).thenReturn(List.of(e));

        HtxOrder o = new HtxOrder();
        o.setOrderId(9003L);
        o.setSymbol("xautusdt");
        o.setType("buy-limit");
        o.setState("filled");
        o.setFilledAmount(new java.math.BigDecimal("0.01"));
        o.setFilledCashAmount(new java.math.BigDecimal("25"));
        o.setFilledFees(new java.math.BigDecimal("0.00002"));
        when(spot.queryOrderByClientId("key-3-BUY"))
                .thenReturn(HtxRestResponse.<HtxOrder>builder().status("ok").data(o).build());

        recovery.recover();

        verify(ledger).recordOrderSnapshot(eq(77L), eq(3L), eq("xautusdt"), eq("buy"),
                eq(9003L), eq("key-3-BUY"), same(o), eq(HtxSpotLedgerRecorder.SOURCE_STARTUP_RECOVERY));
        verify(orderMapper).updateById(argThat(u ->
                Long.valueOf(9003L).equals(u.getHtxOrderId())
                        && HtxStatus.INTENT_SENT.equals(u.getIntentStatus())));
    }

    @Test
    void submittedOrderAtStartup_recordsExchangeFirstLedgerSnapshot() {
        HtxSpotLedgerRecorder ledger = mock(HtxSpotLedgerRecorder.class);
        recovery = new HtxStartupRecovery(orderMapper, api, props, ledger);
        HtxOrderEntity e = new HtxOrderEntity();
        e.setId(4L);
        e.setPairId(88L);
        e.setHtxOrderId(9004L);
        e.setClientOrderId("key-4-SELL");
        e.setSide("sell");
        e.setIntentStatus(HtxStatus.INTENT_SENT);
        when(orderMapper.selectList(any())).thenReturn(List.of(), List.of(e));

        HtxOrder o = new HtxOrder();
        o.setOrderId(9004L);
        o.setSymbol("xautusdt");
        o.setType("sell-limit-maker");
        o.setState("partial-filled");
        o.setFilledAmount(new java.math.BigDecimal("0.006"));
        o.setFilledCashAmount(new java.math.BigDecimal("15"));
        o.setFilledFees(new java.math.BigDecimal("0.009"));
        when(spot.queryOrder(9004L))
                .thenReturn(HtxRestResponse.<HtxOrder>builder().status("ok").data(o).build());

        recovery.recover();

        verify(ledger).recordOrderSnapshot(eq(88L), eq(4L), eq("xautusdt"), eq("sell"),
                eq(9004L), eq("key-4-SELL"), same(o), eq(HtxSpotLedgerRecorder.SOURCE_STARTUP_RECOVERY));
        verify(orderMapper).updateById(argThat(u ->
                Long.valueOf(9004L).equals(u.getHtxOrderId())
                        && "partial-filled".equals(u.getState())
                        && HtxStatus.INTENT_SENT.equals(u.getIntentStatus())
                        && new java.math.BigDecimal("0.006").compareTo(u.getFilledAmount()) == 0
                        && "usdt".equals(u.getFeeCurrency())));
    }

    @Test
    void submittedOrderMissingOnExchange_keepsLocalStateForManualReconciliation() {
        HtxSpotLedgerRecorder ledger = mock(HtxSpotLedgerRecorder.class);
        recovery = new HtxStartupRecovery(orderMapper, api, props, ledger);
        HtxOrderEntity e = new HtxOrderEntity();
        e.setId(5L);
        e.setPairId(89L);
        e.setClientOrderId("key-5-BUY");
        e.setSide("buy");
        e.setIntentStatus(HtxStatus.INTENT_SENT);
        when(orderMapper.selectList(any())).thenReturn(List.of(), List.of(e));
        when(spot.queryOrderByClientId("key-5-BUY"))
                .thenReturn(HtxRestResponse.<HtxOrder>builder().status("error").errMsg("not-found").build());

        recovery.recover();

        verify(orderMapper, never()).updateById(any(HtxOrderEntity.class));
        verifyNoInteractions(ledger);
    }

    @Test
    void pendingSend_htxHasNoOrder_marksSendFailed() {
        HtxOrderEntity e = new HtxOrderEntity();
        e.setId(2L);
        e.setClientOrderId("key-2-BUY");
        e.setIntentStatus(HtxStatus.INTENT_PENDING_SEND);
        when(orderMapper.selectList(any())).thenReturn(List.of(e));
        when(spot.queryOrderByClientId("key-2-BUY"))
                .thenReturn(HtxRestResponse.<HtxOrder>builder().status("error").errMsg("not-found").build());

        recovery.recover();

        verify(orderMapper).updateById(argThat(u ->
                HtxStatus.INTENT_SEND_FAILED.equals(u.getIntentStatus())));
    }

    @Test
    void disabled_doesNothing() {
        props.getTrade().setEnabled(false);
        recovery.recover();
        verifyNoInteractions(orderMapper);
    }
}
