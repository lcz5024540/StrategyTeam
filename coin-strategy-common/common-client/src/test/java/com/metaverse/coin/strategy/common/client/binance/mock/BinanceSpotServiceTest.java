package com.metaverse.coin.strategy.common.client.binance.mock;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.spot.Trade;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceSpotService;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    public void testCommissionRate() {
        // Arrange
        String symbol = "BTCUSDT";
        String jsonResponse = "{\"symbol\":\"BTCUSDT\",\"standardCommission\":{\"maker\":\"0.00100000\",\"taker\":\"0.00100000\"}}";
        when(spotClient.createTrade()).thenReturn(trade);
        when(trade.commission(any(LinkedHashMap.class))).thenReturn(jsonResponse);

        // Act
        String result = spotService.commissionRate(symbol);

        // Assert
        assertEquals(jsonResponse, result);
    }

    @Test
    public void testOrderCountUsage() {
        // Arrange
        String jsonResponse = "[{\"rateLimitType\":\"ORDERS\",\"interval\":\"SECOND\",\"intervalNum\":10,\"limit\":50,\"count\":0}]";
        when(spotClient.createTrade()).thenReturn(trade);
        when(trade.rateLimitOrder(any(LinkedHashMap.class))).thenReturn(jsonResponse);

        // Act
        String result = spotService.orderCountUsage();

        // Assert
        assertEquals(jsonResponse, result);
    }

    @Test
    public void testAllocations() {
        // Arrange
        String symbol = "BTCUSDT";
        String jsonResponse = "[{\"symbol\":\"BTCUSDT\",\"allocationId\":1}]";
        when(spotClient.createTrade()).thenReturn(trade);
        when(trade.sorAllocations(any(LinkedHashMap.class))).thenReturn(jsonResponse);

        // Act
        String result = spotService.allocations(symbol, null, null, null, null);

        // Assert
        assertEquals(jsonResponse, result);
    }

    @Test
    public void testGetAllOCO() {
        // Arrange
        String jsonResponse = "[{\"orderListId\": 1}]";
        when(spotClient.createTrade()).thenReturn(trade);
        when(trade.getOCOOrders(any(LinkedHashMap.class))).thenReturn(jsonResponse);

        // Act
        String result = spotService.getAllOCO(null, null, null, null);

        // Assert
        assertEquals(jsonResponse, result);
    }

    @Test
    public void testOrderList() {
        // Arrange
        String jsonResponse = "{\"orderListId\": 1}";
        when(spotClient.createTrade()).thenReturn(trade);
        when(trade.getOCOOrder(any(LinkedHashMap.class))).thenReturn(jsonResponse);

        // Act
        String result = spotService.orderList(1L, null);

        // Assert
        assertEquals(jsonResponse, result);
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