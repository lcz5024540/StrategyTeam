package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import java.util.LinkedHashMap;

public class BinanceMarketDataService {
    private final SpotClient spotClient;
    // We could also include FuturesClient here if we want to centralize all market data
    
    public BinanceMarketDataService(SpotClient spotClient) {
        this.spotClient = spotClient;
    }

    /**
     * Kline/Candlestick Data
     */
    public String klines(String symbol, String interval, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("interval", interval);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return spotClient.createMarket().klines(parameters);
    }
    
    /**
     * Current Average Price
     */
    public String averagePrice(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return spotClient.createMarket().averagePrice(parameters);
    }
    
    /**
     * 24hr Ticker Price Change Statistics
     */
    public String ticker24H(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return spotClient.createMarket().ticker24H(parameters);
    }
    
    /**
     * Symbol Price Ticker
     */
    public String tickerPrice(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return spotClient.createMarket().tickerSymbol(parameters);
    }
    
    /**
     * Symbol Order Book Ticker
     */
    public String tickerBook(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return spotClient.createMarket().bookTicker(parameters);
    }
    
    /**
     * Order Book
     */
    public String depth(String symbol, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        return spotClient.createMarket().depth(parameters);
    }
}
