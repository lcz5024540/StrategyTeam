package com.metaverse.coin.strategy.common.client.binance;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.spot.Trade;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceSpotService;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Calendar;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BinanceSpotServiceTest {

    @Mock
    private SpotClient spotClient;

    @Mock
    private Trade trade;

    private BinanceSpotService spotService;

    @BeforeEach
    public void setUp() {
        spotService = new BinanceSpotService(spotClient);
    }

    @Test
    public void testPlaceOrder() {
        // Arrange
        String symbol = "BTCUSDT";
        String side = "BUY";
        String type = "LIMIT";
        String timeInForce = "GTC";
        String quantity = "0.01";
        String price = "50000";

        String jsonResponse = "{" +
                "\"symbol\": \"BTCUSDT\"," +
                "\"orderId\": 12345," +
                "\"clientOrderId\": \"myOrder1\"," +
                "\"price\": \"50000\"," +
                "\"origQty\": \"0.01\"," +
                "\"executedQty\": \"0.00\"," +
                "\"cummulativeQuoteQty\": \"0.00\"," +
                "\"status\": \"NEW\"," +
                "\"timeInForce\": \"GTC\"," +
                "\"type\": \"LIMIT\"," +
                "\"side\": \"BUY\"" +
                "}";

        when(spotClient.createTrade()).thenReturn(trade);
        when(trade.newOrder(any(LinkedHashMap.class))).thenReturn(jsonResponse);

        // Act
        BinanceOrder order = spotService.placeOrder(symbol, side, type, timeInForce, quantity, price);

        // Assert
        assertNotNull(order);
        assertEquals(12345L, order.getOrderId());
        assertEquals("BTCUSDT", order.getSymbol());
        assertEquals("NEW", order.getStatus());
        assertEquals("BUY", order.getSide());
    }
}
