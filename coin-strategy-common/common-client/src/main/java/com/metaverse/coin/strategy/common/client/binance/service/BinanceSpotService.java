package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
public class BinanceSpotService {
    private final SpotClient spotClient;
    private static final long DEFAULT_RECV_WINDOW = 60000L;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BinanceSpotService(SpotClient spotClient) {
        this(spotClient, null, null, null);
    }

    public BinanceSpotService(SpotClient spotClient, String apiKey, String baseUrl, OkHttpClient httpClient) {
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
     * 账户信息 (Account Information)
     * GET /api/v3/account
     *
     * 获取当前账户信息。
     * 权重: 20
     *
     * @return 账户信息，包含资产余额、账户权限、佣金费率等
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/account-endpoints#account-information-user_data">Account Information</a>
     */
    public BinanceAccountInfo account() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("omitZeroBalances", false);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        try {
            String json = spotClient.createTrade().account(parameters);
            return objectMapper.readValue(json, BinanceAccountInfo.class);
        } catch (BinanceConnectorException e) {
            String message = buildSpotConnectivityErrorMessage(e);
            log.error(message, e);
            throw new IllegalStateException(message, e);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance account info", e);
            throw new RuntimeException("Failed to parse Binance account info", e);
        }
    }

    private String buildSpotConnectivityErrorMessage(BinanceConnectorException exception) {
        String detail = exception.getMessage();
        if (detail != null && detail.contains("unexpected end of stream")) {
            return "Failed to connect to Binance Spot API. The proxy or upstream endpoint closed the TLS stream unexpectedly. Prefer binance.api.spot-url=https://api.binance.com and verify the proxy protocol matches the proxy server.";
        }
        return "Failed to connect to Binance Spot API. Check binance.api.spot-url or configure binance.api.proxy-host/binance.api.proxy-port for the current network.";
    }

    /**
     * 下单 (New Order)
     * POST /api/v3/order
     *
     * 寄送新订单。
     * 权重: 1
     *
     * @param symbol 交易对 (e.g., BTCUSDT)
     * @param side 买卖方向 (BUY, SELL)
     * @param type 订单类型 (LIMIT, MARKET, STOP_LOSS, etc.)
     * @param timeInForce 有效方式 (GTC, IOC, FOK)
     * @param quantity 下单数量
     * @param price 价格
     * @return 订单响应
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/trading-endpoints#new-order-trade">New Order</a>
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
     * 测试下单 (Test New Order)
     * POST /api/v3/order/test
     *
     * 测试新订单创建和签名/recvWindow时长。创建并验证新订单，但不会将其发送到撮合引擎。
     * 权重: 1
     *
     * @param symbol 交易对
     * @param side 买卖方向
     * @param type 订单类型
     * @param timeInForce 有效方式
     * @param quantity 下单数量
     * @param price 价格
     * @return {}
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/trading-endpoints#test-new-order-trade">Test New Order</a>
     */
    public String testNewOrder(String symbol, String side, String type, String timeInForce, String quantity, String price) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("side", side);
        parameters.put("type", type);
        if (timeInForce != null) parameters.put("timeInForce", timeInForce);
        if (quantity != null) parameters.put("quantity", quantity);
        if (price != null) parameters.put("price", price);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        return spotClient.createTrade().testNewOrder(parameters);
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
     * 查询订单 (Query Order)
     * GET /api/v3/order
     *
     * 查询订单状态。
     * 权重: 4
     *
     * @param symbol 交易对
     * @param orderId 订单ID (可选)
     * @param origClientOrderId 用户自定义订单ID (可选)
     * @return 订单详情
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/trading-endpoints#query-order-user_data">Query Order</a>
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
     * 撤销订单 (Cancel Order)
     * DELETE /api/v3/order
     *
     * 撤销一个活动订单。
     * 权重: 1
     *
     * @param symbol 交易对
     * @param orderId 订单ID (可选)
     * @param origClientOrderId 用户自定义订单ID (可选)
     * @return 撤单响应
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/trading-endpoints#cancel-order-trade">Cancel Order</a>
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
     * 撤销单一交易对所有挂单 (Cancel all Open Orders on a Symbol)
     * DELETE /api/v3/openOrders
     *
     * 撤销指定交易对的所有活动订单。包括 OCO 订单。
     * 权重: 1
     *
     * @param symbol 交易对
     * @return 撤单列表
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/trading-endpoints#cancel-all-open-orders-on-a-symbol-trade">Cancel all Open Orders</a>
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
     * 当前挂单 (Current Open Orders)
     * GET /api/v3/openOrders
     *
     * 获取当前所有挂单。
     * 权重: 6 (带 symbol), 80 (不带 symbol)
     *
     * @param symbol 交易对 (可选)
     * @return 挂单列表
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/account-endpoints#current-open-orders-user_data">Current Open Orders</a>
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
     * 下单 OCO (New OCO)
     * POST /api/v3/order/oco
     *
     * 发送一个新的 OCO (One Cancels the Other) 订单对。
     * 权重: 1
     *
     * @param symbol 交易对
     * @param side 买卖方向
     * @param quantity 数量
     * @param price 价格
     * @param stopPrice 止损价格
     * @return OCO 响应
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/trading-endpoints#new-oco-trade">New OCO</a>
     */
    public String placeOCO(String symbol, String side, String quantity, String price, String stopPrice) {
        // LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        // parameters.put("symbol", symbol);
        // parameters.put("side", side);
        // parameters.put("quantity", quantity);
        // parameters.put("price", price);
        // parameters.put("stopPrice", stopPrice);
        // parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        // parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        // return spotClient.createTrade().oco(parameters); // Method not found
        return "{}"; 
    }

    /**
     * 撤销 OCO 订单 (Cancel OCO)
     * DELETE /api/v3/orderList
     *
     * 撤销整个订单列表 (OCO)。
     * 权重: 1
     *
     * @param symbol 交易对
     * @param orderListId 订单列表ID (可选)
     * @param listClientOrderId 用户自定义列表ID (可选)
     * @param newClientOrderId 新的用户自定义列表ID (可选)
     * @return 撤单响应
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/trading-endpoints#cancel-oco-trade">Cancel OCO</a>
     */
    public String cancelOCO(String symbol, Long orderListId, String listClientOrderId, String newClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderListId != null) parameters.put("orderListId", orderListId);
        if (listClientOrderId != null) parameters.put("listClientOrderId", listClientOrderId);
        if (newClientOrderId != null) parameters.put("newClientOrderId", newClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        return spotClient.createTrade().cancelOCO(parameters);
    }

    /**
     * 查询 OCO (Query OCO)
     * GET /api/v3/orderList
     *
     * 基于提供的可选参数检索特定 OCO。
     * 权重: 2
     *
     * @param orderListId 订单列表ID (可选)
     * @param origClientOrderId 用户自定义列表ID (可选)
     * @return OCO 详情
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/account-endpoints#query-oco-user_data">Query OCO</a>
     */
    public String getOCO(Long orderListId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (orderListId != null) parameters.put("orderListId", orderListId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        return spotClient.createTrade().getOCOOrder(parameters);
    }

    /**
     * 查询所有 OCO (Query all OCO)
     * GET /api/v3/allOrderList
     *
     * 检索所有 OCO。
     * 权重: 10
     *
     * @param fromId 起始ID (可选)
     * @param startTime 开始时间 (可选)
     * @param endTime 结束时间 (可选)
     * @param limit 限制 (可选)
     * @return OCO 列表
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/account-endpoints#query-all-oco-user_data">Query all OCO</a>
     */
    public String getAllOCO(Long fromId, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (fromId != null) parameters.put("fromId", fromId);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        return spotClient.createTrade().getOCOOrders(parameters);
    }

    /**
     * 查询当前挂单 OCO (Query Open OCO)
     * GET /api/v3/openOrderList
     *
     * 检索所有打开的 OCO。
     * 权重: 3
     *
     * @return OCO 列表
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/account-endpoints#query-open-oco-user_data">Query Open OCO</a>
     */
    public String getOpenOCO() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        return spotClient.createTrade().getOpenOCOOrders(parameters);
    }
    
    /**
     * OCO (One Cancels the Other) Query
     * GET /api/v3/orderList
     *
     * @param orderListId 订单列表ID
     * @param origClientOrderId 用户自定义列表ID
     * @return OCO详情
     */
    public String orderList(Long orderListId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (orderListId != null) parameters.put("orderListId", orderListId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        return spotClient.createTrade().getOCOOrder(parameters);
    }

    /**
     * OCO Query (Parsed Object)
     */
    public BinanceOrderList getOrderList(Long orderListId, String origClientOrderId) {
        String json = orderList(orderListId, origClientOrderId);
        try {
            return objectMapper.readValue(json, BinanceOrderList.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance order list response", e);
            throw new RuntimeException("Failed to parse Binance order list response", e);
        }
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
        return spotClient.createTrade().commission(parameters);
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
     * 查询当前订单计数使用情况 (Query Current Order Count Usage)
     * GET /api/v3/rateLimit/order
     *
     * 显示当前订单计数使用情况。
     * 权重: 20
     *
     * @return 订单计数使用情况
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/account-endpoints#query-current-order-count-usage-user_data">Query Current Order Count Usage</a>
     */
    public String orderCountUsage() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        parameters.put("timestamp", Calendar.getInstance().getTime().getTime());
        return spotClient.createTrade().rateLimitOrder(parameters);
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
     * 查询被阻止的撮合 (Query Prevented Matches)
     * GET /api/v3/myPreventedMatches
     *
     * 查询由于自我交易预防 (STP) 而过期的订单被阻止的撮合。
     * 权重: 20
     *
     * @param symbol 交易对
     * @param orderId 订单ID (可选)
     * @param preventedMatchId 被阻止的撮合ID (可选)
     * @param limit 返回限制
     * @return 被阻止的撮合列表
     * @see <a href="https://developers.binance.com/docs/binance-spot-api-docs/rest-api/account-endpoints#query-prevented-matches-user_data">Query Prevented Matches</a>
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
        return spotClient.createTrade().sorAllocations(parameters);
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
