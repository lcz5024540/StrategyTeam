package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import com.metaverse.coin.strategy.common.model.binance.entity.alpha.BinanceAlphaOrder;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
public class BinanceAlphaServiceTest {

    private MockWebServer mockWebServer;
    private BinanceAlphaService alphaService;

    @Mock
    private SpotClient spotClient;

    @BeforeEach
    public void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/").toString();
        alphaService = new BinanceAlphaService(spotClient, baseUrl, "apiKey", "secretKey");
    }

    @AfterEach
    public void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    public void testNewOrder() {
        String jsonResponse = "{\"algoId\": 12345, \"success\": true, \"code\": \"0\", \"msg\": \"\"}";

        mockWebServer.enqueue(new MockResponse()
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        Map<String, Object> params = new HashMap<>();
        params.put("urgency", "LOW");

        BinanceAlphaOrder order = alphaService.newOrder("BTCUSDT", "BUY", "0.01", "VP", params);
        assertNotNull(order);
        assertEquals(12345L, order.getAlgoId());
    }

    @Test
    public void testGetOpenOrders() {
        String jsonResponse = "[{\"algoId\": 12345, \"symbol\": \"BTCUSDT\"}]";

        mockWebServer.enqueue(new MockResponse()
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        List<BinanceAlphaOrder> orders = alphaService.getOpenOrders();
        assertNotNull(orders);
        assertEquals(1, orders.size());
        assertEquals(12345L, orders.get(0).getAlgoId());
    }
}
