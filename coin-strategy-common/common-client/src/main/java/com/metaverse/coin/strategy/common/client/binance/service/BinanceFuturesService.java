package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import java.util.LinkedHashMap;

public class BinanceFuturesService {
    private final UMFuturesClientImpl futuresClient;
    private static final long DEFAULT_RECV_WINDOW = 60000L;

    public BinanceFuturesService(UMFuturesClientImpl futuresClient) {
        this.futuresClient = futuresClient;
    }

    /**
     * Test connectivity
     */
    public String checkServerTime() {
        return futuresClient.market().time();
    }

    /**
     * Account Information
     * @return Account information including balances and positions
     */
    public String account() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().accountInformation(parameters);
    }

    /**
     * Change Initial Leverage
     * @param symbol Symbol
     * @param leverage Leverage (1-125)
     * @return Response
     */
    public String changeInitialLeverage(String symbol, Integer leverage) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("leverage", leverage);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().changeInitialLeverage(parameters);
    }

    /**
     * Change Margin Type
     * @param symbol Symbol
     * @param marginType ISOLATED, CROSSED
     * @return Response
     */
    public String changeMarginType(String symbol, String marginType) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("marginType", marginType);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return futuresClient.account().changeMarginType(parameters);
    }

    /**
     * Place Order
     */
    public String placeOrder(String symbol, String side, String positionSide, String type, 
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
     * Open Long (Hedge Mode)
     */
    public String openLong(String symbol, String type, String quantity, String price, String timeInForce) {
        return placeOrder(symbol, "BUY", "LONG", type, timeInForce, quantity, price, null);
    }

    /**
     * Close Long (Hedge Mode)
     */
    public String closeLong(String symbol, String type, String quantity, String price) {
        return placeOrder(symbol, "SELL", "LONG", type, null, quantity, price, null);
    }

    /**
     * Open Short (Hedge Mode)
     */
    public String openShort(String symbol, String type, String quantity, String price, String timeInForce) {
        return placeOrder(symbol, "SELL", "SHORT", type, timeInForce, quantity, price, null);
    }

    /**
     * Close Short (Hedge Mode)
     */
    public String closeShort(String symbol, String type, String quantity, String price) {
        return placeOrder(symbol, "BUY", "SHORT", type, null, quantity, price, null);
    }

    /**
     * Cancel Order
     */
    public String cancelOrder(String symbol, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        return futuresClient.account().cancelOrder(parameters);
    }
    
    /**
     * Get Current All Open Orders
     */
    public String currentAllOpenOrders(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return futuresClient.account().currentAllOpenOrders(parameters);
    }

    /**
     * Get Order
     */
    public String getOrder(String symbol, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        return futuresClient.account().queryOrder(parameters);
    }

    /**
     * Get Ticker Price (Futures)
     * @param symbol Symbol (e.g. BTCUSDT)
     * @return {"symbol":"BTCUSDT","price":"60000.00","time":162...}
     */
    public String tickerPrice(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return futuresClient.market().tickerSymbol(parameters);
    }

    /**
     * Get Premium Index (Mark Price and Funding Rate)
     * @param symbol Symbol
     * @return {"symbol":"BTCUSDT","markPrice":"...","lastFundingRate":"0.0001",...}
     */
    public String getPremiumIndex(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return futuresClient.market().markPrice(parameters);
    }

    /**
     * Get Funding Rate History
     * @param symbol Symbol
     * @param limit Limit
     * @return Array of funding rate history
     */
    public String getFundingRateHistory(String symbol, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        return futuresClient.market().fundingRate(parameters);
    }

    /**
     * Place Multiple Orders (Batch)
     */
    public String batchOrders(String batchOrders) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("batchOrders", batchOrders);
        return futuresClient.account().placeMultipleOrders(parameters);
    }
}
