package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxHedgeRecycleRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxDeltaHedgeLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryRecycleSagaEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxDeltaHedgeLedgerMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryRecycleSagaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxDeltaHedgeIntentExecutorTest {

    private HtxUsdtSwapService swap;
    private HtxContractProperties props;
    private HtxDeltaHedgeLedgerMapper hedgeMapper;
    private HtxInventoryRecycleSagaMapper recycleMapper;
    private HtxDeltaHedgeIntentExecutor executor;

    @BeforeEach
    void setUp() {
        swap = mock(HtxUsdtSwapService.class);
        props = new HtxContractProperties();
        hedgeMapper = mock(HtxDeltaHedgeLedgerMapper.class);
        recycleMapper = mock(HtxInventoryRecycleSagaMapper.class);
        executor = new HtxDeltaHedgeIntentExecutor(swap, props, hedgeMapper, recycleMapper);
    }

    @Test
    void dryRunClosesHedgeIntentWithoutCallingExchange() {
        HtxDeltaHedgeLedgerEntity row = openShortRow(11L);
        when(hedgeMapper.updateStatusIfCurrent(eq(11L), eq(HtxHedgeRecycleRecorder.STATUS_INTENT),
                eq(HtxHedgeRecycleRecorder.STATUS_SKIPPED_DRY_RUN), anyString())).thenReturn(1);

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.executeHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.SKIPPED_DRY_RUN, result.code());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_SKIPPED_DRY_RUN, result.status());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void liveGateClosesHedgeIntentWithoutCallingExchange() {
        props.setDryRun(false);
        HtxDeltaHedgeLedgerEntity row = openShortRow(12L);
        when(hedgeMapper.updateStatusIfCurrent(eq(12L), eq(HtxHedgeRecycleRecorder.STATUS_INTENT),
                eq(HtxHedgeRecycleRecorder.STATUS_SKIPPED_LIVE_GATE), anyString())).thenReturn(1);

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.executeHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.SKIPPED_LIVE_GATE, result.code());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_SKIPPED_LIVE_GATE, result.status());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void closeShortWithoutReduceOnlyIsRejectedBeforeSend() {
        HtxDeltaHedgeLedgerEntity row = closeShortRow(13L);
        row.setReduceOnly(false);
        when(hedgeMapper.updateStatusIfCurrent(eq(13L), eq(HtxHedgeRecycleRecorder.STATUS_INTENT),
                eq(HtxHedgeRecycleRecorder.STATUS_INVALID_INTENT), anyString())).thenReturn(1);

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.executeHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.INVALID_INTENT, result.code());
        assertTrue(result.reason().contains("reduce-only"));
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void staleHedgeIntentIsNotSentWhenClaimFails() {
        props.setDryRun(false);
        props.setLiveEnabled(true);
        HtxDeltaHedgeLedgerEntity row = openShortRow(14L);
        when(hedgeMapper.updateStatusIfCurrent(eq(14L), eq(HtxHedgeRecycleRecorder.STATUS_INTENT),
                eq(HtxHedgeRecycleRecorder.STATUS_SENDING), anyString())).thenReturn(0);

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.executeHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.STALE, result.code());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    @Test
    void acceptedHedgeIntentMovesToSent() {
        props.setDryRun(false);
        props.setLiveEnabled(true);
        HtxDeltaHedgeLedgerEntity row = openShortRow(15L);
        when(hedgeMapper.updateStatusIfCurrent(eq(15L), eq(HtxHedgeRecycleRecorder.STATUS_INTENT),
                eq(HtxHedgeRecycleRecorder.STATUS_SENDING), anyString())).thenReturn(1);
        when(hedgeMapper.updateAcceptedIfCurrent(eq(15L), eq(HtxHedgeRecycleRecorder.STATUS_SENDING),
                eq(HtxHedgeRecycleRecorder.STATUS_SENT), eq(9001L), eq("9002"), anyString()))
                .thenReturn(1);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(okOrder(9001L));

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.executeHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.SENT, result.code());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_SENT, result.status());
        ArgumentCaptor<HtxSwapOrderRequest> cap = ArgumentCaptor.forClass(HtxSwapOrderRequest.class);
        verify(swap).placeOrder(cap.capture());
        assertEquals("sell", cap.getValue().getSide());
        assertEquals("short", cap.getValue().getPositionSide());
        assertEquals(Boolean.FALSE, cap.getValue().getReduceOnly());
        assertEquals("market", cap.getValue().getType());
    }

    @Test
    void explicitExchangeRejectMovesToRejectedWithoutRetryLoop() {
        props.setDryRun(false);
        props.setLiveEnabled(true);
        HtxDeltaHedgeLedgerEntity row = openShortRow(16L);
        when(hedgeMapper.updateStatusIfCurrent(eq(16L), eq(HtxHedgeRecycleRecorder.STATUS_INTENT),
                eq(HtxHedgeRecycleRecorder.STATUS_SENDING), anyString())).thenReturn(1);
        when(hedgeMapper.updateStatusIfCurrent(eq(16L), eq(HtxHedgeRecycleRecorder.STATUS_SENDING),
                eq(HtxHedgeRecycleRecorder.STATUS_REJECTED), anyString())).thenReturn(1);
        HtxSwapOrderResult rejected = new HtxSwapOrderResult();
        rejected.setCode(1048);
        rejected.setMessage("insufficient margin");
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(response(200, rejected));

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.executeHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.REJECTED, result.code());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_REJECTED, result.status());
    }

    @Test
    void preAcceptParameterRejectMovesToRejectedWithoutBlockingRecovery() {
        props.setDryRun(false);
        props.setLiveEnabled(true);
        HtxDeltaHedgeLedgerEntity row = openShortRow(1601L);
        when(hedgeMapper.updateStatusIfCurrent(eq(1601L), eq(HtxHedgeRecycleRecorder.STATUS_INTENT),
                eq(HtxHedgeRecycleRecorder.STATUS_SENDING), anyString())).thenReturn(1);
        when(hedgeMapper.updateStatusIfCurrent(eq(1601L), eq(HtxHedgeRecycleRecorder.STATUS_SENDING),
                eq(HtxHedgeRecycleRecorder.STATUS_REJECTED), anyString())).thenReturn(1);
        HtxRestResponse<HtxSwapOrderResult> rejected = response(400, null);
        rejected.setMessage("Illegal parameter client_order_id.");
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(rejected);

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.executeHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.REJECTED, result.code());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_REJECTED, result.status());
    }

    @Test
    void knownPreAcceptRejectsAreReclassifiedBeforeBacklogBlocksAutoSend() {
        when(hedgeMapper.reclassifyPreAcceptRejects(
                eq(HtxHedgeRecycleRecorder.STATUS_UNKNOWN),
                eq(HtxHedgeRecycleRecorder.STATUS_REJECTED),
                anyString(),
                eq("exchange response not ok: Illegal parameter client_order_id%")))
                .thenReturn(1);
        when(recycleMapper.reclassifyPreAcceptRejects(
                eq(HtxHedgeRecycleRecorder.STATUS_UNKNOWN),
                eq(HtxHedgeRecycleRecorder.STATUS_REJECTED),
                anyString(),
                eq("exchange response not ok: Illegal parameter client_order_id%")))
                .thenReturn(2);

        int reclassified = executor.reclassifyKnownPreAcceptRejects();

        assertEquals(3, reclassified);
    }

    @Test
    void exchangeExceptionMovesToUnknown() {
        props.setDryRun(false);
        props.setLiveEnabled(true);
        HtxDeltaHedgeLedgerEntity row = openShortRow(17L);
        when(hedgeMapper.updateStatusIfCurrent(eq(17L), eq(HtxHedgeRecycleRecorder.STATUS_INTENT),
                eq(HtxHedgeRecycleRecorder.STATUS_SENDING), anyString())).thenReturn(1);
        when(hedgeMapper.updateStatusIfCurrent(eq(17L), eq(HtxHedgeRecycleRecorder.STATUS_SENDING),
                eq(HtxHedgeRecycleRecorder.STATUS_UNKNOWN), anyString())).thenReturn(1);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenThrow(new IllegalStateException("timeout"));

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.executeHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.UNKNOWN, result.code());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_UNKNOWN, result.status());
    }

    @Test
    void recycleSagaAlwaysSendsReduceOnlyCloseShortWhenLiveGatesOpen() {
        props.setDryRun(false);
        props.setLiveEnabled(true);
        HtxInventoryRecycleSagaEntity row = recycleRow(18L);
        when(recycleMapper.updateStatusIfCurrent(eq(18L),
                eq(HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING),
                eq(HtxHedgeRecycleRecorder.STATUS_SENDING), anyString())).thenReturn(1);
        when(recycleMapper.updateAcceptedIfCurrent(eq(18L), eq(HtxHedgeRecycleRecorder.STATUS_SENDING),
                eq(HtxHedgeRecycleRecorder.STATUS_SENT), eq(9002L), eq("9003"), anyString()))
                .thenReturn(1);
        when(swap.placeOrder(any(HtxSwapOrderRequest.class))).thenReturn(okOrder(9002L));

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.executeRecycleCloseIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.SENT, result.code());
        ArgumentCaptor<HtxSwapOrderRequest> cap = ArgumentCaptor.forClass(HtxSwapOrderRequest.class);
        verify(swap).placeOrder(cap.capture());
        assertEquals("buy", cap.getValue().getSide());
        assertEquals("short", cap.getValue().getPositionSide());
        assertEquals(Boolean.TRUE, cap.getValue().getReduceOnly());
        assertEquals("15", cap.getValue().getVolume());
    }

    @Test
    void sentHedgeReconcilesFilledExchangeSnapshot() {
        HtxDeltaHedgeLedgerEntity row = openShortRow(19L);
        row.setStatus(HtxHedgeRecycleRecorder.STATUS_SENT);
        row.setExchangeOrderId(9019L);
        when(swap.queryOrder("XAUT-USDT", 9019L))
                .thenReturn(orderResponse(filledOrder(9019L, "500", "4321.10")));
        when(hedgeMapper.updateReconciliationIfCurrent(eq(19L),
                eq(HtxHedgeRecycleRecorder.STATUS_SENT), eq(HtxHedgeRecycleRecorder.STATUS_FILLED),
                eq(9019L), eq("9020"), eq("filled"), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), eq("USDT"), any(BigDecimal.class), anyString())).thenReturn(1);

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.reconcileHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.FILLED, result.code());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_FILLED, result.status());
    }

    @Test
    void sentRecyclePartialTerminalMovesToHedgeDegraded() {
        HtxInventoryRecycleSagaEntity row = recycleRow(20L);
        row.setStatus(HtxHedgeRecycleRecorder.STATUS_SENT);
        row.setCloseExchangeOrderId(9020L);
        when(swap.queryOrder("XAUT-USDT", 9020L))
                .thenReturn(orderResponse(partialCanceledOrder(9020L, "3", "4310.50")));
        when(recycleMapper.updateReconciliationIfCurrent(eq(20L),
                eq(HtxHedgeRecycleRecorder.STATUS_SENT),
                eq(HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED), eq(9020L), eq("9021"),
                eq("partially_canceled"), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), eq("USDT"), any(BigDecimal.class), anyString()))
                .thenReturn(1);

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.reconcileRecycleCloseIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.HEDGE_DEGRADED, result.code());
        assertEquals(HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED, result.status());
        assertTrue(result.reason().contains("partial fill"));
    }

    @Test
    void sentHedgeWithoutExchangeOrderIdReconcilesByClientOrderId() {
        HtxDeltaHedgeLedgerEntity row = openShortRow(23L);
        row.setStatus(HtxHedgeRecycleRecorder.STATUS_SENT);
        when(swap.queryOrderByClientOrderId("XAUT-USDT", "CS-MM-HEDGE-XAUTUSDT-23"))
                .thenReturn(orderResponse(filledOrder(9023L, "500", "4320.00")));
        when(hedgeMapper.updateReconciliationIfCurrent(eq(23L),
                eq(HtxHedgeRecycleRecorder.STATUS_SENT),
                eq(HtxHedgeRecycleRecorder.STATUS_FILLED), eq(9023L), eq("9024"),
                eq("filled"), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), eq("USDT"), any(BigDecimal.class), anyString()))
                .thenReturn(1);

        HtxDeltaHedgeIntentExecutor.ExecutionResult result = executor.reconcileHedgeIntent(row);

        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.FILLED, result.code());
        verify(swap).queryOrderByClientOrderId("XAUT-USDT", "CS-MM-HEDGE-XAUTUSDT-23");
    }

    @Test
    void pendingHedgeBatchUsesStatusQuery() {
        when(hedgeMapper.findByStatus(HtxHedgeRecycleRecorder.STATUS_INTENT, 2))
                .thenReturn(List.of(openShortRow(21L), closeShortRow(22L)));
        props.setDryRun(false);
        when(hedgeMapper.updateStatusIfCurrent(any(Long.class), anyString(),
                eq(HtxHedgeRecycleRecorder.STATUS_SKIPPED_LIVE_GATE), anyString())).thenReturn(1);

        List<HtxDeltaHedgeIntentExecutor.ExecutionResult> results = executor.executePendingHedgeIntents(2);

        assertEquals(2, results.size());
        assertEquals(HtxDeltaHedgeIntentExecutor.ExecutionCode.SKIPPED_LIVE_GATE, results.get(0).code());
        verify(swap, never()).placeOrder(any(HtxSwapOrderRequest.class));
    }

    private static HtxDeltaHedgeLedgerEntity openShortRow(long id) {
        HtxDeltaHedgeLedgerEntity row = baseHedgeRow(id);
        row.setAction("OPEN_SHORT");
        row.setSide("sell");
        row.setReduceOnly(false);
        return row;
    }

    private static HtxDeltaHedgeLedgerEntity closeShortRow(long id) {
        HtxDeltaHedgeLedgerEntity row = baseHedgeRow(id);
        row.setAction("CLOSE_SHORT");
        row.setSide("buy");
        row.setReduceOnly(true);
        return row;
    }

    private static HtxDeltaHedgeLedgerEntity baseHedgeRow(long id) {
        HtxDeltaHedgeLedgerEntity row = new HtxDeltaHedgeLedgerEntity();
        row.setId(id);
        row.setHedgeKey("hedge-" + id);
        row.setSymbol("xautusdt");
        row.setContractCode("XAUT-USDT");
        row.setEpoch(7L);
        row.setTargetShortBase(new BigDecimal("0.5"));
        row.setContractVolume(500L);
        row.setPositionSide("short");
        row.setClientOrderId("CS-MM-HEDGE-XAUTUSDT-" + id);
        row.setPlannerState("RUNNING");
        row.setStatus(HtxHedgeRecycleRecorder.STATUS_INTENT);
        return row;
    }

    private static HtxInventoryRecycleSagaEntity recycleRow(long id) {
        HtxInventoryRecycleSagaEntity row = new HtxInventoryRecycleSagaEntity();
        row.setId(id);
        row.setSagaKey("recycle-" + id);
        row.setSymbol("xautusdt");
        row.setContractCode("XAUT-USDT");
        row.setEpoch(8L);
        row.setSpotFillLedgerId(101L);
        row.setSpotSoldBase(new BigDecimal("0.015"));
        row.setAvailableShortBase(new BigDecimal("0.020"));
        row.setCloseContracts(15L);
        row.setCloseClientOrderId("CS-MM-RECYCLE-XAUTUSDT-CLOSE-" + id);
        row.setPlannerState("RUNNING");
        row.setStatus(HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING);
        return row;
    }

    private static HtxRestResponse<HtxSwapOrderResult> okOrder(long orderId) {
        HtxSwapOrderResult result = new HtxSwapOrderResult();
        result.setOrderId(orderId);
        result.setClientOrderId(orderId + 1L);
        return response(200, result);
    }

    private static HtxRestResponse<List<HtxSwapOrder>> orderResponse(HtxSwapOrder order) {
        HtxRestResponse<List<HtxSwapOrder>> response = new HtxRestResponse<>();
        response.setCode(200);
        response.setData(List.of(order));
        return response;
    }

    private static HtxSwapOrder filledOrder(long orderId, String filled, String avgPrice) {
        HtxSwapOrder order = order(orderId, "filled", filled, avgPrice);
        order.setStatus(6);
        return order;
    }

    private static HtxSwapOrder partialCanceledOrder(long orderId, String filled, String avgPrice) {
        HtxSwapOrder order = order(orderId, "partially_canceled", filled, avgPrice);
        order.setStatus(5);
        return order;
    }

    private static HtxSwapOrder order(long orderId, String state, String filled, String avgPrice) {
        HtxSwapOrder order = new HtxSwapOrder();
        order.setOrderId(orderId);
        order.setClientOrderId(orderId + 1L);
        order.setContractCode("XAUT-USDT");
        order.setState(state);
        order.setTradeVolume(new BigDecimal(filled));
        order.setTradeAveragePrice(new BigDecimal(avgPrice));
        order.setFee(new BigDecimal("0.01"));
        order.setFeeCurrency("USDT");
        order.setRealProfit(new BigDecimal("0.02"));
        return order;
    }

    private static HtxRestResponse<HtxSwapOrderResult> response(Integer code, HtxSwapOrderResult result) {
        HtxRestResponse<HtxSwapOrderResult> response = new HtxRestResponse<>();
        response.setCode(code);
        response.setData(result);
        return response;
    }
}
