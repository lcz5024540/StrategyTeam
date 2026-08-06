package com.metaverse.coin.strategy.common.client.htx.rest;

import com.metaverse.coin.strategy.common.client.htx.model.HtxAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HtxAccountServiceTest {

    private MockWebServer server;
    private HtxAccountService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        HtxRestClient rest = new HtxRestClient("access", "secret", server.url("/").toString());
        service = new HtxAccountService(rest);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void getAccountBalance_parsesTradeAndFrozenBalances() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"status\":\"ok\",\"data\":{\"id\":73741966,\"type\":\"spot\",\"state\":\"working\","
                        + "\"list\":[{\"currency\":\"usdt\",\"type\":\"trade\",\"balance\":\"19.5\"},"
                        + "{\"currency\":\"usdt\",\"type\":\"frozen\",\"balance\":\"40\"}]}}"));

        HtxRestResponse<HtxAccount> response = service.getAccountBalance("73741966");

        assertTrue(response.ok());
        assertEquals(2, response.getData().getList().size());
        assertEquals(new BigDecimal("19.5"), response.getData().getList().get(0).getBalance());
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("GET", request.getMethod());
        assertEquals("/v1/account/accounts/73741966/balance", request.getRequestUrl().encodedPath());
        assertNotNull(request.getRequestUrl().queryParameter("Signature"));
    }
}
