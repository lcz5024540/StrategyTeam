package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import java.util.LinkedHashMap;

public class BinanceWalletService {
    private final SpotClient spotClient;

    public BinanceWalletService(SpotClient spotClient) {
        this.spotClient = spotClient;
    }

    /**
     * System Status
     */
    public String systemStatus() {
        return spotClient.createWallet().systemStatus();
    }

    /**
     * All Coins' Information
     */
    public String getAllCoins() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        return spotClient.createWallet().coinInfo(parameters);
    }

    /**
     * Deposit History
     */
    public String depositHistory(String coin, Integer status, Long startTime, Long endTime, Integer offset, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (coin != null) parameters.put("coin", coin);
        if (status != null) parameters.put("status", status);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (offset != null) parameters.put("offset", offset);
        if (limit != null) parameters.put("limit", limit);
        return spotClient.createWallet().depositHistory(parameters);
    }

    /**
     * Withdraw History
     */
    public String withdrawHistory(String coin, Integer status, Long startTime, Long endTime, Integer offset, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (coin != null) parameters.put("coin", coin);
        if (status != null) parameters.put("status", status);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (offset != null) parameters.put("offset", offset);
        if (limit != null) parameters.put("limit", limit);
        return spotClient.createWallet().withdrawHistory(parameters);
    }
    
    /**
     * Asset Detail
     */
    public String assetDetail(String asset) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (asset != null) parameters.put("asset", asset);
        return spotClient.createWallet().assetDetail(parameters);
    }
    
    /**
     * Trade Fee
     */
    public String tradeFee(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return spotClient.createWallet().tradeFee(parameters);
    }
}
