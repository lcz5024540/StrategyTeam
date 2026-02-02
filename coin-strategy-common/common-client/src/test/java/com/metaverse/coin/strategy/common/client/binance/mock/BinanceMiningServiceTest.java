package com.metaverse.coin.strategy.common.client.binance.mock;

import com.metaverse.coin.strategy.common.client.binance.service.BinanceMiningService;
import com.metaverse.coin.strategy.common.model.binance.entity.mining.BinanceMiningAlgo;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BinanceMiningServiceTest {

    private MockWebServer mockWebServer;
    private BinanceMiningService miningService;

    @BeforeEach
    public void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/").toString();
        miningService = new BinanceMiningService(baseUrl, "apiKey", "secretKey");
    }

    @AfterEach
    public void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    public void testGetAlgoList() {
        String jsonResponse = "{" +
                "\"code\": 0," +
                "\"msg\": \"\"," +
                "\"data\": [" +
                "  {\"algoName\": \"sha256\", \"algoId\": 1, \"poolIndex\": 0, \"unit\": \"h/s\"}" +
                "]" +
                "}";

        mockWebServer.enqueue(new MockResponse()
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        List<BinanceMiningAlgo> algos = miningService.getAlgoList();
        assertNotNull(algos);
        assertEquals(1, algos.size());
        assertEquals("sha256", algos.get(0).getAlgoName());
    }
}
