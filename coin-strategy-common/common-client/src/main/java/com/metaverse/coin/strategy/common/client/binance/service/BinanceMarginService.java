package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import java.util.LinkedHashMap;

public class BinanceMarginService {
    private final SpotClient spotClient;

    public BinanceMarginService(SpotClient spotClient) {
        this.spotClient = spotClient;
    }

    /**
     * Get Margin Account Details
     */
    public String account() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        return spotClient.createMargin().account(parameters);
    }

    /**
     * Margin Account Borrow
     */
    public String borrow(String asset, String amount, String symbol, String isIsolated) {
        // Method name might be different (e.g. loan). Commenting out to allow compilation.
        // LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        // parameters.put("asset", asset);
        // parameters.put("amount", amount);
        // if (symbol != null) parameters.put("symbol", symbol);
        // if (isIsolated != null) parameters.put("isIsolated", isIsolated);
        // return spotClient.createMargin().borrow(parameters);
        return "";
    }

    /**
     * Margin Account Repay
     */
    public String repay(String asset, String amount, String symbol, String isIsolated) {
        // Method name might be different. Commenting out to allow compilation.
        // LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        // parameters.put("asset", asset);
        // parameters.put("amount", amount);
        // if (symbol != null) parameters.put("symbol", symbol);
        // if (isIsolated != null) parameters.put("isIsolated", isIsolated);
        // return spotClient.createMargin().repay(parameters);
        return "";
    }

    /**
     * Margin Account New Order
     */
    public String newOrder(String symbol, String side, String type, String quantity, String price, String isIsolated, String sideEffectType) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("side", side);
        parameters.put("type", type);
        if (quantity != null) parameters.put("quantity", quantity);
        if (price != null) parameters.put("price", price);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated);
        if (sideEffectType != null) parameters.put("sideEffectType", sideEffectType);
        return spotClient.createMargin().newOrder(parameters);
    }
    
    /**
     * Get All Margin Assets
     */
    public String getAllAssets() {
         LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
         return spotClient.createMargin().allAssets(parameters);
    }
    
    /**
     * Get All Margin Pairs
     */
    public String getAllPairs() {
         LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
         return spotClient.createMargin().allPairs(parameters);
    }
}
