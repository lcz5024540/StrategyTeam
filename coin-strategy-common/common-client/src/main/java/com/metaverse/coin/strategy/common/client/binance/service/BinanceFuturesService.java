package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;

/**
 * Binance Futures Service (U-Margined)
 * <br>
 * Provides access to Binance USD-M Futures REST API.
 */
@Slf4j
public class BinanceFuturesService {
    private final UMFuturesClientImpl futuresClient;
    private static final long DEFAULT_RECV_WINDOW = 60000L;

    public BinanceFuturesService(UMFuturesClientImpl futuresClient) {
        this.futuresClient = futuresClient;
    }

    // =========================== Market Data ===========================

    /**
     * Check Server Time (MARKET_DATA)
     * <br>
     * GET /fapi/v1/time
     * <br>
     * 获取服务器时间
     */
    public String checkServerTime() {
        return futuresClient.market().time();
    }

    /**
     * Symbol Price Ticker (MARKET_DATA)
     * <br>
     * GET /fapi/v1/ticker/price
     * <br>
     * 获取交易对最新价格
     */
    public String getTickerPrice(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return futuresClient.market().tickerSymbol(parameters);
    }

    /**
     * Mark Price and Funding Rate (MARKET_DATA)
     * <br>
     * GET /fapi/v1/premiumIndex
     * <br>
     * 获取标记价格和资金费率
     */
    public String getPremiumIndex(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return futuresClient.market().markPrice(parameters);
    }

    /**
     * Get Funding Rate History (MARKET_DATA)
     * <br>
     * GET /fapi/v1/fundingRate
     * <br>
     * 获取资金费率历史
     */
    public String getFundingRateHistory(String symbol, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        return futuresClient.market().fundingRate(parameters);
    }

    // =========================== Account ===========================

    /**
     * Account Information V2 (USER_DATA)
     * <br>
     * GET /fapi/v2/account
     * <br>
     * 获取账户信息 (V2)
     */
    public String account() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().accountInformation(parameters);
    }

    /**
     * Position Information V2 (USER_DATA)
     * <br>
     * GET /fapi/v2/positionRisk
     * <br>
     * 获取持仓风险 (V2)
     */
    public String getPositionRisk(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().positionInformation(parameters);
    }

    /**
     * Change Initial Leverage (TRADE)
     * <br>
     * POST /fapi/v1/leverage
     * <br>
     * 调整开仓杠杆
     */
    public String changeInitialLeverage(String symbol, Integer leverage) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("leverage", leverage);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().changeInitialLeverage(parameters);
    }

    /**
     * Change Margin Type (TRADE)
     * <br>
     * POST /fapi/v1/marginType
     * <br>
     * 变换逐全仓模式 (ISOLATED, CROSSED)
     */
    public String changeMarginType(String symbol, String marginType) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("marginType", marginType);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().changeMarginType(parameters);
    }

    // =========================== Trade ===========================

    /**
     * New Order (TRADE)
     * <br>
     * POST /fapi/v1/order
     * <br>
     * 下单
     */
    public String newOrder(String symbol, String side, String positionSide, String type, 
                                    String timeInForce, String quantity, String price, Boolean reduceOnly) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("side", side);
        if (positionSide != null) parameters.put("positionSide", positionSide);
        parameters.put("type", type);
        if (timeInForce != null) parameters.put("timeInForce", timeInForce);
        if (quantity != null) parameters.put("quantity", quantity);
        if (price != null) parameters.put("price", price);
        if (reduceOnly != null) parameters.put("reduceOnly", reduceOnly);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        
        return futuresClient.account().newOrder(parameters);
    }

    /**
     * Place Order (Alias for newOrder)
     */
    public String placeOrder(String symbol, String side, String positionSide, String type, 
                             String timeInForce, String quantity, String price, Boolean reduceOnly) {
        return newOrder(symbol, side, positionSide, type, timeInForce, quantity, price, reduceOnly);
    }

    /**
     * Query Order (USER_DATA)
     * <br>
     * GET /fapi/v1/order
     * <br>
     * 查询订单
     */
    public String getOrder(String symbol, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().queryOrder(parameters);
    }

    /**
     * Cancel Order (TRADE)
     * <br>
     * DELETE /fapi/v1/order
     * <br>
     * 撤销订单
     */
    public String cancelOrder(String symbol, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().cancelOrder(parameters);
    }

    /**
     * Cancel All Open Orders (TRADE)
     * <br>
     * DELETE /fapi/v1/allOpenOrders
     * <br>
     * 撤销所有挂单
     */
    public String cancelAllOpenOrders(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().cancelAllOpenOrders(parameters);
    }
    
    /**
     * Current All Open Orders (USER_DATA)
     * <br>
     * GET /fapi/v1/openOrders
     * <br>
     * 查询当前所有挂单
     */
    public String getOpenOrders(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().currentAllOpenOrders(parameters);
    }

    /**
     * Place Multiple Orders (TRADE)
     * <br>
     * POST /fapi/v1/batchOrders
     * <br>
     * 批量下单
     */
    public String batchOrders(String batchOrders) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("batchOrders", batchOrders);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().placeMultipleOrders(parameters);
    }

    // =========================== Helper Methods (Hedge Mode) ===========================

    /**
     * Open Long (Hedge Mode)
     */
    public String openLong(String symbol, String type, String quantity, String price, String timeInForce) {
        return newOrder(symbol, "BUY", "LONG", type, timeInForce, quantity, price, null);
    }

    /**
     * Close Long (Hedge Mode)
     */
    public String closeLong(String symbol, String type, String quantity, String price) {
        return newOrder(symbol, "SELL", "LONG", type, null, quantity, price, null);
    }

    /**
     * Open Short (Hedge Mode)
     */
    public String openShort(String symbol, String type, String quantity, String price, String timeInForce) {
        return newOrder(symbol, "SELL", "SHORT", type, timeInForce, quantity, price, null);
    }

    /**
     * Close Short (Hedge Mode)
     */
    public String closeShort(String symbol, String type, String quantity, String price) {
        return newOrder(symbol, "BUY", "SHORT", type, null, quantity, price, null);
    }
}
