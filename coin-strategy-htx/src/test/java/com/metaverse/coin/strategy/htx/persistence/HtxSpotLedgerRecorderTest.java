package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryLotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxSpotFillLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryLotMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxSpotFillLedgerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HtxSpotLedgerRecorderTest {

    private HtxSpotFillLedgerMapper fillMapper;
    private HtxInventoryLotMapper lotMapper;
    private HtxSpotLedgerRecorder recorder;

    @BeforeEach
    void setUp() {
        fillMapper = mock(HtxSpotFillLedgerMapper.class);
        lotMapper = mock(HtxInventoryLotMapper.class);
        recorder = new HtxSpotLedgerRecorder(fillMapper, lotMapper);
    }

    @Test
    void buySnapshotAppendsOnlyDeltaAndCreatesInventoryLotNetOfBaseFee() {
        HtxSpotFillLedgerEntity previous = new HtxSpotFillLedgerEntity();
        previous.setCumulativeFilledAmount(new BigDecimal("0.1000"));
        previous.setCumulativeFilledCashAmount(new BigDecimal("6000"));
        previous.setCumulativeFilledFees(new BigDecimal("0.0002"));
        when(fillMapper.findLatestSnapshot(eq("xautusdt"), eq("BUY"), eq(11L), any()))
                .thenReturn(previous);
        when(fillMapper.insert(any())).thenAnswer(inv -> {
            ((HtxSpotFillLedgerEntity) inv.getArgument(0)).setId(99L);
            return 1;
        });
        HtxOrder order = order(11L, "xautusdt", "buy-limit", "partial-filled",
                "0.1500", "9000", "0.0003");

        HtxSpotLedgerRecorder.LedgerAppendResult result = recorder.recordOrderSnapshot(
                7L, null, "xautusdt", "buy", 11L, "mm-xaut-BUY", order,
                HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);

        assertTrue(result.appended());
        assertEquals(0, new BigDecimal("0.0500").compareTo(result.deltaBase()));
        assertEquals(0, new BigDecimal("-3000").compareTo(result.deltaQuote()));

        ArgumentCaptor<HtxSpotFillLedgerEntity> fill = ArgumentCaptor.forClass(HtxSpotFillLedgerEntity.class);
        verify(fillMapper).insert(fill.capture());
        assertEquals(0, new BigDecimal("0.0500").compareTo(fill.getValue().getDeltaBase()));
        assertEquals(0, new BigDecimal("-3000").compareTo(fill.getValue().getDeltaQuote()));

        ArgumentCaptor<HtxInventoryLotEntity> lot = ArgumentCaptor.forClass(HtxInventoryLotEntity.class);
        verify(lotMapper).insert(lot.capture());
        assertEquals(99L, lot.getValue().getBuyFillLedgerId());
        assertEquals(0, new BigDecimal("0.0499").compareTo(lot.getValue().getAcquiredBase()));
        assertEquals(HtxSpotLedgerRecorder.LOT_OPEN, lot.getValue().getStatus());
    }

    @Test
    void repeatedSnapshotDoesNotAppendOrDoubleInventory() {
        HtxSpotFillLedgerEntity previous = new HtxSpotFillLedgerEntity();
        previous.setCumulativeFilledAmount(new BigDecimal("0.1500"));
        previous.setCumulativeFilledCashAmount(new BigDecimal("9000"));
        previous.setCumulativeFilledFees(new BigDecimal("0.0003"));
        when(fillMapper.findLatestSnapshot(eq("xautusdt"), eq("BUY"), eq(11L), any()))
                .thenReturn(previous);
        HtxOrder order = order(11L, "xautusdt", "buy-limit", "partial-filled",
                "0.1500", "9000", "0.0003");

        HtxSpotLedgerRecorder.LedgerAppendResult result = recorder.recordOrderSnapshot(
                7L, null, "xautusdt", "buy", 11L, "mm-xaut-BUY", order,
                HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);

        assertTrue(result.duplicate());
        verify(fillMapper, never()).insert(any());
        verifyNoInteractions(lotMapper);
    }

    @Test
    void sellSnapshotConsumesInventoryLotsFifoAndReportsUnmatchedSell() {
        when(fillMapper.findLatestSnapshot(eq("xautusdt"), eq("SELL"), eq(22L), any()))
                .thenReturn(null);
        when(fillMapper.insert(any())).thenAnswer(inv -> {
            ((HtxSpotFillLedgerEntity) inv.getArgument(0)).setId(100L);
            return 1;
        });
        HtxInventoryLotEntity first = lot(1L, "0.0300");
        HtxInventoryLotEntity second = lot(2L, "0.0100");
        when(lotMapper.findOpenLots("xautusdt")).thenReturn(List.of(first, second));
        HtxOrder order = order(22L, "xautusdt", "sell-limit", "partial-canceled",
                "0.0500", "3050", "6.10");

        HtxSpotLedgerRecorder.LedgerAppendResult result = recorder.recordOrderSnapshot(
                7L, null, "xautusdt", "sell", 22L, "mm-xaut-SELL", order,
                HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);

        assertTrue(result.appended());
        assertEquals(0, new BigDecimal("0.0100").compareTo(result.unmatchedSellBase()));
        ArgumentCaptor<HtxInventoryLotEntity> updates = ArgumentCaptor.forClass(HtxInventoryLotEntity.class);
        verify(lotMapper, times(2)).updateById(updates.capture());
        assertEquals(HtxSpotLedgerRecorder.LOT_CLOSED, updates.getAllValues().get(0).getStatus());
        assertEquals(HtxSpotLedgerRecorder.LOT_CLOSED, updates.getAllValues().get(1).getStatus());
    }

    @Test
    void regressedCumulativeSnapshotIsSkipped() {
        HtxSpotFillLedgerEntity previous = new HtxSpotFillLedgerEntity();
        previous.setCumulativeFilledAmount(new BigDecimal("0.1500"));
        previous.setCumulativeFilledCashAmount(new BigDecimal("9000"));
        previous.setCumulativeFilledFees(new BigDecimal("0.0003"));
        when(fillMapper.findLatestSnapshot(eq("xautusdt"), eq("BUY"), eq(11L), any()))
                .thenReturn(previous);
        HtxOrder order = order(11L, "xautusdt", "buy-limit", "partial-filled",
                "0.1000", "6000", "0.0002");

        HtxSpotLedgerRecorder.LedgerAppendResult result = recorder.recordOrderSnapshot(
                7L, null, "xautusdt", "buy", 11L, "mm-xaut-BUY", order,
                HtxSpotLedgerRecorder.SOURCE_MARKET_MAKER);

        assertFalse(result.appended());
        assertEquals("cumulative fill snapshot regressed", result.skippedReason());
        verify(fillMapper, never()).insert(any());
        verifyNoInteractions(lotMapper);
    }

    private HtxInventoryLotEntity lot(long id, String remaining) {
        HtxInventoryLotEntity lot = new HtxInventoryLotEntity();
        lot.setId(id);
        lot.setSymbol("xautusdt");
        lot.setRemainingBase(new BigDecimal(remaining));
        lot.setStatus(HtxSpotLedgerRecorder.LOT_OPEN);
        return lot;
    }

    private HtxOrder order(long orderId, String symbol, String type, String state,
                           String amount, String cash, String fees) {
        HtxOrder order = new HtxOrder();
        order.setOrderId(orderId);
        order.setSymbol(symbol);
        order.setType(type);
        order.setState(state);
        order.setFilledAmount(new BigDecimal(amount));
        order.setFilledCashAmount(new BigDecimal(cash));
        order.setFilledFees(new BigDecimal(fees));
        return order;
    }
}
