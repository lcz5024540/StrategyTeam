package com.metaverse.coin.strategy.common.client.binance.mock;

import com.binance.connector.client.SpotClient;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceSimpleEarnService;
import com.metaverse.coin.strategy.common.model.binance.entity.simpleearn.BinanceSimpleEarnFlexibleProduct;
import com.metaverse.coin.strategy.common.model.binance.entity.simpleearn.BinanceSimpleEarnListResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
public class BinanceSimpleEarnServiceTest {

    private MockWebServer mockWebServer;
    private BinanceSimpleEarnService simpleEarnService;

    @Mock
    private SpotClient spotClient;

    @BeforeEach
    public void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/").toString();
        simpleEarnService = new BinanceSimpleEarnService(spotClient, baseUrl, "apiKey", "secretKey");
    }

    @AfterEach
    public void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    public void testGetFlexibleProductList() {
        String jsonResponse = "{" +
                "\"rows\": [" +
                "  {\"asset\": \"BTC\", \"latestAnnualPercentageRate\": \"0.05\", \"tierAnnualPercentageRate\": {}, \"airDropPercentageRate\": \"0\", \"canPurchase\": true, \"canRedeem\": true, \"isSoldOut\": false, \"hot\": false, \"minPurchaseAmount\": \"0.001\", \"productId\": \"BTC001\", \"subscriptionStartTime\": \"\", \"status\": \"SUBSCRIBABLE\"}" +
                "]," +
                "\"total\": 1" +
                "}";

        mockWebServer.enqueue(new MockResponse()
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexibleProduct> response = simpleEarnService.getFlexibleProductList("BTC", 1L, 10);
        assertNotNull(response);
        assertEquals(1, response.getTotal());
        assertEquals(1, response.getRows().size());
        assertEquals("BTC", response.getRows().get(0).getAsset());
    }
}
