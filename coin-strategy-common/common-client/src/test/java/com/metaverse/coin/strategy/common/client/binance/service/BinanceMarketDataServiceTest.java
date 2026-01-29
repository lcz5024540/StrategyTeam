package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.spot.Market;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOpenSymbol;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTicker;
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
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BinanceMarketDataServiceTest {

    @Mock
    private SpotClient spotClient;

    @Mock
    private Market market;

    private MockWebServer mockWebServer;
    private BinanceMarketDataService marketDataService;

    @BeforeEach
    public void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/").toString();

        marketDataService = new BinanceMarketDataService(spotClient, baseUrl);
    }

    @AfterEach
    public void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    public void testGetTicker24H() {
        // Arrange
        String symbol = "BTCUSDT";
        String jsonResponse = "{\"symbol\": \"BTCUSDT\", \"priceChange\": \"-100.00\", \"priceChangePercent\": \"-1.00\", \"lastPrice\": \"50000.00\"}";

        when(spotClient.createMarket()).thenReturn(market);
        when(market.ticker24H(any(LinkedHashMap.class))).thenReturn(jsonResponse);

        // Act
        BinanceTicker ticker = marketDataService.getTicker24H(symbol);

        // Assert
        assertNotNull(ticker);
        assertEquals("BTCUSDT", ticker.getSymbol());
        assertEquals(new BigDecimal("50000.00"), ticker.getLastPrice());
    }

    @Test
    public void testGetOpenSymbolList() {
        // Arrange
        String jsonResponse = "[{\"symbols\":[\"BTCUSDT\"]}]";

        mockWebServer.enqueue(new MockResponse()
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        // Act
        List<BinanceOpenSymbol> symbols = marketDataService.getOpenSymbolList();

        // Assert
        assertNotNull(symbols);
        assertEquals(1, symbols.size());
        assertNotNull(symbols.get(0).getSymbols());
        assertEquals("BTCUSDT", symbols.get(0).getSymbols().get(0));
    }
}
