package com.metaverse.coin.strategy.htx.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxLockedPairEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HtxTradeRecorderMmTest {

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

    @Test void createMmCell_persists_resting_buy_and_market_maker_strategy() {
        when(pairMapper.insert(any())).thenAnswer(inv -> {
            ((HtxLockedPairEntity) inv.getArgument(0)).setId(100L);
            return 1;
        });
        long id = recorder.createMmCell("btcusdt", new BigDecimal("0.0003"),
                new BigDecimal("59700.00"), new BigDecimal("60300.00"), "mm-btcusdt-1");
        assertEquals(100L, id);
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).insert(cap.capture());
        HtxLockedPairEntity e = cap.getValue();
        assertEquals(HtxStatus.MM_RESTING_BUY, e.getStatus());
        assertEquals(HtxStatus.STRATEGY_MARKET_MAKER, e.getStrategy());
        assertEquals("mm-btcusdt-1", e.getClientOrderId());
        assertEquals(0, new BigDecimal("0.0003").compareTo(e.getReqQuantity()));
        assertEquals(0, new BigDecimal("59700.00").compareTo(e.getBuyPrice()));
        assertEquals(0, new BigDecimal("60300.00").compareTo(e.getSellPrice()));
        assertFalse(e.getDryRun());   // 做市实盘单
    }

    @Test void createMmCell_fail_closed_on_insert_error() {
        when(pairMapper.insert(any())).thenThrow(new RuntimeException("db down"));
        assertThrows(HtxPersistenceException.class, () ->
                recorder.createMmCell("btcusdt", new BigDecimal("0.0003"),
                        new BigDecimal("59700.00"), new BigDecimal("60300.00"), "mm-btcusdt-1"));
    }

    @Test void markMmBuySent_sets_buy_order_id() {
        recorder.markMmBuySent(100L, 555L);
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        assertEquals(100L, cap.getValue().getId());
        assertEquals(555L, cap.getValue().getBuyOrderId());
    }

    @Test void markMmSellPending_persistsFillFeeAndActualSellPrice() {
        recorder.markMmSellPending(100L, new BigDecimal("0.0003"),
                new BigDecimal("0.0000006"), new BigDecimal("60058.20"));
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        HtxLockedPairEntity e = cap.getValue();
        assertEquals(HtxStatus.MM_RESTING_SELL, e.getStatus());
        assertEquals(0, new BigDecimal("0.0003").compareTo(e.getBuyFilledAmount()));
        assertEquals(0, new BigDecimal("0.0000006").compareTo(e.getTotalFeeBase()));
        assertEquals(0, new BigDecimal("60058.20").compareTo(e.getSellPrice()));
    }

    @Test void markMmSellResting_sets_sell_order_and_status() {
        recorder.markMmSellResting(100L, 777L);
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        HtxLockedPairEntity e = cap.getValue();
        assertEquals(HtxStatus.MM_RESTING_SELL, e.getStatus());
        assertEquals(777L, e.getSellOrderId());
    }

    @Test void markMmDone_sets_done_and_sell_filled() {
        recorder.markMmDone(100L, new BigDecimal("0.0003"));
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        assertEquals(HtxStatus.MM_DONE, cap.getValue().getStatus());
        assertEquals(0, new BigDecimal("0.0003").compareTo(cap.getValue().getSellFilledAmount()));
    }

    @Test void markMmCancelledBuy_sets_status() {
        recorder.markMmCancelledBuy(100L);
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        assertEquals(HtxStatus.MM_CANCELLED_BUY, cap.getValue().getStatus());
    }

    @Test void findOpenMmCells_queries_symbol_strategy_and_resting_statuses() {
        when(pairMapper.selectList(any())).thenReturn(List.of());
        recorder.findOpenMmCells("btcusdt");
        verify(pairMapper).selectList(any(QueryWrapper.class));
    }

    @Test void countOpenMmCells_uses_selectCount() {
        when(pairMapper.selectCount(any())).thenReturn(2L);
        assertEquals(2, recorder.countOpenMmCells("btcusdt"));
    }

    @Test void findAllOpenMmCells_queries_strategy_and_resting_statuses() {
        when(pairMapper.selectList(any())).thenReturn(List.of());
        recorder.findAllOpenMmCells();
        verify(pairMapper).selectList(any(QueryWrapper.class));
    }

    @Test void markMmReprice_updates_buy_order_price_and_resets_created_at_keeps_status_unset() {
        recorder.markMmReprice(100L, 888L, new BigDecimal("60000.00"), new BigDecimal("60360.00"));
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        HtxLockedPairEntity e = cap.getValue();
        assertEquals(100L, e.getId());
        assertEquals(888L, e.getBuyOrderId());
        assertEquals(0, new BigDecimal("60000.00").compareTo(e.getBuyPrice()));
        assertEquals(0, new BigDecimal("60360.00").compareTo(e.getSellPrice()));
        assertNotNull(e.getCreatedAt());                 // 重置 TTL
        assertNull(e.getStatus());                       // 状态不改（updateById 只更非空字段，仍 MM_RESTING_BUY）
    }

    @Test void markMmSellReprice_updates_sell_order_and_price_keeps_status_unset() {
        recorder.markMmSellReprice(100L, 888L, new BigDecimal("64515.00"));
        ArgumentCaptor<HtxLockedPairEntity> cap = ArgumentCaptor.forClass(HtxLockedPairEntity.class);
        verify(pairMapper).updateById(cap.capture());
        HtxLockedPairEntity e = cap.getValue();
        assertEquals(100L, e.getId());
        assertEquals(888L, e.getSellOrderId());
        assertEquals(0, new BigDecimal("64515.00").compareTo(e.getSellPrice()));
        assertNull(e.getStatus());   // updateById 只更非空字段，仍 MM_RESTING_SELL
    }
}
