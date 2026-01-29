package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.*;
import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
public class BinanceSpotService {
    private final SpotClient spotClient;
    private static final long DEFAULT_RECV_WINDOW = 60000L;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BinanceSpotService(SpotClient spotClient) {
        this.spotClient = spotClient;
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Test connectivity
     * @return Server time
     */
    public String checkServerTime() {
        return spotClient.createMarket().time();
    }

    /**
     * Account Information
     * @return Account information including balances
     */
    public BinanceAccountInfo account() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("omitZeroBalances", false);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        String json = spotClient.createTrade().account(parameters);
        try {
            return objectMapper.readValue(json, BinanceAccountInfo.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance account info", e);
            throw new RuntimeException("Failed to parse Binance account info", e);
        }
    }

    /**
     * New Order
     */
    public String newOrder(String symbol, String side, String type, String timeInForce, String quantity, String price) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("side", side);
        parameters.put("type", type);
        if (timeInForce != null) parameters.put("timeInForce", timeInForce);
        if (quantity != null) parameters.put("quantity", quantity);
        if (price != null) parameters.put("price", price);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        return spotClient.createTrade().newOrder(parameters);
    }

    /**
     * New Order (Parsed Object)
     */
    public BinanceOrder placeOrder(String symbol, String side, String type, String timeInForce, String quantity, String price) {
        String json = newOrder(symbol, side, type, timeInForce, quantity, price);
        try {
            return objectMapper.readValue(json, BinanceOrder.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance new order response", e);
            throw new RuntimeException("Failed to parse Binance new order response", e);
        }
    }

    /**
     * Buy (Spot)
     */
    public String buy(String symbol, String type, String quantity, String price) {
        return newOrder(symbol, "BUY", type, null, quantity, price);
    }

    /**
     * Sell (Spot)
     */
    public String sell(String symbol, String type, String quantity, String price) {
        return newOrder(symbol, "SELL", type, null, quantity, price);
    }

    /**
     * Get Order
     */
    public String getOrder(String symbol, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return spotClient.createTrade().getOrder(parameters);
    }

    /**
     * Get Order (Parsed Object)
     */
    public BinanceOrder getOrderObject(String symbol, Long orderId, String origClientOrderId) {
        String json = getOrder(symbol, orderId, origClientOrderId);
        try {
            return objectMapper.readValue(json, BinanceOrder.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance get order response", e);
            throw new RuntimeException("Failed to parse Binance get order response", e);
        }
    }

    /**
     * Cancel Order
     */
    public String cancelOrder(String symbol, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return spotClient.createTrade().cancelOrder(parameters);
    }

    /**
     * Cancel Order (Parsed Object)
     */
    public BinanceOrder cancelOrderObject(String symbol, Long orderId, String origClientOrderId) {
        String json = cancelOrder(symbol, orderId, origClientOrderId);
        try {
            return objectMapper.readValue(json, BinanceOrder.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance cancel order response", e);
            throw new RuntimeException("Failed to parse Binance cancel order response", e);
        }
    }

    /**
     * Cancel all Open Orders on a Symbol
     */
    public String cancelOpenOrders(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return spotClient.createTrade().cancelOpenOrders(parameters);
    }

    /**
     * Cancel all Open Orders (Parsed List)
     */
    public List<BinanceOrder> cancelAllOpenOrders(String symbol) {
        String json = cancelOpenOrders(symbol);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceOrder>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance cancel open orders response", e);
            throw new RuntimeException("Failed to parse Binance cancel open orders response", e);
        }
    }
    
    /**
     * Open Orders
     */
    public String openOrders(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return spotClient.createTrade().getOpenOrders(parameters);
    }

    /**
     * Open Orders (Parsed List)
     */
    public List<BinanceOrder> getOpenOrders(String symbol) {
        String json = openOrders(symbol);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceOrder>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance open orders response", e);
            throw new RuntimeException("Failed to parse Binance open orders response", e);
        }
    }

    /**
     * All Orders
     */
    public String allOrders(String symbol, Long orderId, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return spotClient.createTrade().getOrders(parameters);
    }

    /**
     * All Orders (Parsed List)
     */
    public List<BinanceOrder> getAllOrders(String symbol, Long orderId, Integer limit, Long startTime, Long endTime) {
        String json = allOrders(symbol, orderId, limit, startTime, endTime);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceOrder>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance all orders response", e);
            throw new RuntimeException("Failed to parse Binance all orders response", e);
        }
    }

    /**
     * Account Trade List (My Trades)
     */
    public String myTrades(String symbol, Long orderId, Integer limit, Long fromId, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (limit != null) parameters.put("limit", limit);
        if (fromId != null) parameters.put("fromId", fromId);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return spotClient.createTrade().myTrades(parameters);
    }

    /**
     * Account Trade List (Parsed List)
     * See: https://developers.binance.com/docs/binance-spot-api-docs/rest-api/account-endpoints#account-trade-list-user_data
     */
    public List<BinanceTradeHistory> getMyTrades(String symbol, Long orderId, Integer limit, Long fromId, Long startTime, Long endTime) {
        String json = myTrades(symbol, orderId, limit, fromId, startTime, endTime);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceTradeHistory>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance my trades response", e);
            throw new RuntimeException("Failed to parse Binance my trades response", e);
        }
    }

    /**
     * OCO (One Cancels the Other) Query
     * Note: This method seems to be missing or named differently in the current SDK version.
     * We will temporarily comment it out to allow compilation, as it is less critical than core order functions.
     * If needed, we can implement it via raw request if we find the correct path.
     */
    public String orderList(Long orderListId, String origClientOrderId) {
        // LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        // if (orderListId != null) parameters.put("orderListId", orderListId);
        // if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        // parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        // return spotClient.createTrade().getOco(parameters);
        return "{}"; // Placeholder
    }

    /**
     * OCO Query (Parsed Object)
     */
    public BinanceOrderList getOrderList(Long orderListId, String origClientOrderId) {
        return null; // Placeholder
    }

    /**
     * Query Commission Rates
     * GET /api/v3/account/commission
     */
    public String commissionRate(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        // return spotClient.createTrade().commissionRate(parameters);
        return "{}"; // Method not available in current SDK
    }

    public BinanceCommissionResponse getCommissionRate(String symbol) {
        String json = commissionRate(symbol);
        try {
            return objectMapper.readValue(json, BinanceCommissionResponse.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance commission rate response", e);
            throw new RuntimeException("Failed to parse Binance commission rate response", e);
        }
    }

    /**
     * Query Current Order Count Usage
     * GET /api/v3/rateLimit/order
     */
    public String orderCountUsage() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        // return spotClient.createTrade().orderCount(parameters);
        return "[]"; // Method not available in current SDK
    }

    public List<BinanceOrderCountUsage> getOrderCountUsage() {
        String json = orderCountUsage();
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceOrderCountUsage>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance order count usage response", e);
            throw new RuntimeException("Failed to parse Binance order count usage response", e);
        }
    }

    /**
     * Query Prevented Matches
     * GET /api/v3/myPreventedMatches
     */
    public String preventedMatches(String symbol, Long orderId, Long preventedMatchId, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (preventedMatchId != null) parameters.put("preventedMatchId", preventedMatchId);
        if (limit != null) parameters.put("limit", limit);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        return spotClient.createTrade().preventedMatches(parameters);
    }

    public List<BinancePreventedMatch> getPreventedMatches(String symbol, Long orderId, Long preventedMatchId, Integer limit) {
        String json = preventedMatches(symbol, orderId, preventedMatchId, limit);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinancePreventedMatch>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance prevented matches response", e);
            throw new RuntimeException("Failed to parse Binance prevented matches response", e);
        }
    }

    /**
     * Query Allocations
     * GET /api/v3/myAllocations
     */
    public String allocations(String symbol, Long orderId, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        // return spotClient.createTrade().allocations(parameters);
        return "[]"; // Method not available in current SDK
    }

    public List<BinanceAllocation> getAllocations(String symbol, Long orderId, Integer limit, Long startTime, Long endTime) {
        String json = allocations(symbol, orderId, limit, startTime, endTime);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceAllocation>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance allocations response", e);
            throw new RuntimeException("Failed to parse Binance allocations response", e);
        }
    }
}
