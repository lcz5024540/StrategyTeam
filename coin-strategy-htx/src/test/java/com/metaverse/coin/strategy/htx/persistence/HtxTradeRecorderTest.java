package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.*;
import com.metaverse.coin.strategy.htx.service.HtxLockedPairRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HtxTradeRecorderTest {

    HtxLockedPairMapper pairMapper;
    HtxOrderMapper orderMapper;
    HtxCancelMapper cancelMapper;
    HtxExposureMapper exposureMapper;
    HtxTradeRecorder recorder;

    @BeforeEach void setup() {
        pairMapper = mock(HtxLockedPairMapper.class);
        orderMapper = mock(HtxOrderMapper.class);
        cancelMapper = mock(HtxCancelMapper.class);
        exposureMapper = mock(HtxExposureMapper.class);
        recorder = new HtxTradeRecorder(pairMapper, orderMapper, cancelMapper, exposureMapper);
    }

    private HtxLockedPairRequest req() {
        return HtxLockedPairRequest.builder()
                .symbol("btcusdt").quantity(new BigDecimal("0.01"))
                .buyPrice(new BigDecimal("60000")).sellPrice(new BigDecimal("60050"))
                .clientOrderId("key-1").build();
    }

    @Test void createPair_returns_generated_id() {
        when(pairMapper.insert(any())).thenAnswer(inv -> {
            ((HtxLockedPairEntity) inv.getArgument(0)).setId(42L);
            return 1;
        });
        assertEquals(42L, recorder.createPair(req(), false));
    }

    @Test void createPair_throws_persistence_on_insert_failure() {
        when(pairMapper.insert(any())).thenThrow(new RuntimeException("db down"));
        assertThrows(HtxPersistenceException.class, () -> recorder.createPair(req(), false));
    }

    @Test void insertOrderIntent_throws_persistence_on_failure() {
        when(orderMapper.insert(any())).thenThrow(new RuntimeException("db down"));
        assertThrows(HtxPersistenceException.class, () ->
                recorder.insertOrderIntent(1L, "key-1-BUY", "buy", "buy-limit",
                        new BigDecimal("60000"), new BigDecimal("0.01")));
    }

    @Test void insertOrderIntent_persists_pending_send_and_returns_id() {
        when(orderMapper.insert(any())).thenAnswer(inv -> {
            ((HtxOrderEntity) inv.getArgument(0)).setId(7L);
            return 1;
        });
        long id = recorder.insertOrderIntent(1L, "key-1-BUY", "buy", "buy-limit",
                new BigDecimal("60000"), new BigDecimal("0.01"));
        assertEquals(7L, id);
        ArgumentCaptor<HtxOrderEntity> cap = ArgumentCaptor.forClass(HtxOrderEntity.class);
        verify(orderMapper).insert(cap.capture());
        assertEquals(HtxStatus.INTENT_PENDING_SEND, cap.getValue().getIntentStatus());
        assertEquals("buy", cap.getValue().getSide());
    }

    @Test void updateOrderFill_maps_htxorder_fields_and_fee_currency() {
        HtxOrder o = new HtxOrder();
        o.setState("filled");
        o.setFilledAmount(new BigDecimal("0.01"));
        o.setFilledCashAmount(new BigDecimal("600"));
        o.setFilledFees(new BigDecimal("0.00002"));
        recorder.updateOrderFill(7L, "btcusdt", "buy", o, "{\"raw\":true}");
        ArgumentCaptor<HtxOrderEntity> cap = ArgumentCaptor.forClass(HtxOrderEntity.class);
        verify(orderMapper).updateById(cap.capture());
        HtxOrderEntity e = cap.getValue();
        assertEquals("filled", e.getState());
        assertEquals(0, new BigDecimal("0.00002").compareTo(e.getFilledFees()));
        assertEquals("btc", e.getFeeCurrency());          // 买腿费在 base 币
        assertEquals(HtxStatus.INTENT_TERMINAL, e.getIntentStatus());
    }

    @Test void updateOrderFill_sell_fee_currency_is_quote() {
        HtxOrder o = new HtxOrder();
        o.setState("filled");
        o.setFilledFees(new BigDecimal("0.6"));
        recorder.updateOrderFill(9L, "btcusdt", "sell", o, "{}");
        ArgumentCaptor<HtxOrderEntity> cap = ArgumentCaptor.forClass(HtxOrderEntity.class);
        verify(orderMapper).updateById(cap.capture());
        assertEquals("usdt", cap.getValue().getFeeCurrency());
    }
}
