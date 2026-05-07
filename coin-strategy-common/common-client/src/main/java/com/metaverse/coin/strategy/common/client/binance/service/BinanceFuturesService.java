package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.futures.client.impl.UMFuturesClientImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.base.utils.SignUtils;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.*;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceAggTrade;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceTrade;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Binance Futures Service (U-Margined)
 * <br>
 * Provides access to Binance USD-M Futures REST API.
 */
@Slf4j
public class BinanceFuturesService {
    private final UMFuturesClientImpl futuresClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String secretKey;
    private static final long DEFAULT_RECV_WINDOW = 60000L;

    public BinanceFuturesService(UMFuturesClientImpl futuresClient) {
        this(futuresClient, "https://fapi.binance.com", null, null);
    }

    public BinanceFuturesService(UMFuturesClientImpl futuresClient, String baseUrl) {
        this(futuresClient, baseUrl, null, null);
    }

    public BinanceFuturesService(UMFuturesClientImpl futuresClient, String baseUrl, String apiKey, String secretKey) {
        this(futuresClient, baseUrl, apiKey, secretKey, null);
    }

    public BinanceFuturesService(UMFuturesClientImpl futuresClient,
                                 String baseUrl,
                                 String apiKey,
                                 String secretKey,
                                 OkHttpClient httpClient) {
        this.futuresClient = futuresClient;
        this.baseUrl = baseUrl != null ? baseUrl : "https://fapi.binance.com";
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.httpClient = httpClient == null ? new OkHttpClient() : httpClient;
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
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

    private String sendPublicRequest(String path, LinkedHashMap<String, Object> parameters) {
        String query = parameters.entrySet().stream()
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

    private String executeRequest(String path, Map<String, Object> parameters, String method, boolean signed) {
        String queryString = parameters.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));

        if (signed) {
            if (apiKey == null || secretKey == null) {
                throw new RuntimeException("API Key and Secret Key are required for this operation");
            }
            long timestamp = System.currentTimeMillis();
            if (!queryString.isEmpty()) {
                queryString += "&";
            }
            queryString += "timestamp=" + timestamp;

            try {
                byte[] hmacBytes = SignUtils.hmacSHA256(secretKey.getBytes(), queryString.getBytes());
                String signature = bytesToHex(hmacBytes);
                queryString += "&signature=" + signature;
            } catch (Exception e) {
                throw new RuntimeException("Failed to sign request", e);
            }
        }

        String fullUrl = baseUrl + path;
        Request.Builder requestBuilder = new Request.Builder();
        if (apiKey != null) {
             requestBuilder.addHeader("X-MBX-APIKEY", apiKey);
        }

        if ("POST".equalsIgnoreCase(method)) {
             RequestBody body = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), queryString);
             requestBuilder.url(fullUrl).post(body);
        } else if ("PUT".equalsIgnoreCase(method)) {
             RequestBody body = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), queryString);
             requestBuilder.url(fullUrl).put(body);
        } else if ("DELETE".equalsIgnoreCase(method)) {
             RequestBody body = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded"), queryString);
             requestBuilder.url(fullUrl).delete(body);
        } else {
            if (!queryString.isEmpty()) {
                fullUrl += "?" + queryString;
            }
            requestBuilder.url(fullUrl).get();
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            String json = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Failed to execute request: " + json);
            }
            return json;
        } catch (IOException e) {
            log.error("Failed to execute request", e);
            throw new RuntimeException("Failed to execute request", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // =========================== Market Data ===========================

    /**
     * Test Connectivity
     * <br>
     * GET /fapi/v1/ping
     */
    public void ping() {
        futuresClient.market().ping();
    }

    /**
     * Exchange Information
     * <br>
     * GET /fapi/v1/exchangeInfo
     */
    public BinanceFuturesExchangeInfo getExchangeInfo() {
        return parse(futuresClient.market().exchangeInfo(), BinanceFuturesExchangeInfo.class);
    }

    /**
     * Order Book
     * <br>
     * GET /fapi/v1/depth
     */
    public BinanceFuturesOrderBook getOrderBook(String symbol, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().depth(parameters), BinanceFuturesOrderBook.class);
    }

    /**
     * Recent Trades List
     * <br>
     * GET /fapi/v1/trades
     */
    public List<BinanceTrade> getTrades(String symbol, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().trades(parameters), new TypeReference<List<BinanceTrade>>() {});
    }

    /**
     * Old Trades Lookup
     * <br>
     * GET /fapi/v1/historicalTrades
     */
    public List<BinanceTrade> getHistoricalTrades(String symbol, Integer limit, Long fromId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        if (fromId != null) parameters.put("fromId", fromId);
        return parse(futuresClient.market().historicalTrades(parameters), new TypeReference<List<BinanceTrade>>() {});
    }

    /**
     * Compressed/Aggregate Trades List
     * <br>
     * GET /fapi/v1/aggTrades
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
     * Kline/Candlestick Data
     * <br>
     * GET /fapi/v1/klines
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
     * <br>
     * GET /fapi/v1/continuousKlines
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
     * <br>
     * GET /fapi/v1/indexPriceKlines
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
     * <br>
     * GET /fapi/v1/markPriceKlines
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
     * <br>
     * GET /fapi/v1/ticker/24hr
     */
    public BinanceTicker get24hrTicker(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return parse(futuresClient.market().ticker24H(parameters), BinanceTicker.class);
    }

    /**
     * 24hr Ticker Price Change Statistics (All Symbols)
     * <br>
     * GET /fapi/v1/ticker/24hr
     */
    public List<BinanceTicker> get24hrTickers() {
        return parse(futuresClient.market().ticker24H(new LinkedHashMap<>()), new TypeReference<List<BinanceTicker>>() {});
    }

    /**
     * Symbol Order Book Ticker
     * <br>
     * GET /fapi/v1/ticker/bookTicker
     */
    public BinanceFuturesBookTicker getBookTicker(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return parse(futuresClient.market().bookTicker(parameters), BinanceFuturesBookTicker.class);
    }

    /**
     * Symbol Order Book Ticker (All Symbols)
     * <br>
     * GET /fapi/v1/ticker/bookTicker
     */
    public List<BinanceFuturesBookTicker> getBookTickers() {
        return parse(futuresClient.market().bookTicker(new LinkedHashMap<>()), new TypeReference<List<BinanceFuturesBookTicker>>() {});
    }

    /**
     * Open Interest
     * <br>
     * GET /fapi/v1/openInterest
     */
    public BinanceFuturesOpenInterest getOpenInterest(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return parse(futuresClient.market().openInterest(parameters), BinanceFuturesOpenInterest.class);
    }

    /**
     * Open Interest Statistics
     * <br>
     * GET /fapi/futures/data/openInterestHist
     */
    public List<BinanceFuturesOpenInterestHist> getOpenInterestStatistics(String symbol, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return parse(futuresClient.market().openInterestStatistics(parameters), new TypeReference<List<BinanceFuturesOpenInterestHist>>() {});
    }

    /**
     * Top Trader Long/Short Ratio (Accounts)
     * <br>
     * GET /fapi/futures/data/topLongShortAccountRatio
     */
    public List<BinanceFuturesAccountRatio> getTopLongShortAccountRatio(String symbol, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return parse(sendPublicRequest("/futures/data/topLongShortAccountRatio", parameters), new TypeReference<List<BinanceFuturesAccountRatio>>() {});
    }

    /**
     * Top Trader Long/Short Ratio (Positions)
     * <br>
     * GET /futures/data/topLongShortPositionRatio
     */
    public List<BinanceFuturesPositionRatio> getTopLongShortPositionRatio(String symbol, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return parse(sendPublicRequest("/futures/data/topLongShortPositionRatio", parameters), new TypeReference<List<BinanceFuturesPositionRatio>>() {});
    }

    /**
     * Long/Short Ratio
     * <br>
     * GET /futures/data/globalLongShortAccountRatio
     */
    public List<BinanceFuturesAccountRatio> getGlobalLongShortAccountRatio(String symbol, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return parse(sendPublicRequest("/futures/data/globalLongShortAccountRatio", parameters), new TypeReference<List<BinanceFuturesAccountRatio>>() {});
    }

    /**
     * Taker Buy/Sell Volume
     * <br>
     * GET /futures/data/takerlongshortRatio
     */
    public List<BinanceFuturesTakerVolume> getTakerLongShortRatio(String symbol, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return parse(sendPublicRequest("/futures/data/takerlongshortRatio", parameters), new TypeReference<List<BinanceFuturesTakerVolume>>() {});
    }

    /**
     * Basis
     * <br>
     * GET /futures/data/basis
     */
    public List<BinanceFuturesBasis> getBasis(String pair, String contractType, String period, Integer limit, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        parameters.put("contractType", contractType);
        parameters.put("period", period);
        if (limit != null) parameters.put("limit", limit);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        return parse(sendPublicRequest("/futures/data/basis", parameters), new TypeReference<List<BinanceFuturesBasis>>() {});
    }

    /**
     * Composite Index Symbol Information
     * <br>
     * GET /fapi/v1/indexInfo
     */
    public List<BinanceFuturesCompositeIndexInfo> getCompositeIndexInfo(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return parse(sendPublicRequest("/fapi/v1/indexInfo", parameters), new TypeReference<List<BinanceFuturesCompositeIndexInfo>>() {});
    }

    /**
     * Multi-Assets Mode Asset Index
     * <br>
     * GET /fapi/v1/assetIndex
     */
    public List<BinanceFuturesAssetIndex> getAssetIndex(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return parse(sendPublicRequest("/fapi/v1/assetIndex", parameters), new TypeReference<List<BinanceFuturesAssetIndex>>() {});
    }

    /**
     * Check Server Time (MARKET_DATA)
     * <br>
     * GET /fapi/v1/time
     * <br>
     * 鑾峰彇鏈嶅姟鍣ㄦ椂闂?
     */
    public Long checkServerTime() {
        String json = futuresClient.market().time();
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.get("serverTime").asLong();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse server time", e);
        }
    }

    /**
     * Symbol Price Ticker (MARKET_DATA)
     * <br>
     * GET /fapi/v1/ticker/price
     * <br>
     * 获取交易对最新价格
     */
    public BinancePriceTicker getTickerPrice(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return parse(futuresClient.market().tickerSymbol(parameters), BinancePriceTicker.class);
    }

    /**
     * Mark Price and Funding Rate (MARKET_DATA)
     * <br>
     * GET /fapi/v1/premiumIndex
     * <br>
     * 获取标记价格和资金费率
     */
    public BinanceFuturesPremiumIndex getPremiumIndex(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        return parse(futuresClient.market().markPrice(parameters), BinanceFuturesPremiumIndex.class);
    }

    /**
     * Get Funding Rate History (MARKET_DATA)
     * <br>
     * GET /fapi/v1/fundingRate
     * <br>
     * 获取资金费率历史
     */
    public List<BinanceFuturesFundingRate> getFundingRateHistory(String symbol, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (limit != null) parameters.put("limit", limit);
        return parse(futuresClient.market().fundingRate(parameters), new TypeReference<List<BinanceFuturesFundingRate>>() {});
    }

    /**
     * Symbol Price Ticker (All Symbols) (MARKET_DATA)
     * <br>
     * GET /fapi/v1/ticker/price
     * <br>
     * 获取所有交易对最新价格
     */
    public List<BinancePriceTicker> getTickerPrices() {
        return parse(futuresClient.market().tickerSymbol(new LinkedHashMap<>()), new TypeReference<List<BinancePriceTicker>>() {});
    }

    /**
     * Mark Price and Funding Rate (All Symbols) (MARKET_DATA)
     * <br>
     * GET /fapi/v1/premiumIndex
     * <br>
     * 获取所有交易对标记价格和资金费率
     */
    public List<BinanceFuturesPremiumIndex> getPremiumIndices() {
        return parse(futuresClient.market().markPrice(new LinkedHashMap<>()), new TypeReference<List<BinanceFuturesPremiumIndex>>() {});
    }

    /**
     * Historical BLVT NAV Kline/Candlestick (MARKET_DATA)
     * <br>
     * GET /fapi/v1/lvtKlines
     * <br>
     * 获取杠杆代币历史净值K线
     */
    public List<BinanceFuturesKline> getLvtKlines(String symbol, String interval, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("interval", interval);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        return parse(sendPublicRequest("/fapi/v1/lvtKlines", parameters), new TypeReference<List<BinanceFuturesKline>>() {});
    }

    /**
     * Symbol Index Constituents (MARKET_DATA)
     * <br>
     * GET /fapi/v1/constituents
     * <br>
     * 获取指数成分股
     */
    public BinanceFuturesConstituents getIndexConstituents(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return parse(sendPublicRequest("/fapi/v1/constituents", parameters), BinanceFuturesConstituents.class);
    }

    /**
     * Futures Trading Status Notification (MARKET_DATA)
     * <br>
     * GET /fapi/v1/apiTradingStatus
     * <br>
     * 获取合约交易状态
     */
    public BinanceFuturesTradingStatus getApiTradingStatus() {
        return parse(sendPublicRequest("/fapi/v1/apiTradingStatus", new LinkedHashMap<>()), BinanceFuturesTradingStatus.class);
    }

    /**
     * Futures Trading Status Notification (USER_DATA)
     * <br>
     * GET /fapi/v1/apiTradingStatus
     * <br>
     * 获取合约交易状态 (用户)
     */
    public BinanceFuturesTradingStatus getUserTradingStatus() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/apiTradingStatus", parameters, "GET", true), BinanceFuturesTradingStatus.class);
    }

    /**
     * Quarterly Contract Settlement Price (MARKET_DATA)
     * <br>
     * GET /fapi/v1/delivery-price
     * <br>
     * 获取季度合约交割价格
     */
    public List<BinanceFuturesDeliveryPrice> getDeliveryPrice(String pair) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pair", pair);
        return parse(sendPublicRequest("/fapi/v1/delivery-price", parameters), new TypeReference<List<BinanceFuturesDeliveryPrice>>() {});
    }

    /**
     * Get Funding Rate Info (MARKET_DATA)
     * <br>
     * GET /fapi/v1/fundingInfo
     * <br>
     * 获取资金费率信息
     */
    public List<BinanceFuturesFundingInfo> getFundingInfo() {
        return parse(sendPublicRequest("/fapi/v1/fundingInfo", new LinkedHashMap<>()), new TypeReference<List<BinanceFuturesFundingInfo>>() {});
    }

    // =========================== Account ===========================

    /**
     * Account Information V2 (USER_DATA)
     * <br>
     * GET /fapi/v2/account
     * <br>
     * 获取账户信息 (V2)
     */
    public BinanceFuturesAccount account() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().accountInformation(parameters), BinanceFuturesAccount.class);
    }

    /**
     * Position Information V2 (USER_DATA)
     * <br>
     * GET /fapi/v2/positionRisk
     * <br>
     * 获取持仓风险 (V2)
     */
    public List<BinanceFuturesPositionRisk> getPositionRisk(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().positionInformation(parameters), new TypeReference<List<BinanceFuturesPositionRisk>>() {});
    }

    /**
     * Change Initial Leverage (TRADE)
     * <br>
     * POST /fapi/v1/leverage
     * <br>
     * 调整开仓杠杆
     */
    public BinanceFuturesPositionRisk changeInitialLeverage(String symbol, Integer leverage) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("leverage", leverage);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().changeInitialLeverage(parameters), BinanceFuturesPositionRisk.class);
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

    /**
     * Modify Isolated Position Margin (TRADE)
     * <br>
     * POST /fapi/v1/positionMargin
     * <br>
     * 调整逐仓保证金
     */
    public void modifyIsolatedPositionMargin(String symbol, BigDecimal amount, Integer type, String positionSide) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("amount", amount);
        parameters.put("type", type);
        if (positionSide != null) parameters.put("positionSide", positionSide);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        executeRequest("/fapi/v1/positionMargin", parameters, "POST", true);
    }

    /**
     * Get Position Margin Change History (TRADE)
     * <br>
     * GET /fapi/v1/positionMargin/history
     * <br>
     * 获取逐仓保证金变动历史
     */
    public List<BinanceFuturesPositionMarginHistory> getPositionMarginChangeHistory(String symbol, Integer type, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (type != null) parameters.put("type", type);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/positionMargin/history", parameters, "GET", true), new TypeReference<List<BinanceFuturesPositionMarginHistory>>() {});
    }

    // =========================== Trade ===========================

    /**
     * Test New Order (TRADE)
     * <br>
     * POST /fapi/v1/order/test
     * <br>
     * 测试下单 (不实际下单)
     */
    public void testNewOrder(String symbol, String side, String positionSide, String type, 
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
        
        executeRequest("/fapi/v1/order/test", parameters, "POST", true);
    }

    /**
     * New Order (TRADE)
     * <br>
     * POST /fapi/v1/order
     * <br>
     * 下单
     */
    public BinanceFuturesOrder newOrder(String symbol, String side, String positionSide, String type, 
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
        
        return parse(futuresClient.account().newOrder(parameters), BinanceFuturesOrder.class);
    }

    /**
     * Place Order (Alias for newOrder)
     */
    public BinanceFuturesOrder placeOrder(String symbol, String side, String positionSide, String type, 
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
    public BinanceFuturesOrder getOrder(String symbol, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().queryOrder(parameters), BinanceFuturesOrder.class);
    }

    /**
     * Cancel Order (TRADE)
     * <br>
     * DELETE /fapi/v1/order
     * <br>
     * 撤销订单
     */
    public BinanceFuturesOrder cancelOrder(String symbol, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().cancelOrder(parameters), BinanceFuturesOrder.class);
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
    public List<BinanceFuturesOrder> getOpenOrders(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().currentAllOpenOrders(parameters), new TypeReference<List<BinanceFuturesOrder>>() {});
    }

    /**
     * Query Current Open Order (USER_DATA)
     * <br>
     * GET /fapi/v1/openOrder
     * <br>
     * 查询当前挂单
     */
    public BinanceFuturesOrder getOpenOrder(String symbol, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/openOrder", parameters, "GET", true), BinanceFuturesOrder.class);
    }

    /**
     * Place Multiple Orders (TRADE)
     * <br>
     * POST /fapi/v1/batchOrders
     * <br>
     * 批量下单
     */
    public List<BinanceFuturesOrder> batchOrders(String batchOrders) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("batchOrders", batchOrders);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().placeMultipleOrders(parameters), new TypeReference<List<BinanceFuturesOrder>>() {});
    }

    /**
     * Cancel Multiple Orders (TRADE)
     * <br>
     * DELETE /fapi/v1/batchOrders
     * <br>
     * 批量撤销订单
     */
    public List<BinanceFuturesOrder> cancelMultipleOrders(String symbol, List<Long> orderIdList, List<String> origClientOrderIdList) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        try {
            if (orderIdList != null) parameters.put("orderIdList", objectMapper.writeValueAsString(orderIdList));
            if (origClientOrderIdList != null) parameters.put("origClientOrderIdList", objectMapper.writeValueAsString(origClientOrderIdList));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize order list", e);
        }
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().cancelMultipleOrders(parameters), new TypeReference<List<BinanceFuturesOrder>>() {});
    }

    /**
     * Auto-Cancel All Open Orders (TRADE)
     * <br>
     * POST /fapi/v1/countdownCancelAll
     * <br>
     * 倒计时撤销所有订单
     */
    public BinanceFuturesCountDownCancel autoCancelAllOpenOrders(String symbol, Long countdownTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("countdownTime", countdownTime);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/countdownCancelAll", parameters, "POST", true), BinanceFuturesCountDownCancel.class);
    }

    /**
     * All Orders (USER_DATA)
     * <br>
     * GET /fapi/v1/allOrders
     * <br>
     * 查询所有订单 (历史订单)
     */
    public List<BinanceFuturesOrder> getAllOrders(String symbol, Long orderId, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().allOrders(parameters), new TypeReference<List<BinanceFuturesOrder>>() {});
    }

    /**
     * Future Account Balance (USER_DATA)
     * <br>
     * GET /fapi/v2/balance
     * <br>
     * 获取账户余额 (V2)
     */
    public List<BinanceFuturesAccountBalance> getFutureAccountBalance() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().futuresAccountBalance(parameters), new TypeReference<List<BinanceFuturesAccountBalance>>() {});
    }

    /**
     * Account Trade List (USER_DATA)
     * <br>
     * GET /fapi/v1/userTrades
     * <br>
     * 获取账户成交历史
     */
    public List<BinanceFuturesAccountTrade> getAccountTradeList(String symbol, Long startTime, Long endTime, Long fromId, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (fromId != null) parameters.put("fromId", fromId);
        if (limit != null) parameters.put("limit", limit);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(futuresClient.account().accountTradeList(parameters), new TypeReference<List<BinanceFuturesAccountTrade>>() {});
    }

    /**
     * Get Income History (USER_DATA)
     * <br>
     * GET /fapi/v1/income
     * <br>
     * 获取收益历史
     */
    public List<BinanceFuturesIncome> getIncomeHistory(String symbol, String incomeType, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (incomeType != null) parameters.put("incomeType", incomeType);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/income", parameters, "GET", true), new TypeReference<List<BinanceFuturesIncome>>() {});
    }

    /**
     * Get Download ID For Trade History (USER_DATA)
     * <br>
     * GET /fapi/v1/income/asyn
     * <br>
     * 获取交易历史下载ID
     */
    public BinanceFuturesDownloadId getDownloadIdForTradeHistory(Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("startTime", startTime);
        parameters.put("endTime", endTime);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/income/asyn", parameters, "GET", true), BinanceFuturesDownloadId.class);
    }

    /**
     * Get Download ID Status (USER_DATA)
     * <br>
     * GET /fapi/v1/income/asyn/id
     * <br>
     * 获取交易历史下载状态
     */
    public BinanceFuturesDownloadStatus getDownloadIdStatus(String downloadId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("downloadId", downloadId);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/income/asyn/id", parameters, "GET", true), BinanceFuturesDownloadStatus.class);
    }

    /**
     * Notional and Leverage Brackets (USER_DATA)
     * <br>
     * GET /fapi/v1/leverageBracket
     * <br>
     * 获取杠杆分层标准
     */
    public List<BinanceFuturesLeverageBracket> getLeverageBracket(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        // The API returns a list (sometimes wrapped, but usually list of brackets)
        // If symbol is present, returns list of 1. If not, list of all.
        return parse(executeRequest("/fapi/v1/leverageBracket", parameters, "GET", true), new TypeReference<List<BinanceFuturesLeverageBracket>>() {});
    }

    /**
     * Position ADL Quantile Estimation (USER_DATA)
     * <br>
     * GET /fapi/v1/adlQuantile
     * <br>
     * 仓位ADL队列估算
     */
    public List<BinanceFuturesAdlQuantile> getAdlQuantile(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/adlQuantile", parameters, "GET", true), new TypeReference<List<BinanceFuturesAdlQuantile>>() {});
    }

    /**
     * User Commission Rate (USER_DATA)
     * <br>
     * GET /fapi/v1/commissionRate
     * <br>
     * 获取用户手续费率
     */
    public BinanceFuturesCommissionRate getCommissionRate(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/commissionRate", parameters, "GET", true), BinanceFuturesCommissionRate.class);
    }

    /**
     * User's Force Orders (USER_DATA)
     * <br>
     * GET /fapi/v1/forceOrders
     * <br>
     * 用户强平订单
     */
    public List<BinanceFuturesForceOrder> getForceOrders(String symbol, String autoCloseType, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (autoCloseType != null) parameters.put("autoCloseType", autoCloseType);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/forceOrders", parameters, "GET", true), new TypeReference<List<BinanceFuturesForceOrder>>() {});
    }

    /**
     * Modify Order (TRADE)
     * <br>
     * PUT /fapi/v1/order
     * <br>
     * 修改订单
     */
    public BinanceFuturesOrder modifyOrder(String symbol, String side, BigDecimal quantity, BigDecimal price, Long orderId, String origClientOrderId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        parameters.put("symbol", symbol);
        parameters.put("side", side);
        if (quantity != null) parameters.put("quantity", quantity);
        if (price != null) parameters.put("price", price);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/order", parameters, "PUT", true), BinanceFuturesOrder.class);
    }

    /**
     * Modify Multiple Orders (TRADE)
     * <br>
     * PUT /fapi/v1/batchOrders
     * <br>
     * 批量修改订单
     */
    public List<BinanceFuturesOrder> modifyBatchOrders(List<Object> batchOrders) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        try {
            parameters.put("batchOrders", objectMapper.writeValueAsString(batchOrders));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize batchOrders", e);
        }
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/batchOrders", parameters, "PUT", true), new TypeReference<List<BinanceFuturesOrder>>() {});
    }

    /**
     * Get Order Modify History (USER_DATA)
     * <br>
     * GET /fapi/v1/orderAmendment
     * <br>
     * 查询订单修改历史
     */
    public List<BinanceFuturesOrder> getOrderModifyHistory(String symbol, Long orderId, String origClientOrderId, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/orderAmendment", parameters, "GET", true), new TypeReference<List<BinanceFuturesOrder>>() {});
    }

    /**
     * Change Position Mode (TRADE)
     * <br>
     * POST /fapi/v1/positionSide/dual
     * <br>
     * 更改持仓模式
     */
    public void changePositionMode(String dualSidePosition) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("dualSidePosition", dualSidePosition);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        executeRequest("/fapi/v1/positionSide/dual", parameters, "POST", true);
    }

    /**
     * Get Current Position Mode (USER_DATA)
     * <br>
     * GET /fapi/v1/positionSide/dual
     * <br>
     * 查询持仓模式
     */
    public BinanceFuturesPositionMode getPositionMode() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/positionSide/dual", parameters, "GET", true), BinanceFuturesPositionMode.class);
    }

    /**
     * Change Multi-Assets Mode (TRADE)
     * <br>
     * POST /fapi/v1/multiAssetsMargin
     * <br>
     * 更改联合保证金模式
     */
    public void changeMultiAssetsMode(String multiAssetsMargin) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("multiAssetsMargin", multiAssetsMargin);
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        executeRequest("/fapi/v1/multiAssetsMargin", parameters, "POST", true);
    }

    /**
     * Get Current Multi-Assets Mode (USER_DATA)
     * <br>
     * GET /fapi/v1/multiAssetsMargin
     * <br>
     * 查询联合保证金模式
     */
    public BinanceFuturesMultiAssetsMode getMultiAssetsMode() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recvWindow", DEFAULT_RECV_WINDOW);
        return parse(executeRequest("/fapi/v1/multiAssetsMargin", parameters, "GET", true), BinanceFuturesMultiAssetsMode.class);
    }

    // =========================== Helper Methods (Hedge Mode) ===========================

    /**
     * Open Long (Hedge Mode)
     */
    public BinanceFuturesOrder openLong(String symbol, String type, String quantity, String price, String timeInForce) {
        return newOrder(symbol, "BUY", "LONG", type, timeInForce, quantity, price, null);
    }

    /**
     * Close Long (Hedge Mode)
     */
    public BinanceFuturesOrder closeLong(String symbol, String type, String quantity, String price) {
        return newOrder(symbol, "SELL", "LONG", type, null, quantity, price, null);
    }

    /**
     * Open Short (Hedge Mode)
     */
    public BinanceFuturesOrder openShort(String symbol, String type, String quantity, String price, String timeInForce) {
        return newOrder(symbol, "SELL", "SHORT", type, timeInForce, quantity, price, null);
    }

    /**
     * Close Short (Hedge Mode)
     */
    public BinanceFuturesOrder closeShort(String symbol, String type, String quantity, String price) {
        return newOrder(symbol, "BUY", "SHORT", type, null, quantity, price, null);
    }
}
