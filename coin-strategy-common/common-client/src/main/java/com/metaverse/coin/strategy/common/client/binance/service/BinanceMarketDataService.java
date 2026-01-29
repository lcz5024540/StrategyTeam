package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceAggTrade;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceAveragePrice;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceBookTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceExchangeInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceKline;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTrade;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOpenSymbol;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
public class BinanceMarketDataService {
    private final SpotClient spotClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String baseUrl;

    public BinanceMarketDataService(SpotClient spotClient) {
        this(spotClient, "https://api.binance.com");
    }

    public BinanceMarketDataService(SpotClient spotClient, String baseUrl) {
        this.spotClient = spotClient;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.binance.com";
        this.httpClient = new OkHttpClient();
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Test Connectivity
     */
    public String ping() {
        return spotClient.createMarket().ping();
    }

    /**
     * Check Server Time
     */
    public String time() {
        return spotClient.createMarket().time();
    }

    /**
     * Exchange Information (Raw JSON)
     */
    public String exchangeInfo(String symbol, List<String> symbols) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) {
            parameters.put("symbol", symbol);
        } else if (symbols != null && !symbols.isEmpty()) {
            parameters.put("symbols", symbols);
        }
        return spotClient.createMarket().exchangeInfo(parameters);
    }

    /**
     * Exchange Information (Parsed Object)
     */
    public BinanceExchangeInfo getExchangeInfo(String symbol, List<String> symbols) {
        String json = exchangeInfo(symbol, symbols);
        try {
            return objectMapper.readValue(json, BinanceExchangeInfo.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance exchange info", e);
            throw new RuntimeException("Failed to parse Binance exchange info", e);
        }
    }

    /**
     * Recent Trades List
     */
    public String trades(String symbol, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        return spotClient.createMarket().trades(parameters);
    }

    /**
     * Recent Trades List (Parsed Object)
     */
    public List<BinanceTrade> getTrades(String symbol, Integer limit) {
        String json = trades(symbol, limit);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceTrade>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance trades", e);
            throw new RuntimeException("Failed to parse Binance trades", e);
        }
    }

    /**
     * Historical Trades List
     */
    public String historicalTrades(String symbol, Integer limit, Long fromId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        if (fromId != null) parameters.put("fromId", fromId);
        return spotClient.createMarket().historicalTrades(parameters);
    }

    /**
     * Historical Trades List (Parsed Object)
     */
    public List<BinanceTrade> getHistoricalTrades(String symbol, Integer limit, Long fromId) {
        String json = historicalTrades(symbol, limit, fromId);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceTrade>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance historical trades", e);
            throw new RuntimeException("Failed to parse Binance historical trades", e);
        }
    }

    /**
     * Compressed/Aggregate Trades List
     */
    public String aggTrades(String symbol, String fromId, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (fromId != null) parameters.put("fromId", fromId);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        return spotClient.createMarket().aggTrades(parameters);
    }

    /**
     * Compressed/Aggregate Trades List (Parsed Object)
     */
    public List<BinanceAggTrade> getAggTrades(String symbol, String fromId, Long startTime, Long endTime, Integer limit) {
        String json = aggTrades(symbol, fromId, startTime, endTime, limit);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceAggTrade>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance agg trades", e);
            throw new RuntimeException("Failed to parse Binance agg trades", e);
        }
    }

    /**
     * Rolling window price change statistics
     */
    public String ticker(String symbol, String windowSize) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (windowSize != null) parameters.put("windowSize", windowSize);
        return spotClient.createMarket().ticker(parameters);
    }

    /**
     * Rolling window price change statistics (Parsed Object)
     */
    public BinanceTicker getTicker(String symbol, String windowSize) {
        String json = ticker(symbol, windowSize);
        try {
            return objectMapper.readValue(json, BinanceTicker.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance ticker", e);
            throw new RuntimeException("Failed to parse Binance ticker", e);
        }
    }

    /**
     * Kline/Candlestick Data (Raw JSON)
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
     * Kline/Candlestick Data (Parsed Object)
     */
    public List<BinanceKline> getKlines(String symbol, String interval, Integer limit, Long startTime, Long endTime) {
        String json = klines(symbol, interval, limit, startTime, endTime);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceKline>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance kline data", e);
            throw new RuntimeException("Failed to parse Binance kline data", e);
        }
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

    /**
     * Trading Day Ticker
     */
    public String tradingDayTicker(String symbol, List<String> symbols, String timeZone) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (symbols != null && !symbols.isEmpty()) parameters.put("symbols", symbols);
        if (timeZone != null) parameters.put("timeZone", timeZone);
        return spotClient.createMarket().tradingDayTicker(parameters);
    }

    /**
     * Trading Day Ticker (Parsed Object - Single)
     */
    public BinanceTicker getTradingDayTicker(String symbol, String timeZone) {
        if (symbol == null) throw new IllegalArgumentException("Symbol must be provided");
        String json = tradingDayTicker(symbol, null, timeZone);
        try {
            return objectMapper.readValue(json, BinanceTicker.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance trading day ticker", e);
            throw new RuntimeException("Failed to parse Binance trading day ticker", e);
        }
    }

    /**
     * Trading Day Ticker (Parsed Object - List)
     */
    public List<BinanceTicker> getTradingDayTickers(List<String> symbols, String timeZone) {
        String json = tradingDayTicker(null, symbols, timeZone);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceTicker>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance trading day tickers", e);
            throw new RuntimeException("Failed to parse Binance trading day tickers", e);
        }
    }

    /**
     * UI Klines
     */
    public String uiKlines(String symbol, String interval, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("interval", interval);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return spotClient.createMarket().uiKlines(parameters);
    }

    /**
     * UI Klines (Parsed Object)
     */
    public List<BinanceKline> getUiKlines(String symbol, String interval, Integer limit, Long startTime, Long endTime) {
        String json = uiKlines(symbol, interval, limit, startTime, endTime);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceKline>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance UI klines", e);
            throw new RuntimeException("Failed to parse Binance UI klines", e);
        }
    }

    /**
     * Current Average Price (Parsed)
     */
    public BinanceAveragePrice getAveragePrice(String symbol) {
        String json = averagePrice(symbol);
        try {
            return objectMapper.readValue(json, BinanceAveragePrice.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance average price", e);
            throw new RuntimeException("Failed to parse Binance average price", e);
        }
    }

    /**
     * 24hr Ticker Price Change Statistics (Parsed - Single)
     */
    public BinanceTicker getTicker24H(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("Symbol must be provided");
        String json = ticker24H(symbol);
        try {
            return objectMapper.readValue(json, BinanceTicker.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance ticker 24H", e);
            throw new RuntimeException("Failed to parse Binance ticker 24H", e);
        }
    }

    /**
     * 24hr Ticker Price Change Statistics (Parsed - All)
     */
    public List<BinanceTicker> getAllTicker24H() {
        String json = ticker24H(null);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceTicker>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance all ticker 24H", e);
            throw new RuntimeException("Failed to parse Binance all ticker 24H", e);
        }
    }

    /**
     * Symbol Price Ticker (Parsed - Single)
     */
    public BinancePriceTicker getTickerPrice(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("Symbol must be provided");
        String json = tickerPrice(symbol);
        try {
            return objectMapper.readValue(json, BinancePriceTicker.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance ticker price", e);
            throw new RuntimeException("Failed to parse Binance ticker price", e);
        }
    }

    /**
     * Symbol Price Ticker (Parsed - All)
     */
    public List<BinancePriceTicker> getAllTickerPrice() {
        String json = tickerPrice(null);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinancePriceTicker>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance all ticker price", e);
            throw new RuntimeException("Failed to parse Binance all ticker price", e);
        }
    }

    /**
     * Symbol Order Book Ticker (Parsed - Single)
     */
    public BinanceBookTicker getTickerBook(String symbol) {
        if (symbol == null) throw new IllegalArgumentException("Symbol must be provided");
        String json = tickerBook(symbol);
        try {
            return objectMapper.readValue(json, BinanceBookTicker.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance ticker book", e);
            throw new RuntimeException("Failed to parse Binance ticker book", e);
        }
    }

    /**
     * Symbol Order Book Ticker (Parsed - All)
     */
    public List<BinanceBookTicker> getAllTickerBook() {
        String json = tickerBook(null);
        try {
            return objectMapper.readValue(json, new TypeReference<List<BinanceBookTicker>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Binance all ticker book", e);
            throw new RuntimeException("Failed to parse Binance all ticker book", e);
        }
    }

    /**
     * Get Open Symbol List
     * GET /sapi/v1/spot/open-symbol-list
     */
    public List<BinanceOpenSymbol> getOpenSymbolList() {
        String url = baseUrl + "/sapi/v1/spot/open-symbol-list";
        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String json = response.body().string();
                return objectMapper.readValue(json, new TypeReference<List<BinanceOpenSymbol>>() {});
            } else {
                throw new RuntimeException("Failed to get open symbol list. Code: " + response.code());
            }
        } catch (Exception e) {
            log.error("Failed to get open symbol list", e);
            throw new RuntimeException("Failed to get open symbol list", e);
        }
    }
}
