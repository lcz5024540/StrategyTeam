package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAvailableLeverage;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxContractTradeRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxContractDualTradingServiceTest {

    private HtxUsdtSwapService swap;
    private HtxDepthGuard depthGuard;
    private HtxContractTradeRecorder recorder;
    private HtxContractProperties props;
    private HtxContractTradingService service;

    @BeforeEach
    void setUp() {
        HtxUsdtSwapApiService api = mock(HtxUsdtSwapApiService.class);
        swap = mock(HtxUsdtSwapService.class);
        depthGuard = mock(HtxDepthGuard.class);
        recorder = mock(HtxContractTradeRecorder.class);
        props = new HtxContractProperties();
        props.setContractCodes(List.of("XAUT-USDT"));
        props.setMaxSingleQuoteNotional(new BigDecimal("100000"));
        props.setFillPollIntervalMs(0L);
        props.setFillTimeoutMs(20L);
        when(api.getSwapService()).thenReturn(swap);
        when(recorder.hasActiveCycle("XAUT-USDT")).thenReturn(false);
        when(recorder.createIntent(any(), eq("XAUT-USDT"), eq(5))).thenReturn(77L);
        when(depthGuard.checkSides(any(), any(), any(), any(), anyInt())).thenReturn(depthOk());
        service = new HtxContractTradingService(api, depthGuard, props, recorder);
    }

    @Test
    void dryRunValidatesDepthButDoesNotCallPrivateV5Endpoints() {
        stubPublicPreflight();

        HtxContractTradeResult result = service.submitDualCycle(request());

        assertEquals(HtxContractTradeResult.Code.DRY_RUN_OK, result.getCode());
        verify(depthGuard).checkSides(any(), eq(new BigDecimal("2")), eq(new BigDecimal("2")), any(), anyInt());
        verify(swap, never()).openOrders(anyString());
        verify(swap, never()).batchOrders(any());
    }

    @Test
    void liveDualCycleUsesEqualPricesReduceOnlyClosesAndAggregatesFees() {
        enableLive();
        stubPrivatePreflight();
        when(swap.batchOrders(any())).thenReturn(
                ok(List.of(accepted(11L, 100L), accepted(12L, 101L))),
                ok(List.of(accepted(13L, 102L), accepted(14L, 103L))));
        when(swap.queryOrder("XAUT-USDT", 11L)).thenReturn(filled(11L, "2", "0.10", "0"));
        when(swap.queryOrder("XAUT-USDT", 12L)).thenReturn(filled(12L, "2", "0.10", "0"));
        when(swap.queryOrder("XAUT-USDT", 13L)).thenReturn(filled(13L, "2", "0.10", "1.25"));
        when(swap.queryOrder("XAUT-USDT", 14L)).thenReturn(filled(14L, "2", "0.10", "-1.25"));

        HtxContractTradeResult result = service.submitDualCycle(request());

        assertEquals(HtxContractTradeResult.Code.OK, result.getCode());
        assertEquals(11L, result.getLongOpenOrderId());
        assertEquals(12L, result.getShortOpenOrderId());
        assertEquals(13L, result.getLongCloseOrderId());
        assertEquals(14L, result.getShortCloseOrderId());
        assertEquals(new BigDecimal("0.40"), result.getTotalFee());
        assertEquals(new BigDecimal("0.00"), result.getRealizedProfit());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HtxSwapOrderRequest>> batches = ArgumentCaptor.forClass(List.class);
        verify(swap, times(2)).batchOrders(batches.capture());
        List<HtxSwapOrderRequest> opens = batches.getAllValues().get(0);
        List<HtxSwapOrderRequest> closes = batches.getAllValues().get(1);
        assertEquals("4333.33", opens.get(0).getPrice());
        assertEquals("4333.33", opens.get(1).getPrice());
        assertEquals(List.of("long", "short"), opens.stream().map(HtxSwapOrderRequest::getPositionSide).toList());
        assertTrue(opens.stream().noneMatch(order -> Boolean.TRUE.equals(order.getReduceOnly())));
        assertEquals("4359.33", closes.get(0).getPrice());
        assertEquals("4359.33", closes.get(1).getPrice());
        assertTrue(closes.stream().allMatch(order -> Boolean.TRUE.equals(order.getReduceOnly())));
        assertTrue(closes.stream().allMatch(order -> "cancel_both".equals(order.getSelfMatchPrevent())));
        verify(recorder, times(4)).createLegIntent(eq(77L), anyString(), any(Long.class),
                anyString(), anyString(), any(Boolean.class), any(Long.class), any(BigDecimal.class));
        verify(swap).switchLeverage("XAUT-USDT", "long", 5);
        verify(swap).switchLeverage("XAUT-USDT", "short", 5);
    }

    @Test
    void closeDepthFailureLeavesExposureAndDoesNotSendCloseBatch() {
        enableLive();
        stubPrivatePreflight();
        when(depthGuard.checkSides(any(), any(), any(), any(), anyInt()))
                .thenReturn(depthOk(), depthRejected());
        when(swap.batchOrders(any())).thenReturn(
                ok(List.of(accepted(21L, 100L), accepted(22L, 101L))));
        when(swap.queryOrder("XAUT-USDT", 21L)).thenReturn(filled(21L, "2", "0.1", "0"));
        when(swap.queryOrder("XAUT-USDT", 22L)).thenReturn(filled(22L, "2", "0.1", "0"));

        HtxContractTradeResult result = service.submitDualCycle(request());

        assertEquals(HtxContractTradeResult.Code.EXPOSURE, result.getCode());
        assertTrue(result.getMessage().contains("close depth rejected"));
        verify(swap, times(1)).batchOrders(any());
        verify(depthGuard, times(2)).checkSides(any(), any(), any(), any(), anyInt());
    }

    @Test
    void oneRejectedOpenLegIsFlattenedButCycleIsNotReportedSuccessful() {
        enableLive();
        stubPrivatePreflight();
        HtxSwapOrderResult rejected = new HtxSwapOrderResult();
        rejected.setCode(400);
        rejected.setMessage("rejected");
        rejected.setClientOrderId(101L);
        when(swap.batchOrders(any())).thenReturn(
                ok(List.of(accepted(31L, 100L), rejected)),
                ok(List.of(accepted(33L, 102L))));
        when(swap.queryOrder("XAUT-USDT", 31L)).thenReturn(filled(31L, "2", "0.1", "0"));
        when(swap.queryOrder("XAUT-USDT", 33L)).thenReturn(filled(33L, "2", "0.1", "0"));

        HtxContractTradeResult result = service.submitDualCycle(request());

        assertEquals(HtxContractTradeResult.Code.OPEN_ORDER_REJECTED, result.getCode());
        assertFalse(result.success());
        assertEquals("asymmetric open safely flattened", result.getMessage());
        verify(swap, times(2)).batchOrders(any());
    }

    @Test
    void missingOpenBatchAcknowledgementBlocksCycleAsUnknown() {
        enableLive();
        stubPrivatePreflight();
        when(swap.batchOrders(any())).thenReturn(HtxRestResponse.<List<HtxSwapOrderResult>>builder()
                .status("error").errCode("io").errMsg("timeout").build());

        HtxContractTradeResult result = service.submitDualCycle(request());

        assertEquals(HtxContractTradeResult.Code.QUERY_INFRA_ERROR, result.getCode());
        assertTrue(result.getMessage().contains("acknowledgement is unknown"));
        verify(recorder).update(eq(77L), eq(HtxContractTradeRecorder.OPEN_UNKNOWN),
                any(), any(), any(), any(), anyString());
        verify(swap, never()).queryOrder(anyString(), any(Long.class));
    }

    @Test
    void openOrderStillActiveAfterCancelBlocksCycleAsUnknown() {
        enableLive();
        stubPrivatePreflight();
        when(swap.batchOrders(any())).thenReturn(
                ok(List.of(accepted(41L, 100L), accepted(42L, 101L))));
        when(swap.queryOrder(eq("XAUT-USDT"), any(Long.class))).thenReturn(openOrder());
        HtxContractCycleRequest request = request();
        request.setWaitFillTimeoutMs(1L);

        HtxContractTradeResult result = service.submitDualCycle(request);

        assertEquals(HtxContractTradeResult.Code.QUERY_INFRA_ERROR, result.getCode());
        assertTrue(result.getMessage().contains("open state unknown"));
        verify(swap).cancelOrder("XAUT-USDT", 41L);
        verify(swap).cancelOrder("XAUT-USDT", 42L);
        verify(recorder).update(eq(77L), eq(HtxContractTradeRecorder.EXPOSURE),
                any(), any(), any(), any(), anyString());
    }

    private void enableLive() {
        props.setDryRun(false);
        props.setLiveEnabled(true);
    }

    private void stubPublicPreflight() {
        HtxSwapContractInfo contract = new HtxSwapContractInfo();
        contract.setContractCode("XAUT-USDT");
        contract.setContractSize(new BigDecimal("0.001"));
        contract.setPriceTick(new BigDecimal("0.01"));
        contract.setContractStatus(1);
        contract.setSupportMarginMode("all");
        contract.setBusinessType("swap");
        when(swap.contractInfo("XAUT-USDT")).thenReturn(ok(List.of(contract)));
        when(swap.depth("XAUT-USDT", 6)).thenReturn(ok(new HtxDepth()));
    }

    private void stubPrivatePreflight() {
        stubPublicPreflight();
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(Collections.emptyList());
        when(swap.openOrders("XAUT-USDT")).thenReturn(ok(openOrders));
        when(swap.positionInfo("XAUT-USDT")).thenReturn(ok(Collections.emptyList()));
        HtxSwapAccount account = new HtxSwapAccount();
        account.setContractCode("XAUT-USDT");
        account.setState("normal");
        account.setMarginAvailable(new BigDecimal("1000"));
        when(swap.accountInfo("XAUT-USDT")).thenReturn(ok(List.of(account)));
        when(swap.availableLeverage("XAUT-USDT")).thenReturn(ok(List.of(leverage("long"))));
        when(swap.availableLeverage("XAUT-USDT", "short")).thenReturn(ok(List.of(leverage("short"))));
        when(swap.switchLeverage(eq("XAUT-USDT"), anyString(), eq(5)))
                .thenReturn(ok(new HtxSwapLeverageResult()));
    }

    private static HtxSwapAvailableLeverage leverage(String positionSide) {
        HtxSwapAvailableLeverage leverage = new HtxSwapAvailableLeverage();
        leverage.setContractCode("XAUT-USDT");
        leverage.setPositionSide(positionSide);
        leverage.setAvailableLevelRate("1,3,5");
        return leverage;
    }

    private static HtxContractCycleRequest request() {
        return HtxContractCycleRequest.builder()
                .contractCode("XAUT-USDT")
                .volume(2L)
                .openPrice(new BigDecimal("4333.33"))
                .closePrice(new BigDecimal("4359.33"))
                .leverage(5)
                .clientOrderId(100L)
                .build();
    }

    private static HtxSwapOrderResult accepted(long orderId, long clientOrderId) {
        HtxSwapOrderResult result = new HtxSwapOrderResult();
        result.setCode(200);
        result.setOrderId(orderId);
        result.setClientOrderId(clientOrderId);
        return result;
    }

    private static HtxRestResponse<List<HtxSwapOrder>> filled(
            long orderId, String volume, String fee, String profit) {
        HtxSwapOrder order = new HtxSwapOrder();
        order.setOrderId(orderId);
        order.setState("filled");
        order.setTradeVolume(new BigDecimal(volume));
        order.setTradeAveragePrice(new BigDecimal("4333.33"));
        order.setFee(new BigDecimal(fee));
        order.setFeeCurrency("USDT");
        order.setProfit(new BigDecimal(profit));
        return ok(List.of(order));
    }

    private static HtxRestResponse<List<HtxSwapOrder>> openOrder() {
        HtxSwapOrder order = new HtxSwapOrder();
        order.setState("new");
        order.setTradeVolume(BigDecimal.ZERO);
        return ok(List.of(order));
    }

    private static HtxDepthGuard.Verdict depthOk() {
        return new HtxDepthGuard.Verdict(true, true,
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("6"));
    }

    private static HtxDepthGuard.Verdict depthRejected() {
        return new HtxDepthGuard.Verdict(false, false,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("6"));
    }

    private static <T> HtxRestResponse<T> ok(T data) {
        return HtxRestResponse.<T>builder().status("ok").data(data).build();
    }
}
