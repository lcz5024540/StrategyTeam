package com.metaverse.coin.strategy.htx.recovery;

import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxStatus;
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
