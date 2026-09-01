package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxDeltaHedgeLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryRecycleSagaEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxDeltaHedgeLedgerMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryRecycleSagaMapper;
import com.metaverse.coin.strategy.htx.strategy.DeltaHedgeManager;
import com.metaverse.coin.strategy.htx.strategy.InventoryRecyclePlanner;
import com.metaverse.coin.strategy.htx.strategy.MovingGridEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HtxHedgeRecycleRecorderTest {

    private HtxDeltaHedgeLedgerMapper hedgeMapper;
    private HtxInventoryRecycleSagaMapper recycleMapper;
    private HtxHedgeRecycleRecorder recorder;

    @BeforeEach
    void setUp() {
        hedgeMapper = mock(HtxDeltaHedgeLedgerMapper.class);
        recycleMapper = mock(HtxInventoryRecycleSagaMapper.class);
        recorder = new HtxHedgeRecycleRecorder(hedgeMapper, recycleMapper);
    }

    @Test
    void hedgeDecisionPersistsAggregateIntentWithoutSendingOrder() {
        when(hedgeMapper.insert(any())).thenAnswer(inv -> {
            ((HtxDeltaHedgeLedgerEntity) inv.getArgument(0)).setId(11L);
            return 1;
        });
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager.HedgeDecision(
                DeltaHedgeManager.Action.OPEN_SHORT, MovingGridEngine.RUNNING,
                new BigDecimal("0.500"), order("CS-MM-HEDGE-XAUTUSDT-OPEN-7", "500", false), null);

        HtxHedgeRecycleRecorder.RecordResult result =
                recorder.recordHedgeIntent("xautusdt", 7L, decision);

        assertTrue(result.appended());
        ArgumentCaptor<HtxDeltaHedgeLedgerEntity> cap =
                ArgumentCaptor.forClass(HtxDeltaHedgeLedgerEntity.class);
        verify(hedgeMapper).insert(cap.capture());
        HtxDeltaHedgeLedgerEntity row = cap.getValue();
        assertEquals("hedge:xautusdt:7:CS-MM-HEDGE-XAUTUSDT-OPEN-7", row.getHedgeKey());
        assertEquals("OPEN_SHORT", row.getAction());
        assertEquals(500L, row.getContractVolume());
        assertFalse(row.getReduceOnly());
        verifyNoInteractions(recycleMapper);
    }

    @Test
    void duplicateHedgeIntentDoesNotInsertSecondLogicalOrder() {
        when(hedgeMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        DeltaHedgeManager.HedgeDecision decision = new DeltaHedgeManager.HedgeDecision(
                DeltaHedgeManager.Action.CLOSE_SHORT, MovingGridEngine.RUNNING,
                BigDecimal.ZERO, order("CS-MM-HEDGE-XAUTUSDT-CLOSE-8", "300", true), "reduce");

        HtxHedgeRecycleRecorder.RecordResult result =
                recorder.recordHedgeIntent("xautusdt", 8L, decision);

        assertFalse(result.appended());
        assertTrue(result.duplicate());
    }

    @Test
    void noneHedgeDecisionIsSkipped() {
        HtxHedgeRecycleRecorder.RecordResult result = recorder.recordHedgeIntent(
                "xautusdt", 9L, DeltaHedgeManager.HedgeDecision.none(
                        MovingGridEngine.RUNNING, BigDecimal.ZERO, "aligned"));

        assertEquals("no hedge order intent", result.skippedReason());
        verifyNoInteractions(hedgeMapper, recycleMapper);
    }

    @Test
    void recycleDecisionPersistsReduceOnlyCloseShortSaga() {
        when(recycleMapper.insert(any())).thenAnswer(inv -> {
            ((HtxInventoryRecycleSagaEntity) inv.getArgument(0)).setId(12L);
            return 1;
        });
        InventoryRecyclePlanner.RecycleInput input = new InventoryRecyclePlanner.RecycleInput(
                "xautusdt", "XAUT-USDT", new BigDecimal("0.015"),
                new BigDecimal("0.020"), new BigDecimal("0.001"),
                "CS-MM-RECYCLE-XAUTUSDT", 3L);
        InventoryRecyclePlanner.RecycleDecision decision = new InventoryRecyclePlanner.RecycleDecision(
                MovingGridEngine.RUNNING, 15L,
                order("CS-MM-RECYCLE-XAUTUSDT-CLOSE-3", "15", true), null);

        HtxHedgeRecycleRecorder.RecordResult result =
                recorder.recordRecycleIntent(101L, input, decision);

        assertTrue(result.appended());
        ArgumentCaptor<HtxInventoryRecycleSagaEntity> cap =
                ArgumentCaptor.forClass(HtxInventoryRecycleSagaEntity.class);
        verify(recycleMapper).insert(cap.capture());
        HtxInventoryRecycleSagaEntity row = cap.getValue();
        assertEquals("recycle:101:CS-MM-RECYCLE-XAUTUSDT-CLOSE-3", row.getSagaKey());
        assertEquals(101L, row.getSpotFillLedgerId());
        assertEquals(15L, row.getCloseContracts());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING, row.getStatus());
        verifyNoInteractions(hedgeMapper);
    }

    @Test
    void recycleCloseShortWithoutReduceOnlyIsRejectedBeforePersistence() {
        InventoryRecyclePlanner.RecycleInput input = new InventoryRecyclePlanner.RecycleInput(
                "xautusdt", "XAUT-USDT", new BigDecimal("0.015"),
                new BigDecimal("0.020"), new BigDecimal("0.001"),
                "CS-MM-RECYCLE-XAUTUSDT", 3L);
        InventoryRecyclePlanner.RecycleDecision decision = new InventoryRecyclePlanner.RecycleDecision(
                MovingGridEngine.RUNNING, 15L,
                order("CS-MM-RECYCLE-XAUTUSDT-CLOSE-3", "15", false), null);

        HtxHedgeRecycleRecorder.RecordResult result =
                recorder.recordRecycleIntent(101L, input, decision);

        assertEquals("recycle close-short must be reduce-only", result.skippedReason());
        verifyNoInteractions(hedgeMapper, recycleMapper);
    }

    private static HtxSwapOrderRequest order(String clientOrderId, String volume, boolean reduceOnly) {
        return HtxSwapOrderRequest.builder()
                .contractCode("XAUT-USDT")
                .positionSide("short")
                .side(reduceOnly ? "buy" : "sell")
                .type("market")
                .clientOrderId(clientOrderId)
                .volume(volume)
                .reduceOnly(reduceOnly)
                .timeInForce("ioc")
                .build();
    }
}
