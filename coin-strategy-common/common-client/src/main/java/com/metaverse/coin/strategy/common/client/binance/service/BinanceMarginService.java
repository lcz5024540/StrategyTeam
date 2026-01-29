package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.base.utils.SignUtils;
import com.metaverse.coin.strategy.common.model.binance.entity.margin.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class BinanceMarginService {
    private final SpotClient spotClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String secretKey;

    public BinanceMarginService(SpotClient spotClient, String baseUrl, String apiKey, String secretKey) {
        this.spotClient = spotClient;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.binance.com";
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.httpClient = new OkHttpClient();
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // =========================== Account ===========================

    public BinanceCrossMarginAccount getCrossMarginAccountDetails() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/margin/account", parameters, "GET", true);
        return parse(json, BinanceCrossMarginAccount.class);
    }

    public BinanceIsolatedMarginAccount getIsolatedMarginAccountInfo(String symbols) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbols != null) parameters.put("symbols", symbols);
        String json = executeRequest("/sapi/v1/margin/isolated/account", parameters, "GET", true);
        return parse(json, BinanceIsolatedMarginAccount.class);
    }

    public String adjustCrossMarginMaxLeverage(Integer maxLeverage) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("maxLeverage", maxLeverage);
        return executeRequest("/sapi/v1/margin/maxLeverage", parameters, "POST", true);
    }

    public String disableIsolatedMarginAccount(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return executeRequest("/sapi/v1/margin/isolated/account", parameters, "DELETE", true);
    }

    public String enableIsolatedMarginAccount(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        return executeRequest("/sapi/v1/margin/isolated/account", parameters, "POST", true);
    }

    public List<BinanceBnbBurnStatus> getBnbBurnStatus() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/margin/bnbBurn", parameters, "GET", true);
        // Sometimes returns object {"spotBNBBurn":true,"interestBNBBurn":false}, sometimes list.
        // If object, wrap in list.
        try {
            if (json.trim().startsWith("[")) {
                return parseList(json, new TypeReference<List<BinanceBnbBurnStatus>>() {});
            } else {
                return Collections.singletonList(parse(json, BinanceBnbBurnStatus.class));
            }
        } catch (Exception e) {
            log.error("Failed to parse BNB Burn Status", e);
            throw new RuntimeException("Failed to parse BNB Burn Status", e);
        }
    }

    public BinanceMarginTradeCoeff getMarginTradeCoeff() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/margin/tradeCoeff", parameters, "GET", true);
        return parse(json, BinanceMarginTradeCoeff.class);
    }

    public List<BinanceMarginCapitalFlow> getCapitalFlow(String asset, String symbol, String type, Long startTime, Long endTime, Long fromId, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (asset != null) parameters.put("asset", asset);
        if (symbol != null) parameters.put("symbol", symbol);
        if (type != null) parameters.put("type", type);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (fromId != null) parameters.put("fromId", fromId);
        if (limit != null) parameters.put("limit", limit);
        String json = executeRequest("/sapi/v1/margin/capital-flow", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginCapitalFlow>>() {});
    }

    public List<BinanceMarginFeeData> getCrossMarginFeeData(String coin, Integer vipLevel) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (coin != null) parameters.put("coin", coin);
        if (vipLevel != null) parameters.put("vipLevel", vipLevel);
        String json = executeRequest("/sapi/v1/margin/crossMarginData", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginFeeData>>() {});
    }

    public List<BinanceIsolatedMarginFeeData> getIsolatedMarginFeeData(String symbol, Integer vipLevel) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (vipLevel != null) parameters.put("vipLevel", vipLevel);
        String json = executeRequest("/sapi/v1/margin/isolatedMarginData", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceIsolatedMarginFeeData>>() {});
    }

    public BinanceIsolatedMarginAccountLimit getIsolatedMarginAccountLimit() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/margin/isolated/accountLimit", parameters, "GET", true);
        return parse(json, BinanceIsolatedMarginAccountLimit.class);
    }
    
    public List<BinanceMarginOrderCount> getOrderCountUsage(String isIsolated, String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (isIsolated != null) parameters.put("isIsolated", isIsolated);
        if (symbol != null) parameters.put("symbol", symbol);
        String json = executeRequest("/sapi/v1/margin/rateLimit/order", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginOrderCount>>() {});
    }

    // =========================== Borrow / Repay / Transfer ===========================

    public BinanceMarginTransaction borrow(String asset, String amount, String symbol, Boolean isIsolated) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("asset", asset);
        parameters.put("amount", amount);
        if (symbol != null) parameters.put("symbol", symbol);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated ? "TRUE" : "FALSE");
        String json = executeRequest("/sapi/v1/margin/loan", parameters, "POST", true);
        return parse(json, BinanceMarginTransaction.class);
    }

    public BinanceMarginTransaction repay(String asset, String amount, String symbol, Boolean isIsolated) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("asset", asset);
        parameters.put("amount", amount);
        if (symbol != null) parameters.put("symbol", symbol);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated ? "TRUE" : "FALSE");
        String json = executeRequest("/sapi/v1/margin/repay", parameters, "POST", true);
        return parse(json, BinanceMarginTransaction.class);
    }

    public List<BinanceMarginBorrowRepayRecord> getBorrowRepayRecords(String asset, String isolatedSymbol, Long txId, Long startTime, Long endTime, Integer current, Integer size, String type) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (asset != null) parameters.put("asset", asset);
        if (isolatedSymbol != null) parameters.put("isolatedSymbol", isolatedSymbol);
        if (txId != null) parameters.put("txId", txId);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (current != null) parameters.put("current", current);
        if (size != null) parameters.put("size", size);
        if (type != null) parameters.put("type", type);
        String json = executeRequest("/sapi/v1/margin/borrow-repay", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginBorrowRepayRecord>>() {});
    }

    public List<BinanceMarginInterestHistory> getInterestHistory(String asset, String isolatedSymbol, Long startTime, Long endTime, Integer current, Integer size) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (asset != null) parameters.put("asset", asset);
        if (isolatedSymbol != null) parameters.put("isolatedSymbol", isolatedSymbol);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (current != null) parameters.put("current", current);
        if (size != null) parameters.put("size", size);
        String json = executeRequest("/sapi/v1/margin/interestHistory", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginInterestHistory>>() {});
    }

    public List<BinanceMarginInterestRateHistory> getInterestRateHistory(String asset, Integer vipLevel, Long startTime, Long endTime) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("asset", asset);
        if (vipLevel != null) parameters.put("vipLevel", vipLevel);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        String json = executeRequest("/sapi/v1/margin/interestRateHistory", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginInterestRateHistory>>() {});
    }

    public List<BinanceMarginNextHourlyInterestRate> getFutureHourlyInterestRate(String assets, Boolean isIsolated) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (assets != null) parameters.put("assets", assets);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated ? "TRUE" : "FALSE");
        String json = executeRequest("/sapi/v1/margin/next-hourly-interest-rate", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginNextHourlyInterestRate>>() {});
    }

    public BinanceMarginMaxBorrowable getMaxBorrowable(String asset, String isolatedSymbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("asset", asset);
        if (isolatedSymbol != null) parameters.put("isolatedSymbol", isolatedSymbol);
        String json = executeRequest("/sapi/v1/margin/maxBorrowable", parameters, "GET", true);
        return parse(json, BinanceMarginMaxBorrowable.class);
    }
    
    public BinanceMarginMaxTransferable getMaxTransferable(String asset, String isolatedSymbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("asset", asset);
        if (isolatedSymbol != null) parameters.put("isolatedSymbol", isolatedSymbol);
        String json = executeRequest("/sapi/v1/margin/maxTransferable", parameters, "GET", true);
        return parse(json, BinanceMarginMaxTransferable.class);
    }
    
    public BinanceMarginTransferResult transfer(String asset, String amount, Integer type) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("asset", asset);
        parameters.put("amount", amount);
        parameters.put("type", type);
        String json = executeRequest("/sapi/v1/margin/transfer", parameters, "POST", true);
        return parse(json, BinanceMarginTransferResult.class);
    }
    
    public BinanceMarginTransferResult isolatedTransfer(String asset, String symbol, String amount, String transFrom, String transTo) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("asset", asset);
        parameters.put("symbol", symbol);
        parameters.put("amount", amount);
        parameters.put("transFrom", transFrom);
        parameters.put("transTo", transTo);
        String json = executeRequest("/sapi/v1/margin/isolated/transfer", parameters, "POST", true);
        return parse(json, BinanceMarginTransferResult.class);
    }

    // =========================== Market Data ===========================

    public List<BinanceMarginAsset> getMarginAssets() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/margin/allAssets", parameters, "GET", false); // Usually public
        return parseList(json, new TypeReference<List<BinanceMarginAsset>>() {});
    }

    public List<BinanceMarginPair> getMarginPairs() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/margin/allPairs", parameters, "GET", false); // Usually public
        return parseList(json, new TypeReference<List<BinanceMarginPair>>() {});
    }

    public BinanceMarginPriceIndex getMarginPriceIndex(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        String json = executeRequest("/sapi/v1/margin/priceIndex", parameters, "GET", false); // Public
        return parse(json, BinanceMarginPriceIndex.class);
    }

    public List<BinanceIsolatedMarginSymbol> getIsolatedMarginSymbols(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        String json = executeRequest("/sapi/v1/margin/isolated/allPairs", parameters, "GET", true); // Requires API Key usually
        return parseList(json, new TypeReference<List<BinanceIsolatedMarginSymbol>>() {});
    }
    
    public BinanceIsolatedMarginSymbol getIsolatedMarginSymbol(String symbol) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        String json = executeRequest("/sapi/v1/margin/isolated/pair", parameters, "GET", true);
        return parse(json, BinanceIsolatedMarginSymbol.class);
    }
    
    public List<BinanceCrossMarginCollateralRatio> getCrossMarginCollateralRatio() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/margin/crossMarginCollateralRatio", parameters, "GET", false); // Public? API Key? Assume safe to use API Key if needed
        return parseList(json, new TypeReference<List<BinanceCrossMarginCollateralRatio>>() {});
    }
    
    public List<BinanceMarginDelistSchedule> getDelistSchedule() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/margin/delist-schedule", parameters, "GET", false); // Public?
        return parseList(json, new TypeReference<List<BinanceMarginDelistSchedule>>() {});
    }

    // =========================== Trade ===========================

    public BinanceMarginOrder newOrder(String symbol, String side, String type, String quantity, String price, String isIsolated, String sideEffectType, String timeInForce) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("side", side);
        parameters.put("type", type);
        if (quantity != null) parameters.put("quantity", quantity);
        if (price != null) parameters.put("price", price);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated);
        if (sideEffectType != null) parameters.put("sideEffectType", sideEffectType);
        if (timeInForce != null) parameters.put("timeInForce", timeInForce);
        String json = executeRequest("/sapi/v1/margin/order", parameters, "POST", true);
        return parse(json, BinanceMarginOrder.class);
    }

    public BinanceMarginOrder cancelOrder(String symbol, Long orderId, String origClientOrderId, Boolean isIsolated) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated ? "TRUE" : "FALSE");
        String json = executeRequest("/sapi/v1/margin/order", parameters, "DELETE", true);
        return parse(json, BinanceMarginOrder.class);
    }

    public List<BinanceMarginOrder> getOpenOrders(String symbol, Boolean isIsolated) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated ? "TRUE" : "FALSE");
        String json = executeRequest("/sapi/v1/margin/openOrders", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginOrder>>() {});
    }

    public List<BinanceMarginOrder> getAllOrders(String symbol, Boolean isIsolated, Long startTime, Long endTime, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated ? "TRUE" : "FALSE");
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (limit != null) parameters.put("limit", limit);
        String json = executeRequest("/sapi/v1/margin/allOrders", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginOrder>>() {});
    }
    
    public BinanceMarginOrder getOrder(String symbol, Long orderId, String origClientOrderId, Boolean isIsolated) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (orderId != null) parameters.put("orderId", orderId);
        if (origClientOrderId != null) parameters.put("origClientOrderId", origClientOrderId);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated ? "TRUE" : "FALSE");
        String json = executeRequest("/sapi/v1/margin/order", parameters, "GET", true);
        return parse(json, BinanceMarginOrder.class);
    }
    
    public List<BinanceMarginTrade> getMyTrades(String symbol, Boolean isIsolated, Long startTime, Long endTime, Long fromId, Integer limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        if (isIsolated != null) parameters.put("isIsolated", isIsolated ? "TRUE" : "FALSE");
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (fromId != null) parameters.put("fromId", fromId);
        if (limit != null) parameters.put("limit", limit);
        String json = executeRequest("/sapi/v1/margin/myTrades", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginTrade>>() {});
    }

    public List<BinanceMarginForceLiquidationRecord> getForceLiquidationRecord(Long startTime, Long endTime, String isolatedSymbol, Integer current, Integer size) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (isolatedSymbol != null) parameters.put("isolatedSymbol", isolatedSymbol);
        if (current != null) parameters.put("current", current);
        if (size != null) parameters.put("size", size);
        String json = executeRequest("/sapi/v1/margin/forceLiquidationRec", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceMarginForceLiquidationRecord>>() {});
    }

    // =========================== Risk Stream ===========================
    
    public String startUserDataStream() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        return executeRequest("/sapi/v1/userDataStream", parameters, "POST", true);
    }
    
    public String keepAliveUserDataStream(String listenKey) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("listenKey", listenKey);
        return executeRequest("/sapi/v1/userDataStream", parameters, "PUT", true);
    }
    
    public String closeUserDataStream(String listenKey) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("listenKey", listenKey);
        return executeRequest("/sapi/v1/userDataStream", parameters, "DELETE", true);
    }

    // =========================== Helper Methods ===========================

    private <T> T parse(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("Failed to parse response: {}", json, e);
            throw new RuntimeException("Failed to parse response", e);
        }
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            log.error("Failed to parse list response: {}", json, e);
            throw new RuntimeException("Failed to parse list response", e);
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
        Request.Builder requestBuilder = new Request.Builder()
                .addHeader("X-MBX-APIKEY", apiKey);

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
}
