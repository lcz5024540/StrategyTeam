package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTradingServiceTest {

    private HtxApiService api;
    private HtxSpotService spot;
    private HtxMarketDataService marketData;
    private HtxTradingService service;
    private HtxTradeProperties props;
    private HtxLockedPairExecutor executor;

    @BeforeEach
    void setUp() {
        api = mock(HtxApiService.class);
        spot = mock(HtxSpotService.class);
        marketData = mock(HtxMarketDataService.class);
        when(api.getSpotService()).thenReturn(spot);
        when(api.getMarketDataService()).thenReturn(marketData);

        props = new HtxTradeProperties();
        props.getApi().setSpotAccountId("111");
        props.getDepthGuard().setTopN(5);
        props.getDepthGuard().setSafetyRatio(new BigDecimal("3.0"));
        props.getDepthGuard().setMinQuoteNotional(new BigDecimal("20"));
        props.getTrade().setFillPollIntervalMs(1);
        props.getTrade().setFillTimeoutMs(50);
        props.getTrade().setQueryFailureThreshold(3);   // keep tests fast

        executor = new HtxLockedPairExecutor(api, props);
        service = new HtxTradingService(api, new HtxDepthGuard(), executor, props);
    }

    // ================= pre-check tests =================

    @Test
    void rejectsWhenBuyPriceExceedsSellPrice() {
        HtxLockedPairResult r = service.submitLockedPair(HtxLockedPairRequest.builder()
                .symbol("btcusdt").quantity(new BigDecimal("0.1"))
                .buyPrice(new BigDecimal("101")).sellPrice(new BigDecimal("100")).build());
        assertEquals(HtxLockedPairResult.Code.REJECT_PRICE_INVERSION, r.getCode());
        verify(marketData, never()).depth(anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsWhenDepthIsInsufficient() {
        when(marketData.depth(eq("btcusdt"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(okDepth(
                levels("100.0", "0.5"), levels("100.1", "0.5")));
        HtxLockedPairResult r = service.submitLockedPair(reqBase().build());
        assertEquals(HtxLockedPairResult.Code.REJECT_DEPTH_BOTH, r.getCode());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void dryRunPathDoesNotHitPlaceOrder() {
        props.getTrade().setEnabled(false);
        stubDepthOk();
        HtxLockedPairResult r = service.submitLockedPair(reqBase().build());
        assertEquals(HtxLockedPairResult.Code.DRY_RUN_OK, r.getCode());
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ================= live BUY path tests =================

    @Test
    void liveHappyPathBothLegsFilled() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        stubPlaceBuy(1001L);
        when(spot.queryOrder(1001L)).thenReturn(okOrder(state("filled", "1")));
        stubPlaceSell(2002L);
        when(spot.queryOrder(2002L)).thenReturn(okOrder(state("filled", "1")));

        HtxLockedPairResult r = service.submitLockedPair(reqBase().build());
        assertEquals(HtxLockedPairResult.Code.OK, r.getCode());
        assertEquals(1001L, r.getBuyOrderId());
        assertEquals(2002L, r.getSellOrderId());
        assertEquals(new BigDecimal("1"), r.getBuyFilledAmount());
        assertEquals(new BigDecimal("1"), r.getSellFilledAmount());
        verify(spot, times(1)).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), isNull());
        verify(spot, times(1)).placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), isNull());
    }

    @Test
    void liveTimeoutWithZeroFillCancelsAndReturnsTimeout() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        stubPlaceBuy(1001L);
        when(spot.queryOrder(1001L)).thenReturn(okOrder(state("submitted", "0")));
        when(spot.cancelOrder(anyLong())).thenReturn(okString("1001"));

        HtxLockedPairResult r = service.submitLockedPair(reqBase().waitFillTimeoutMs(20L).build());
        assertEquals(HtxLockedPairResult.Code.BUY_WAIT_TIMEOUT, r.getCode());
        assertEquals(BigDecimal.ZERO, r.getBuyFilledAmount());
        verify(spot).cancelOrder(1001L);
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    // ================= HOTFIX #1 · partial-filled recovery =================

    @Test
    void hotfix1_partialFillOnBuyRecoversWithSmallerSell() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        stubPlaceBuy(1001L);
        // buy stays "partial-filled" with filledAmount=0.3 the whole time
        when(spot.queryOrder(1001L)).thenReturn(okOrder(state("partial-filled", "0.3")));
        when(spot.cancelOrder(anyLong())).thenReturn(okString("1001"));
        // sell for the 0.3 that actually filled — placed successfully and filled
        stubPlaceSell(2002L);
        when(spot.queryOrder(2002L)).thenReturn(okOrder(state("filled", "0.3")));

        HtxLockedPairResult r = service.submitLockedPair(reqBase()
                .quantity(new BigDecimal("1"))
                .waitFillTimeoutMs(20L)
                .build());
        assertEquals(HtxLockedPairResult.Code.BUY_PARTIAL_FILL_RECOVERED, r.getCode());
        assertEquals(new BigDecimal("0.3"), r.getBuyFilledAmount());
        assertEquals(new BigDecimal("0.3"), r.getSellFilledAmount());

        // sell placeOrder must have been called with the *filled* amount, not the original qty
        ArgumentCaptor<String> amountCap = ArgumentCaptor.forClass(String.class);
        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit"), amountCap.capture(), anyString(), org.mockito.ArgumentMatchers.any());
        assertEquals("0.3", amountCap.getValue());
        verify(spot).cancelOrder(1001L);   // remainder cancelled
    }

    // ================= HOTFIX #2 · SELL fill confirmation =================

    @Test
    void hotfix2_sellPlacedButStillOpenReturnsPending() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        stubPlaceBuy(1001L);
        when(spot.queryOrder(1001L)).thenReturn(okOrder(state("filled", "1")));
        stubPlaceSell(2002L);
        // sell never reaches terminal — remains "submitted"
        when(spot.queryOrder(2002L)).thenReturn(okOrder(state("submitted", "0")));

        HtxLockedPairResult r = service.submitLockedPair(reqBase().waitFillTimeoutMs(20L).build());
        assertEquals(HtxLockedPairResult.Code.SELL_PENDING, r.getCode());
        assertEquals(1001L, r.getBuyOrderId());
        assertEquals(2002L, r.getSellOrderId());
        assertEquals(new BigDecimal("1"), r.getBuyFilledAmount());
        assertEquals(BigDecimal.ZERO, r.getSellFilledAmount());
    }

    @Test
    void hotfix2_sellTerminalRejectedAfterBuyFilledExposesLeg() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        stubPlaceBuy(1001L);
        when(spot.queryOrder(1001L)).thenReturn(okOrder(state("filled", "1")));
        stubPlaceSell(2002L);
        // sell reaches "rejected" with 0 fill
        when(spot.queryOrder(2002L)).thenReturn(okOrder(state("rejected", "0")));

        HtxLockedPairResult r = service.submitLockedPair(reqBase().waitFillTimeoutMs(20L).build());
        assertEquals(HtxLockedPairResult.Code.FAIL_AFTER_BUY_FILL, r.getCode());
        assertEquals(1001L, r.getBuyOrderId());
        assertEquals(2002L, r.getSellOrderId());
        assertEquals(new BigDecimal("1"), r.getBuyFilledAmount());
    }

    // ================= HOTFIX #4 · placeOrder String parsing =================

    @Test
    void hotfix4_nonNumericPlaceOrderDataFailsClean() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        HtxRestResponse<String> bad = new HtxRestResponse<>();
        bad.setStatus("ok");
        bad.setData("hf_notNumber");  // simulate high-freq prefix / bogus value
        when(spot.placeOrder(eq("111"), eq("btcusdt"), eq("buy-limit"), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(bad);

        HtxLockedPairResult r = service.submitLockedPair(reqBase().build());
        assertEquals(HtxLockedPairResult.Code.BUY_ORDER_REJECTED, r.getCode());
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    // ================= HOTFIX #7 · clientOrderId idempotency =================

    @Test
    void hotfix7_clientOrderIdIsForwardedWithPerLegSuffix() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        stubPlaceBuy(1001L);
        when(spot.queryOrder(1001L)).thenReturn(okOrder(state("filled", "1")));
        stubPlaceSell(2002L);
        when(spot.queryOrder(2002L)).thenReturn(okOrder(state("filled", "1")));

        service.submitLockedPair(reqBase().clientOrderId("cor-abc").build());

        ArgumentCaptor<String> buyCoid = ArgumentCaptor.forClass(String.class);
        verify(spot).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), buyCoid.capture());
        assertEquals("cor-abc-BUY", buyCoid.getValue());

        ArgumentCaptor<String> sellCoid = ArgumentCaptor.forClass(String.class);
        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), sellCoid.capture());
        assertEquals("cor-abc-SELL", sellCoid.getValue());
    }

    @Test
    void hotfix7_noClientOrderIdKeepsHtxSideDedupOff() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        stubPlaceBuy(1001L);
        when(spot.queryOrder(1001L)).thenReturn(okOrder(state("filled", "1")));
        stubPlaceSell(2002L);
        when(spot.queryOrder(2002L)).thenReturn(okOrder(state("filled", "1")));

        service.submitLockedPair(reqBase().build());   // no clientOrderId

        verify(spot).placeOrder(anyString(), anyString(), eq("buy-limit"), anyString(), anyString(), isNull());
        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), isNull());
    }

    // ================= HOTFIX v1.0.2 =================

    @Test
    void hotfix3_minNotionalBuyLegBelowFloorRejects() {
        // qty=0.001, sellPrice=25000 → sell side = 25 (>20 floor)
        //           buyPrice=15000  → buy side  = 15 (<20 floor) → weaker leg fails
        HtxLockedPairResult r = service.submitLockedPair(HtxLockedPairRequest.builder()
                .symbol("btcusdt")
                .quantity(new BigDecimal("0.001"))
                .buyPrice(new BigDecimal("15000"))
                .sellPrice(new BigDecimal("25000"))
                .build());
        assertEquals(HtxLockedPairResult.Code.REJECT_MIN_NOTIONAL, r.getCode());
        verify(marketData, never()).depth(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void hotfix5_queryOrderInfraFailuresTripCircuitBreaker() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        stubPlaceBuy(1001L);
        // queryOrder always returns non-ok — simulate HTX 5xx / gateway timeout
        HtxRestResponse<HtxOrder> broken = new HtxRestResponse<>();
        broken.setStatus("error");
        broken.setErrCode("api-signature-not-valid");
        broken.setErrMsg("gateway timeout");
        when(spot.queryOrder(1001L)).thenReturn(broken);

        HtxLockedPairResult r = service.submitLockedPair(reqBase().build());
        assertEquals(HtxLockedPairResult.Code.QUERY_INFRA_ERROR, r.getCode());
        assertEquals(1001L, r.getBuyOrderId());
        // critical: circuit trip must NOT cancel a possibly-filled order
        verify(spot, never()).cancelOrder(anyLong());
        // critical: also must NOT place a sell leg
        verify(spot, never()).placeOrder(anyString(), anyString(), eq("sell-limit"), anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void hotfix6_cancelRaceRecoversFillsInsteadOfReturningTimeout() {
        props.getTrade().setEnabled(true);
        stubDepthOk();
        stubPlaceBuy(1001L);
        // during the wait loop: still "submitted" with 0 filled → will hit timeout branch
        // after cancel: query reveals the order actually filled 0.7 in the race window
        HtxRestResponse<HtxOrder> preCancel = okOrder(state("submitted", "0"));
        HtxRestResponse<HtxOrder> postCancel = okOrder(state("filled", "0.7"));
        when(spot.queryOrder(1001L)).thenReturn(preCancel, preCancel, postCancel);
        when(spot.cancelOrder(anyLong())).thenReturn(okString("1001"));
        stubPlaceSell(2002L);
        when(spot.queryOrder(2002L)).thenReturn(okOrder(state("filled", "0.7")));

        HtxLockedPairResult r = service.submitLockedPair(reqBase()
                .quantity(new BigDecimal("1"))
                .waitFillTimeoutMs(3L)   // trip timeout fast
                .build());
        // hotfix #6 kicked in: cancel-race recovery — treat as partial (always use actually-filled amount)
        assertEquals(HtxLockedPairResult.Code.BUY_PARTIAL_FILL_RECOVERED, r.getCode());
        assertEquals(new BigDecimal("0.7"), r.getBuyFilledAmount());
        // sell leg must have been placed for 0.7 (what actually filled), not 1 (request qty)
        ArgumentCaptor<String> sellAmount = ArgumentCaptor.forClass(String.class);
        verify(spot).placeOrder(anyString(), anyString(), eq("sell-limit"), sellAmount.capture(), anyString(), org.mockito.ArgumentMatchers.any());
        assertEquals("0.7", sellAmount.getValue());
    }

    @Test
    void hotfix10_unsortedAsksRejectsAsDepthBoth() {
        props.getTrade().setEnabled(true);
        // asks NOT ascending (second row cheaper than first) — invariant violation
        HtxRestResponse<HtxDepth> bad = okDepth(
                levels("100.0", "10", "99.5", "10"),   // bids: descending OK
                levels("100.5", "10", "100.1", "10")); // asks: descending — WRONG
        when(marketData.depth(eq("btcusdt"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(bad);

        HtxLockedPairResult r = service.submitLockedPair(reqBase().build());
        assertEquals(HtxLockedPairResult.Code.REJECT_DEPTH_BOTH, r.getCode());
        // ordering violation must NOT reach placeOrder
        verify(spot, never()).placeOrder(anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    // ================= helpers =================

    private HtxLockedPairRequest.HtxLockedPairRequestBuilder reqBase() {
        return HtxLockedPairRequest.builder()
                .symbol("btcusdt")
                .quantity(new BigDecimal("1"))
                .buyPrice(new BigDecimal("99"))
                .sellPrice(new BigDecimal("101"));
    }

    private void stubDepthOk() {
        when(marketData.depth(eq("btcusdt"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(okDepth(
                levels("100.0", "10"), levels("100.1", "10")));
    }

    private void stubPlaceBuy(long id) {
        when(spot.placeOrder(eq("111"), eq("btcusdt"), eq("buy-limit"), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(okString(String.valueOf(id)));
    }

    private void stubPlaceSell(long id) {
        when(spot.placeOrder(eq("111"), eq("btcusdt"), eq("sell-limit"), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(okString(String.valueOf(id)));
    }

    private static HtxRestResponse<String> okString(String data) {
        HtxRestResponse<String> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(data);
        return r;
    }

    private static HtxRestResponse<HtxOrder> okOrder(HtxOrder o) {
        HtxRestResponse<HtxOrder> r = new HtxRestResponse<>();
        r.setStatus("ok");
        r.setData(o);
        return r;
    }

    private static HtxOrder state(String s, String filled) {
        HtxOrder o = new HtxOrder();
        o.setState(s);
        o.setFilledAmount(new BigDecimal(filled));
        return o;
    }

    private static HtxRestResponse<HtxDepth> okDepth(List<HtxPriceLevel> bids, List<HtxPriceLevel> asks) {
        HtxDepth depth = new HtxDepth();
        depth.setSymbol("btcusdt");
        depth.setBids(bids);
        depth.setAsks(asks);
        HtxRestResponse<HtxDepth> resp = new HtxRestResponse<>();
        resp.setStatus("ok");
        resp.setTick(depth);
        return resp;
    }

    private static List<HtxPriceLevel> levels(String... pairs) {
        HtxPriceLevel[] arr = new HtxPriceLevel[pairs.length / 2];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new HtxPriceLevel(new BigDecimal(pairs[2 * i]), new BigDecimal(pairs[2 * i + 1]));
        }
        return Arrays.asList(arr);
    }
}
