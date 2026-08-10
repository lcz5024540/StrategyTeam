package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAvailableLeverage;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxContractTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxContractTradingServiceTest {

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
        props.setMaxSingleQuoteNotional(new BigDecimal("1000"));
        props.setFillPollIntervalMs(0);
        props.setFillTimeoutMs(20);
        when(api.getSwapService()).thenReturn(swap);
        when(depthGuard.check(any(), any(), any(), anyInt()))
                .thenReturn(new HtxDepthGuard.Verdict(true, true,
                        new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("3")));
        when(recorder.hasActiveCycle(anyString())).thenReturn(false);
        when(recorder.createIntent(any(), anyString(), anyInt())).thenReturn(77L);
        service = new HtxContractTradingService(api, depthGuard, props, recorder);
    }

    @Test
    void configureLeverage_dryRunDoesNotCallPrivateApi() {
        HtxContractTradeResult result = service.configureLeverage(
                new HtxContractLeverageRequest("BTC-USDT", 4));

        assertEquals(HtxContractTradeResult.Code.LEVERAGE_DRY_RUN_OK, result.getCode());
        verify(swap, never()).switchLeverage(anyString(), anyInt());
    }

    @Test
    void leverageAboveConfiguredMaximumIsRejected() {
        HtxContractCycleRequest request = request();
        request.setLeverage(6);

        HtxContractTradeResult result = service.submitLongCycle(request);

        assertEquals(HtxContractTradeResult.Code.REJECT_LEVERAGE, result.getCode());
        verify(swap, never()).contractInfo(anyString());
    }

    @Test
    void requestCannotExtendConfiguredFillTimeout() {
        HtxContractCycleRequest request = request();
        request.setWaitFillTimeoutMs(props.getFillTimeoutMs() + 1);

        HtxContractTradeResult result = service.submitLongCycle(request);

        assertEquals(HtxContractTradeResult.Code.REJECT_INVALID_INPUT, result.getCode());
        verify(swap, never()).contractInfo(anyString());
    }

    @Test
    void xautUsdtIsInDefaultContractWhitelist() {
        HtxContractLeverageRequest request = new HtxContractLeverageRequest("xaut-usdt", 3);

        HtxContractTradeResult result = service.configureLeverage(request);

        assertEquals(HtxContractTradeResult.Code.LEVERAGE_DRY_RUN_OK, result.getCode());
        assertEquals("XAUT-USDT", result.getContractCode());
    }

    @Test
    void dryRunValidatesPublicRiskAndNeverCallsPrivateApi() {
        stubPublicPreflight();

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.DRY_RUN_OK, result.getCode());
        verify(swap, never()).positionInfo(anyString());
        verify(swap, never()).placeOrder(anyString(), anyLong(), anyLong(), anyString(), anyString(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void liveModeStillRequiresExplicitLiveGate() {
        props.setDryRun(false);
        stubPublicPreflight();

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.REJECT_LIVE_GATE, result.getCode());
        verify(swap, never()).positionInfo(anyString());
    }

    @Test
    void failedPositionQueryFailsClosed() {
        enableLive();
        stubPublicPreflight();
        stubNoOpenOrders();
        stubAvailableLeverage();
        when(swap.positionInfo("BTC-USDT")).thenReturn(error("io", "timeout"));

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.REJECT_PREFLIGHT, result.getCode());
        verify(swap, never()).accountInfo(anyString());
        verify(swap, never()).placeOrder(anyString(), anyLong(), anyLong(), anyString(), anyString(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void existingExchangeOrderPreventsAnotherOpen() {
        enableLive();
        stubPublicPreflight();
        HtxSwapOrder order = new HtxSwapOrder();
        order.setOrderId(42L);
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(List.of(order));
        when(swap.openOrders("BTC-USDT")).thenReturn(ok(openOrders));

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.REJECT_EXISTING_ORDER, result.getCode());
        verify(swap, never()).availableLeverage(anyString());
        verify(swap, never()).placeOrder(anyString(), anyLong(), anyLong(), anyString(), anyString(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void existingPositionPreventsManagedCycle() {
        enableLive();
        stubPublicPreflight();
        stubNoOpenOrders();
        stubAvailableLeverage();
        HtxSwapPosition position = new HtxSwapPosition();
        position.setVolume(BigDecimal.ONE);
        when(swap.positionInfo("BTC-USDT")).thenReturn(ok(List.of(position)));

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.REJECT_EXISTING_POSITION, result.getCode());
        verify(swap, never()).placeOrder(anyString(), anyLong(), anyLong(), anyString(), anyString(),
                anyInt(), anyString(), anyString());
    }

    @Test
    void unfinishedPersistedCyclePreventsAnotherOpen() {
        enableLive();
        stubPublicPreflight();
        when(recorder.hasActiveCycle("BTC-USDT")).thenReturn(true);

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.REJECT_PREFLIGHT, result.getCode());
        verify(swap, never()).availableLeverage(anyString());
    }

    @Test
    void persistenceReadFailurePreventsPrivateContractCalls() {
        enableLive();
        stubPublicPreflight();
        when(recorder.hasActiveCycle("BTC-USDT"))
                .thenThrow(new HtxPersistenceException("db down", new RuntimeException()));

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.REJECT_PERSISTENCE, result.getCode());
        verify(swap, never()).availableLeverage(anyString());
    }

    @Test
    void liveCycleOpensAndClosesAtConfiguredLeverage() {
        enableLive();
        stubFullPrivatePreflight();
        when(swap.placeOrder(eq("BTC-USDT"), eq(100L), eq(2L), eq("buy"), eq("open"),
                eq(4), eq("60000"), eq("limit"))).thenReturn(orderAccepted(11L));
        when(swap.queryOrder("BTC-USDT", 11L)).thenReturn(order(11L, 6, "2"));
        when(swap.placeOrder(eq("BTC-USDT"), eq(101L), eq(2L), eq("sell"), eq("close"),
                eq(4), eq("60100"), eq("limit"))).thenReturn(orderAccepted(12L));
        when(swap.queryOrder("BTC-USDT", 12L)).thenReturn(order(12L, 6, "2"));

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.OK, result.getCode());
        assertEquals(77L, result.getCycleId());
        assertEquals(new BigDecimal("2"), result.getOpenFilledVolume());
        assertEquals(new BigDecimal("2"), result.getCloseFilledVolume());
        verify(swap).switchLeverage("BTC-USDT", 4);
        verify(recorder).createIntent(any(), eq("BTC-USDT"), eq(4));
    }

    @Test
    void partialOpenIsCancelledThenOnlyActualFillIsClosed() {
        enableLive();
        props.setFillTimeoutMs(1);
        stubFullPrivatePreflight();
        when(swap.placeOrder(eq("BTC-USDT"), eq(100L), eq(2L), eq("buy"), eq("open"),
                eq(4), eq("60000"), eq("limit"))).thenReturn(orderAccepted(21L));
        when(swap.queryOrder("BTC-USDT", 21L))
                .thenReturn(order(21L, 4, "1"), order(21L, 5, "1"));
        when(swap.cancelOrder("BTC-USDT", 21L)).thenReturn(ok(Collections.emptyMap()));
        when(swap.placeOrder(eq("BTC-USDT"), eq(101L), eq(1L), eq("sell"), eq("close"),
                eq(4), eq("60100"), eq("limit"))).thenReturn(orderAccepted(22L));
        when(swap.queryOrder("BTC-USDT", 22L)).thenReturn(order(22L, 6, "1"));

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.OK, result.getCode());
        assertEquals(new BigDecimal("1"), result.getOpenFilledVolume());
        verify(swap).cancelOrder("BTC-USDT", 21L);
    }

    @Test
    void terminalPartialCloseReportsExposure() {
        enableLive();
        stubFullPrivatePreflight();
        when(swap.placeOrder(eq("BTC-USDT"), eq(100L), eq(2L), eq("buy"), eq("open"),
                eq(4), eq("60000"), eq("limit"))).thenReturn(orderAccepted(31L));
        when(swap.queryOrder("BTC-USDT", 31L)).thenReturn(order(31L, 6, "2"));
        when(swap.placeOrder(eq("BTC-USDT"), eq(101L), eq(2L), eq("sell"), eq("close"),
                eq(4), eq("60100"), eq("limit"))).thenReturn(orderAccepted(32L));
        when(swap.queryOrder("BTC-USDT", 32L)).thenReturn(order(32L, 5, "1"));

        HtxContractTradeResult result = service.submitLongCycle(request());

        assertEquals(HtxContractTradeResult.Code.EXPOSURE, result.getCode());
        assertTrue(result.getMessage().contains("flattening"));
    }

    private void enableLive() {
        props.setDryRun(false);
        props.setLiveEnabled(true);
    }

    private void stubPublicPreflight() {
        HtxSwapContractInfo contract = new HtxSwapContractInfo();
        contract.setContractCode("BTC-USDT");
        contract.setContractSize(new BigDecimal("0.001"));
        contract.setPriceTick(new BigDecimal("0.1"));
        contract.setContractStatus(1);
        contract.setSupportMarginMode("all");
        contract.setBusinessType("swap");
        when(swap.contractInfo("BTC-USDT")).thenReturn(ok(List.of(contract)));
        when(swap.depth("BTC-USDT", 6)).thenReturn(ok(new HtxDepth()));
    }

    private void stubAvailableLeverage() {
        HtxSwapAvailableLeverage available = new HtxSwapAvailableLeverage();
        available.setContractCode("BTC-USDT");
        available.setAvailableLevelRate("1,2,3,4,5");
        when(swap.availableLeverage("BTC-USDT")).thenReturn(ok(List.of(available)));
    }

    private void stubFullPrivatePreflight() {
        stubPublicPreflight();
        stubNoOpenOrders();
        stubAvailableLeverage();
        when(swap.positionInfo("BTC-USDT")).thenReturn(ok(Collections.emptyList()));
        HtxSwapAccount account = new HtxSwapAccount();
        account.setContractCode("BTC-USDT");
        account.setMarginAvailable(new BigDecimal("1000"));
        account.setMarginMode("isolated");
        account.setPositionMode("dual_side");
        account.setLeverage(3);
        when(swap.accountInfo("BTC-USDT")).thenReturn(ok(List.of(account)));
        HtxSwapLeverageResult leverage = new HtxSwapLeverageResult();
        leverage.setLeverage(4);
        when(swap.switchLeverage("BTC-USDT", 4)).thenReturn(ok(leverage));
    }

    private void stubNoOpenOrders() {
        HtxSwapOpenOrders openOrders = new HtxSwapOpenOrders();
        openOrders.setOrders(Collections.emptyList());
        when(swap.openOrders("BTC-USDT")).thenReturn(ok(openOrders));
    }

    private static HtxContractCycleRequest request() {
        return HtxContractCycleRequest.builder()
                .contractCode("BTC-USDT")
                .volume(2L)
                .openPrice(new BigDecimal("60000"))
                .closePrice(new BigDecimal("60100"))
                .leverage(4)
                .clientOrderId(100L)
                .build();
    }

    private static HtxRestResponse<HtxSwapOrderResult> orderAccepted(long orderId) {
        HtxSwapOrderResult result = new HtxSwapOrderResult();
        result.setOrderId(orderId);
        return ok(result);
    }

    private static HtxRestResponse<List<HtxSwapOrder>> order(long orderId, int status, String tradeVolume) {
        HtxSwapOrder result = new HtxSwapOrder();
        result.setOrderId(orderId);
        result.setStatus(status);
        result.setTradeVolume(new BigDecimal(tradeVolume));
        return ok(List.of(result));
    }

    private static <T> HtxRestResponse<T> ok(T data) {
        return HtxRestResponse.<T>builder().status("ok").data(data).build();
    }

    private static <T> HtxRestResponse<T> error(String code, String message) {
        return HtxRestResponse.<T>builder().status("error").errCode(code).errMsg(message).build();
    }
}
