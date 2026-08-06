package com.metaverse.coin.strategy.common.client.htx.rest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
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
                new HtxRestClient("access", "secret", server.url("/").toString()));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void placeOrder_usesSwapPathSignatureAndContractFields() throws InterruptedException {
        server.enqueue(json("{\"status\":\"ok\",\"data\":{\"order_id\":123,\"client_order_id\":456}}"));

        HtxRestResponse<HtxSwapOrderResult> response = service.placeOrder(
                "BTC-USDT", 456L, 2L, "buy", "open", 3, "60000.0", "limit");

        assertTrue(response.ok());
        assertEquals(123L, response.getData().getOrderId());
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/linear-swap-api/v1/swap_order", request.getRequestUrl().encodedPath());
        assertNotNull(request.getRequestUrl().queryParameter("Signature"));
        JSONObject body = JSON.parseObject(request.getBody().readUtf8());
        assertEquals("BTC-USDT", body.getString("contract_code"));
        assertEquals(456L, body.getLongValue("client_order_id"));
        assertEquals(2L, body.getLongValue("volume"));
        assertEquals(3, body.getIntValue("lever_rate"));
        assertEquals("buy", body.getString("direction"));
        assertEquals("open", body.getString("offset"));
        assertEquals("limit", body.getString("order_price_type"));
    }

    @Test
    void depth_normalizesTickAndUsesStepType() throws InterruptedException {
        server.enqueue(json("{\"ch\":\"market.BTC-USDT.depth.step6\",\"tick\":{"
                + "\"asks\":[[60001,5]],\"bids\":[[60000,7]]}}"));

        HtxRestResponse<HtxDepth> response = service.depth("BTC-USDT", 6);

        assertTrue(response.ok());
        assertEquals(new BigDecimal("5"), response.payload().getAsks().get(0).getAmount());
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/linear-swap-ex/market/depth", request.getRequestUrl().encodedPath());
        assertEquals("step6", request.getRequestUrl().queryParameter("type"));
    }

    @Test
    void queryOrder_parsesTradeVolumeAndSnakeCaseErrors() {
        server.enqueue(json("{\"status\":\"ok\",\"data\":[{\"order_id\":123,"
                + "\"trade_volume\":\"2\",\"trade_avg_price\":\"60000.5\",\"status\":6}]}"));
        HtxRestResponse<List<HtxSwapOrder>> order = service.queryOrder("BTC-USDT", 123L);
        assertEquals(new BigDecimal("2"), order.getData().get(0).getTradeVolume());
        assertEquals(new BigDecimal("60000.5"), order.getData().get(0).getTradeAveragePrice());

        server.enqueue(json("{\"status\":\"error\",\"err_code\":1048,\"err_msg\":\"Insufficient margin\"}"));
        HtxRestResponse<?> error = service.switchLeverage("BTC-USDT", 3);
        assertEquals("1048", error.getErrCode());
        assertEquals("Insufficient margin", error.getErrMsg());
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
