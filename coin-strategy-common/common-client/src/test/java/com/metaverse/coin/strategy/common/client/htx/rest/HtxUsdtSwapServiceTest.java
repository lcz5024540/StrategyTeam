package com.metaverse.coin.strategy.common.client.htx.rest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAvailableLeverage;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxUsdtSwapServiceTest {

    private MockWebServer server;
    private HtxUsdtSwapService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new HtxUsdtSwapService(
                new HtxRestClient("access", "secret", server.url("/").toString()),
                new HtxUidRateLimiter(100, 100, 1_000));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void placeOrderUsesV5FieldsAndDoesNotSendLegacyOffsetOrLeverage() throws InterruptedException {
        server.enqueue(json("{\"code\":200,\"message\":\"Success\","
                + "\"data\":{\"order_id\":\"123\",\"client_order_id\":\"456\"}}"));

        HtxRestResponse<HtxSwapOrderResult> response = service.placeOrder(
                "BTC-USDT", 456L, 2L, "buy", "open", 3, "60000.0", "limit");

        assertTrue(response.ok());
        assertEquals(123L, response.getData().getOrderId());
        RecordedRequest request = takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/v5/trade/order", request.getRequestUrl().encodedPath());
        assertNotNull(request.getRequestUrl().queryParameter("Signature"));
        JSONObject body = JSON.parseObject(request.getBody().readUtf8());
        assertEquals("BTC-USDT", body.getString("contract_code"));
        assertEquals("456", body.getString("client_order_id"));
        assertEquals("2", body.getString("volume"));
        assertEquals("buy", body.getString("side"));
        assertEquals("long", body.getString("position_side"));
        assertEquals("isolated", body.getString("margin_mode"));
        assertEquals(0, body.getIntValue("reduce_only"));
        assertEquals("cancel_both", body.getString("self_match_prevent"));
        assertFalse(body.containsKey("offset"));
        assertFalse(body.containsKey("lever_rate"));
    }

    @Test
    void batchOrdersPreserveSamePriceAndOppositePositionSides() throws InterruptedException {
        server.enqueue(json("{\"code\":200,\"message\":\"Success\",\"data\":["
                + "{\"code\":200,\"order_id\":\"11\",\"client_order_id\":\"100\"},"
                + "{\"code\":200,\"order_id\":\"12\",\"client_order_id\":\"101\"}]}"));
        List<HtxSwapOrderRequest> orders = List.of(
                HtxUsdtSwapService.orderRequest("XAUT-USDT", 100L, 2L,
                        "buy", "long", false, "4333.40", "limit"),
                HtxUsdtSwapService.orderRequest("XAUT-USDT", 101L, 2L,
                        "sell", "short", false, "4333.40", "limit"));

        HtxRestResponse<List<HtxSwapOrderResult>> response = service.batchOrders(orders);

        assertTrue(response.ok());
        assertEquals(List.of(11L, 12L), response.getData().stream()
                .map(HtxSwapOrderResult::getOrderId).toList());
        RecordedRequest request = takeRequest();
        assertEquals("/v5/trade/batch_orders", request.getRequestUrl().encodedPath());
        JSONArray body = JSON.parseArray(request.getBody().readUtf8());
        assertEquals("4333.40", body.getJSONObject(0).getString("price"));
        assertEquals("4333.40", body.getJSONObject(1).getString("price"));
        assertEquals("long", body.getJSONObject(0).getString("position_side"));
        assertEquals("short", body.getJSONObject(1).getString("position_side"));
        assertEquals(0, body.getJSONObject(0).get("reduce_only"));
        assertEquals(0, body.getJSONObject(1).get("reduce_only"));
        assertEquals("cancel_both", body.getJSONObject(0).getString("self_match_prevent"));
    }

    @Test
    void depthNormalizesTickAndUsesLegacyPublicPath() throws InterruptedException {
        server.enqueue(json("{\"ch\":\"market.BTC-USDT.depth.step6\",\"tick\":{"
                + "\"asks\":[[60001,5]],\"bids\":[[60000,7]]}}"));

        HtxRestResponse<HtxDepth> response = service.depth("BTC-USDT", 6);

        assertTrue(response.ok());
        assertEquals(new BigDecimal("5"), response.payload().getAsks().get(0).getAmount());
        RecordedRequest request = takeRequest();
        assertEquals("/linear-swap-ex/market/depth", request.getRequestUrl().encodedPath());
        assertEquals("step6", request.getRequestUrl().queryParameter("type"));
    }

    @Test
    void queryOrderMapsV5StateFeeAndProfit() throws InterruptedException {
        server.enqueue(json("{\"code\":200,\"message\":\"Success\",\"data\":{"
                + "\"order_id\":\"123\",\"state\":\"filled\",\"position_side\":\"long\","
                + "\"trade_volume\":\"2\",\"trade_avg_price\":\"60000.5\","
                + "\"fee\":\"0.12\",\"fee_currency\":\"USDT\",\"profit\":\"1.5\"}}"));

        HtxRestResponse<List<HtxSwapOrder>> response = service.queryOrder("BTC-USDT", 123L);

        HtxSwapOrder order = response.getData().get(0);
        assertEquals("filled", order.getState());
        assertEquals(6, order.getStatus());
        assertEquals(new BigDecimal("2"), order.getTradeVolume());
        assertEquals(new BigDecimal("0.12"), order.getFee());
        assertEquals(new BigDecimal("1.5"), order.getProfit());
        RecordedRequest request = takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/v5/trade/order", request.getRequestUrl().encodedPath());
        assertEquals("123", request.getRequestUrl().queryParameter("order_id"));
        assertEquals("isolated", request.getRequestUrl().queryParameter("margin_mode"));
    }

    @Test
    void accountBalanceMapsQuoteCurrencyAndNormalState() {
        server.enqueue(json("{\"code\":200,\"message\":\"Success\",\"data\":{"
                + "\"state\":\"normal\",\"details\":[{\"currency\":\"USDT\","
                + "\"available\":\"93.64\",\"isolated_equity\":\"10\"}]}}"));

        HtxRestResponse<List<HtxSwapAccount>> response = service.accountInfo("XAUT-USDT");

        HtxSwapAccount account = response.getData().get(0);
        assertEquals("XAUT-USDT", account.getContractCode());
        assertEquals("USDT", account.getCurrency());
        assertEquals("normal", account.getState());
        assertEquals(new BigDecimal("93.64"), account.getMarginAvailable());
    }

    @Test
    void leverageQueryUsesPositionSideAndMapsAvailableList() throws InterruptedException {
        server.enqueue(json("{\"code\":200,\"message\":\"Success\",\"data\":[{"
                + "\"contract_code\":\"XAUT-USDT\",\"margin_mode\":\"isolated\","
                + "\"position_side\":\"short\",\"lever_rate\":5,\"available_lever\":[1,3,5]}]}"));

        HtxRestResponse<List<HtxSwapAvailableLeverage>> response =
                service.availableLeverage("XAUT-USDT", "short");

        assertEquals("1,3,5", response.getData().get(0).getAvailableLevelRate());
        RecordedRequest request = takeRequest();
        assertEquals("/v5/position/lever", request.getRequestUrl().encodedPath());
        assertEquals("short", request.getRequestUrl().queryParameter("position_side"));
    }

    @Test
    void openOrdersUsesV5GetAndMapsFlatDataArray() throws InterruptedException {
        server.enqueue(json("{\"code\":200,\"message\":\"Success\",\"data\":[{"
                + "\"order_id\":\"123\",\"contract_code\":\"XAUT-USDT\",\"state\":\"new\"}]}"));

        HtxRestResponse<HtxSwapOpenOrders> response = service.openOrders("XAUT-USDT");

        assertEquals(1, response.getData().getOrders().size());
        assertEquals("new", response.getData().getOrders().get(0).getState());
        RecordedRequest request = takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/v5/trade/order/opens", request.getRequestUrl().encodedPath());
        assertEquals("100", request.getRequestUrl().queryParameter("limit"));
    }

    @Test
    void v5BusinessErrorMapsCodeAndMessage() {
        server.enqueue(json("{\"code\":1048,\"message\":\"Insufficient margin\",\"ts\":1}"));

        HtxRestResponse<?> error = service.switchLeverage("BTC-USDT", "long", 3);

        assertFalse(error.ok());
        assertEquals("1048", error.getErrCode());
        assertEquals("Insufficient margin", error.getErrMsg());
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
