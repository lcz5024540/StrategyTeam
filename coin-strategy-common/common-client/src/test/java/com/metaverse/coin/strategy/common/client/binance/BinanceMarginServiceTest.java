package com.metaverse.coin.strategy.common.client.binance;

import com.binance.connector.client.SpotClient;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceMarginService;
import com.metaverse.coin.strategy.common.model.binance.entity.margin.BinanceCrossMarginAccount;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
public class BinanceMarginServiceTest {

    private MockWebServer mockWebServer;
    private BinanceMarginService marginService;

    @Mock
    private SpotClient spotClient;

    @BeforeEach
    public void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/").toString();
        marginService = new BinanceMarginService(spotClient, baseUrl, "apiKey", "secretKey");
    }

    @AfterEach
    public void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    public void testGetCrossMarginAccountDetails() {
        String jsonResponse = "{" +
                "\"borrowEnabled\": true," +
                "\"marginLevel\": \"999\"," +
                "\"totalAssetOfBtc\": \"0.00000000\"," +
                "\"totalLiabilityOfBtc\": \"0.00000000\"," +
                "\"totalNetAssetOfBtc\": \"0.00000000\"," +
                "\"tradeEnabled\": true," +
                "\"transferEnabled\": true," +
                "\"userAssets\": []" +
                "}";

        mockWebServer.enqueue(new MockResponse()
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        BinanceCrossMarginAccount account = marginService.getCrossMarginAccountDetails();
        assertNotNull(account);
        assertEquals(new BigDecimal("999"), account.getMarginLevel());
        assertEquals(true, account.getBorrowEnabled());
    }
}
