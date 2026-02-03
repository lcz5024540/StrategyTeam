package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.futures.client.impl.CMFuturesClientImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.*;
import com.metaverse.coin.strategy.common.model.binance.entity.market.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Binance Coin-Margined Futures Service
 * <br>
 * Provides access to Binance COIN-M Futures REST API (dapi).
 */
@Slf4j
public class BinanceCoinFuturesService {
    private final CMFuturesClientImpl futuresClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String secretKey;
    private static final long DEFAULT_RECV_WINDOW = 60000L;

    public BinanceCoinFuturesService(CMFuturesClientImpl futuresClient) {
        this(futuresClient, "https://dapi.binance.com", null, null);
    }

    public BinanceCoinFuturesService(CMFuturesClientImpl futuresClient, String baseUrl) {
        this(futuresClient, baseUrl, null, null);
    }

    public BinanceCoinFuturesService(CMFuturesClientImpl futuresClient, String baseUrl, String apiKey, String secretKey) {
        this(futuresClient, baseUrl, apiKey, secretKey, null);
    }

    public BinanceCoinFuturesService(CMFuturesClientImpl futuresClient, String baseUrl, String apiKey, String secretKey, OkHttpClient httpClient) {
        this.futuresClient = futuresClient;
        this.baseUrl = baseUrl != null ? baseUrl : "https://dapi.binance.com";
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.httpClient = httpClient != null ? httpClient : new OkHttpClient();
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Check Server Time
     * <br>
     * GET /dapi/v1/time
     */
    public Long checkServerTime() {
        String response = futuresClient.market().time();
        try {
            JsonNode node = objectMapper.readTree(response);
            return node.get("serverTime").asLong();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse server time", e);
            throw new RuntimeException("Failed to parse server time", e);
        }
    }

    // =========================== Market Data Endpoints ===========================

    /**
     * Test Connectivity
     * GET /dapi/v1/ping
     */
    public String ping() {
        return futuresClient.market().ping();
    }

    /**
     * Exchange Information
     * GET /dapi/v1/exchangeInfo
     */
    public BinanceFuturesExchangeInfo getExchangeInfo() {
        return parse(futuresClient.market().exchangeInfo(), BinanceFuturesExchangeInfo.class);
    }

    /**
     * Order Book
     * GET /dapi/v1/depth
     */
    public BinanceFuturesOrderBook getOrderBook(String symbol, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().depth(parameters), BinanceFuturesOrderBook.class);
    }

    /**
     * Recent Trades
     * GET /dapi/v1/trades
     */
    public List<BinanceTrade> getTrades(String symbol, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().trades(parameters), new TypeReference<List<BinanceTrade>>() {});
    }

    /**
     * Old Trade Lookup
     * GET /dapi/v1/historicalTrades
     */
    public List<BinanceTrade> getHistoricalTrades(String symbol, Integer limit, Long fromId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        if (fromId != null) parameters.put("fromId", fromId);
        return parse(futuresClient.market().historicalTrades(parameters), new TypeReference<List<BinanceTrade>>() {});
    }

    /**
     * Aggregate Trades List
     * GET /dapi/v1/aggTrades
     */
    public List<BinanceAggTrade> getAggTrades(String symbol, Long fromId, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (fromId != null) parameters.put("fromId", fromId);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().aggTrades(parameters), new TypeReference<List<BinanceAggTrade>>() {});
    }

    /**
     * Index Price and Mark Price
     * GET /dapi/v1/premiumIndex
     */
    public List<BinanceFuturesPremiumIndex> getPremiumIndex(String symbol, String pair) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (pair != null) parameters.put("pair", pair);
        
        String response = sendPublicRequest("/dapi/v1/premiumIndex", parameters);
        
        if (symbol != null) {
             BinanceFuturesPremiumIndex obj = parse(response, BinanceFuturesPremiumIndex.class);
             return Collections.singletonList(obj);
        } else {
             return parse(response, new TypeReference<List<BinanceFuturesPremiumIndex>>() {});
        }
    }

    /**
     * Get Funding Rate History
     * GET /dapi/v1/fundingRate
     */
    public List<BinanceFuturesFundingRate> getFundingRate(String symbol, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().fundingRate(parameters), new TypeReference<List<BinanceFuturesFundingRate>>() {});
    }

    /**
     * Kline/Candlestick Data
     * GET /dapi/v1/klines
     */
    public List<BinanceFuturesKline> getKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("interval", interval);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().klines(parameters), new TypeReference<List<BinanceFuturesKline>>() {});
    }

    /**
     * Continuous Contract Kline/Candlestick Data
     * GET /dapi/v1/continuousKlines
     */
    public List<BinanceFuturesKline> getContinuousKlines(String pair, String contractType, String interval, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        parameters.put("contractType", contractType);
        parameters.put("interval", interval);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().continuousKlines(parameters), new TypeReference<List<BinanceFuturesKline>>() {});
    }

    /**
     * Index Price Kline/Candlestick Data
     * GET /dapi/v1/indexPriceKlines
     */
    public List<BinanceFuturesKline> getIndexPriceKlines(String pair, String interval, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        parameters.put("interval", interval);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().indexPriceKlines(parameters), new TypeReference<List<BinanceFuturesKline>>() {});
    }

    /**
     * Mark Price Kline/Candlestick Data
     * GET /dapi/v1/markPriceKlines
     */
    public List<BinanceFuturesKline> getMarkPriceKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("interval", interval);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().markPriceKlines(parameters), new TypeReference<List<BinanceFuturesKline>>() {});
    }
    
    /**
     * 24hr Ticker Price Change Statistics
     * GET /dapi/v1/ticker/24hr
     */
    public List<BinanceTicker> get24hrTicker(String symbol, String pair) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (pair != null) parameters.put("pair", pair);
        String response = futuresClient.market().ticker24H(parameters);
        if (symbol != null) {
            BinanceTicker obj = parse(response, BinanceTicker.class);
            return Collections.singletonList(obj);
        } else {
            return parse(response, new TypeReference<List<BinanceTicker>>() {});
        }
    }

    /**
     * Symbol Price Ticker
     * GET /dapi/v1/ticker/price
     */
    public List<BinancePriceTicker> getSymbolPriceTicker(String symbol, String pair) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (pair != null) parameters.put("pair", pair);
        
        String response = sendPublicRequest("/dapi/v1/ticker/price", parameters);
        
         if (symbol != null) {
            BinancePriceTicker obj = parse(response, BinancePriceTicker.class);
            return Collections.singletonList(obj);
        } else {
            return parse(response, new TypeReference<List<BinancePriceTicker>>() {});
        }
    }

    /**
     * Symbol Order Book Ticker
     * GET /dapi/v1/ticker/bookTicker
     */
    public List<BinanceFuturesBookTicker> getBookTicker(String symbol, String pair) {
         LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (pair != null) parameters.put("pair", pair);
        String response = futuresClient.market().bookTicker(parameters);
         if (symbol != null) {
            BinanceFuturesBookTicker obj = parse(response, BinanceFuturesBookTicker.class);
            return Collections.singletonList(obj);
        } else {
            return parse(response, new TypeReference<List<BinanceFuturesBookTicker>>() {});
        }
    }

    /**
     * Open Interest
     * GET /dapi/v1/openInterest
     */
    public BinanceFuturesOpenInterest getOpenInterest(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return parse(futuresClient.market().openInterest(parameters), BinanceFuturesOpenInterest.class);
    }
    
    /**
     * Open Interest Statistics
     * GET /dapi/v1/openInterestHist
     */
    public List<BinanceFuturesOpenInterestHist> getOpenInterestHist(String pair, String contractType, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        parameters.put("contractType", contractType);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return parse(futuresClient.market().openInterestStatistics(parameters), new TypeReference<List<BinanceFuturesOpenInterestHist>>() {});
    }

    /**
     * Top Trader Long/Short Ratio (Accounts)
     * GET /dapi/v1/topLongShortAccountRatio
     */
    public List<BinanceFuturesAccountRatio> getTopLongShortAccountRatio(String pair, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        
        String response = sendPublicRequest("/dapi/v1/topLongShortAccountRatio", parameters);
        return parse(response, new TypeReference<List<BinanceFuturesAccountRatio>>() {});
    }

    /**
     * Top Trader Long/Short Ratio (Positions)
     * GET /dapi/v1/topLongShortPositionRatio
     */
    public List<BinanceFuturesPositionRatio> getTopLongShortPositionRatio(String pair, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        
        String response = sendPublicRequest("/dapi/v1/topLongShortPositionRatio", parameters);
        return parse(response, new TypeReference<List<BinanceFuturesPositionRatio>>() {});
    }

    /**
     * Long/Short Ratio
     * GET /dapi/v1/globalLongShortAccountRatio
     */
    public List<BinanceFuturesAccountRatio> getGlobalLongShortAccountRatio(String pair, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        
        String response = sendPublicRequest("/dapi/v1/globalLongShortAccountRatio", parameters);
        return parse(response, new TypeReference<List<BinanceFuturesAccountRatio>>() {});
    }

    /**
     * Taker Buy/Sell Volume
     * GET /dapi/v1/takerBuySellVol
     */
    public List<BinanceFuturesTakerVolume> getTakerBuySellVol(String pair, String contractType, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        parameters.put("contractType", contractType);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        
        String response = sendPublicRequest("/dapi/v1/takerBuySellVol", parameters);
        return parse(response, new TypeReference<List<BinanceFuturesTakerVolume>>() {});
    }

    /**
     * Basis
     * GET /dapi/v1/basis
     */
    public List<BinanceFuturesBasis> getBasis(String pair, String contractType, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        parameters.put("contractType", contractType);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return parse(futuresClient.market().basis(parameters), new TypeReference<List<BinanceFuturesBasis>>() {});
    }

    /**
     * Liquidation Orders
     * GET /dapi/v1/allForceOrders
     */
    public List<BinanceFuturesForceOrder> getAllForceOrders(String symbol, String pair, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (pair != null) parameters.put("pair", pair);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        return parse(sendPublicRequest("/dapi/v1/allForceOrders", parameters), new TypeReference<List<BinanceFuturesForceOrder>>() {});
    }

    /**
     * Index Constituents
     * GET /dapi/v1/constituents
     */
    public BinanceFuturesConstituents getIndexConstituents(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return parse(sendPublicRequest("/dapi/v1/constituents", parameters), BinanceFuturesConstituents.class);
    }

    // =========================== Helper Methods ===========================

    private String sendPublicRequest(String path, LinkedHashMap<String, Object> parameters) {
        String query = parameters.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        String url = baseUrl + path + (query.isEmpty() ? "" : "?" + query);
        
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Request failed: " + response.code() + " " + response.message());
            }
            return response.body().string();
        } catch (IOException e) {
            throw new RuntimeException("Request failed", e);
        }
    }

    private <T> T parse(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse response: {}", json, e);
            throw new RuntimeException("Failed to parse response", e);
        }
    }

    private <T> T parse(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse response: {}", json, e);
            throw new RuntimeException("Failed to parse response", e);
        }
    }
}
