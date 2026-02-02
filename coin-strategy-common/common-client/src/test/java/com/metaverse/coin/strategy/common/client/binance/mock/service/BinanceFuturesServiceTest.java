package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BinanceFuturesServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private UMFuturesClientImpl futuresClient;

    private BinanceFuturesService binanceFuturesService;

    @BeforeEach
    public void setUp() {
        binanceFuturesService = new BinanceFuturesService(futuresClient);
    }

    @Test
    public void testCheckServerTime() {
        String mockResponse = "{\"serverTime\": 1617283200000}";
        when(futuresClient.market().time()).thenReturn(mockResponse);

        Long serverTime = binanceFuturesService.checkServerTime();

        assertEquals(1617283200000L, serverTime);
    }

    @Test
    public void testAccount() {
        String mockResponse = "{\"totalWalletBalance\": \"1000.50\", \"availableBalance\": \"500.25\"}";
        when(futuresClient.account().accountInformation(any())).thenReturn(mockResponse);

        BinanceFuturesAccount account = binanceFuturesService.account();

        assertNotNull(account);
        assertEquals(new java.math.BigDecimal("1000.50"), account.getTotalWalletBalance());
        assertEquals(new java.math.BigDecimal("500.25"), account.getAvailableBalance());
    }

    @Test
    public void testNewOrder_Limit_OpenLong() {
        // Arrange
        String symbol = "BTCUSDT";
        String side = "BUY";
        String positionSide = "LONG";
        String type = "LIMIT";
        String timeInForce = "GTC";
        String quantity = "0.01";
        String price = "50000";
        Boolean reduceOnly = false;
        
        String mockResponse = "{\"orderId\": 123456789, \"symbol\": \"BTCUSDT\", \"status\": \"NEW\", \"price\": \"50000\"}";
        when(futuresClient.account().newOrder(any())).thenReturn(mockResponse);

        // Act
        BinanceFuturesOrder order = binanceFuturesService.newOrder(symbol, side, positionSide, type, timeInForce, quantity, price, reduceOnly);

        // Assert
        ArgumentCaptor<LinkedHashMap<String, Object>> captor = ArgumentCaptor.forClass(LinkedHashMap.class);
        verify(futuresClient.account()).newOrder(captor.capture());
        LinkedHashMap<String, Object> params = captor.getValue();

        assertEquals(symbol, params.get("symbol"));
        assertEquals(side, params.get("side"));
        assertEquals(positionSide, params.get("positionSide"));
        assertEquals(type, params.get("type"));
        assertEquals(timeInForce, params.get("timeInForce"));
        assertEquals(quantity, params.get("quantity"));
        assertEquals(price, params.get("price"));
        assertEquals(reduceOnly, params.get("reduceOnly"));

        assertNotNull(order);
        assertEquals(123456789L, order.getOrderId());
        assertEquals("BTCUSDT", order.getSymbol());
        assertEquals("NEW", order.getStatus());
    }

    @Test
    public void testBatchOrders() {
        String batchOrdersParams = "[{\"symbol\":\"BTCUSDT\", \"type\":\"LIMIT\", \"side\": \"BUY\", \"quantity\":\"0.001\", \"price\":\"50000\"}]";
        String mockResponse = "[{\"orderId\": 111, \"symbol\": \"BTCUSDT\"}, {\"orderId\": 222, \"symbol\": \"ETHUSDT\"}]";
        when(futuresClient.account().placeMultipleOrders(any())).thenReturn(mockResponse);

        List<BinanceFuturesOrder> orders = binanceFuturesService.batchOrders(batchOrdersParams);

        assertNotNull(orders);
        assertEquals(2, orders.size());
        assertEquals(111L, orders.get(0).getOrderId());
        assertEquals("BTCUSDT", orders.get(0).getSymbol());
        assertEquals(222L, orders.get(1).getOrderId());
        assertEquals("ETHUSDT", orders.get(1).getSymbol());
    }

    @Test
    public void testOpenLong_Helper() {
        // Arrange
        String symbol = "BTCUSDT";
        String type = "LIMIT";
        String quantity = "0.01";
        String price = "50000";
        String timeInForce = "GTC";

        String mockResponse = "{\"orderId\": 123}";
        when(futuresClient.account().newOrder(any())).thenReturn(mockResponse);

        // Act
        BinanceFuturesOrder order = binanceFuturesService.openLong(symbol, type, quantity, price, timeInForce);

        // Assert
        ArgumentCaptor<LinkedHashMap<String, Object>> captor = ArgumentCaptor.forClass(LinkedHashMap.class);
        verify(futuresClient.account()).newOrder(captor.capture());
        LinkedHashMap<String, Object> params = captor.getValue();

        assertEquals("BUY", params.get("side"));
        assertEquals("LONG", params.get("positionSide"));
        assertNotNull(order);
        assertEquals(123L, order.getOrderId());
    }
}
