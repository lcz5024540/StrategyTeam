package com.metaverse.coin.strategy.common.client.htx.rest;

import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSpotFeeRate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxSpotServiceTest {

    private MockWebServer server;
    private HtxSpotService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new HtxSpotService(new HtxRestClient(
                "access", "secret", server.url("/").toString()));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void queryFeeRatesUsesCurrentV2ReferenceEndpoint() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                {"code":200,"success":true,"data":[{"symbol":"xautusdt",
                "actualMakerRate":"0.0018","actualTakerRate":"0.0020",
                "makerFeeRate":"0.0020","takerFeeRate":"0.0020"}]}
                """));

        HtxRestResponse<List<HtxSpotFeeRate>> response = service.queryFeeRates("xautusdt");

        assertTrue(response.ok());
        assertEquals("xautusdt", response.getData().get(0).getSymbol());
        assertEquals("0.0018", response.getData().get(0).getActualMakerRate().toPlainString());
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/v2/reference/transact-fee-rate", request.getRequestUrl().encodedPath());
        assertEquals("xautusdt", request.getRequestUrl().queryParameter("symbols"));
    }

    @Test
    void placeOrderKeepsOfficialCurrentV1OrderEndpoint() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"status\":\"ok\",\"data\":\"12345\"}"));

        HtxRestResponse<String> response = service.placeOrder(
                "2922", "xautusdt", "buy-limit-maker", "0.01", "4200", "grid-1");

        assertTrue(response.ok());
        assertEquals("12345", response.getData());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/order/orders/place", request.getRequestUrl().encodedPath());
    }
}
