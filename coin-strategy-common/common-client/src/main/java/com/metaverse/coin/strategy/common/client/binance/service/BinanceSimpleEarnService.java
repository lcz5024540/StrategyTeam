package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.base.utils.SignUtils;
import com.metaverse.coin.strategy.common.model.binance.entity.simpleearn.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class BinanceSimpleEarnService {
    private final SpotClient spotClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String secretKey;

    public BinanceSimpleEarnService(SpotClient spotClient, String baseUrl, String apiKey, String secretKey) {
        this.spotClient = spotClient;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.binance.com";
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.httpClient = new OkHttpClient();
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // =========================== Flexible Product ===========================

    /**
     * Get Simple Earn Flexible Product List (Simple Earn)
     * <br>
     * GET /sapi/v1/simple-earn/flexible/list
     * <br>
     * 获取活期产品列表
     */
    public BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexibleProduct> getFlexibleProductList(String asset, Long current, Integer size) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (asset != null) parameters.put("asset", asset);
        if (current != null) parameters.put("current", current);
        if (size != null) parameters.put("size", size);
        
        String json = executeRequest("/sapi/v1/simple-earn/flexible/list", parameters, "GET", true);
        return parseListResponse(json, new TypeReference<BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexibleProduct>>() {});
    }

    /**
     * Subscribe Flexible Product (Simple Earn)
     * <br>
     * POST /sapi/v1/simple-earn/flexible/subscribe
     * <br>
     * 申购活期产品
     */
    public String subscribeFlexibleProduct(String productId, BigDecimal amount, Boolean autoSubscribe, String sourceAccount) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("productId", productId);
        parameters.put("amount", amount);
        if (autoSubscribe != null) parameters.put("autoSubscribe", autoSubscribe);
        if (sourceAccount != null) parameters.put("sourceAccount", sourceAccount);
        
        return executeRequest("/sapi/v1/simple-earn/flexible/subscribe", parameters, "POST", true);
    }

    /**
     * Redeem Flexible Product (Simple Earn)
     * <br>
     * POST /sapi/v1/simple-earn/flexible/redeem
     * <br>
     * 赎回活期产品
     */
    public String redeemFlexibleProduct(String productId, BigDecimal amount, String type, Boolean redeemAll, String destAccount) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("productId", productId);
        if (amount != null) parameters.put("amount", amount);
        if (type != null) parameters.put("type", type); // FAST or NORMAL
        if (redeemAll != null) parameters.put("redeemAll", redeemAll);
        if (destAccount != null) parameters.put("destAccount", destAccount); // SPOT or FUNDING
        
        return executeRequest("/sapi/v1/simple-earn/flexible/redeem", parameters, "POST", true);
    }

    /**
     * Get Flexible Product Position (Simple Earn)
     * <br>
     * GET /sapi/v1/simple-earn/flexible/position
     * <br>
     * 获取活期持仓
     */
    public BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexiblePosition> getFlexibleProductPosition(String asset, String productId, Long current, Integer size) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (asset != null) parameters.put("asset", asset);
        if (productId != null) parameters.put("productId", productId);
        if (current != null) parameters.put("current", current);
        if (size != null) parameters.put("size", size);
        
        String json = executeRequest("/sapi/v1/simple-earn/flexible/position", parameters, "GET", true);
        return parseListResponse(json, new TypeReference<BinanceSimpleEarnListResponse<BinanceSimpleEarnFlexiblePosition>>() {});
    }

    // =========================== Locked Product ===========================

    /**
     * Get Simple Earn Locked Product List (Simple Earn)
     * <br>
     * GET /sapi/v1/simple-earn/locked/list
     * <br>
     * 获取定期产品列表
     */
    public BinanceSimpleEarnListResponse<BinanceSimpleEarnLockedProduct> getLockedProductList(String asset, Long current, Integer size) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (asset != null) parameters.put("asset", asset);
        if (current != null) parameters.put("current", current);
        if (size != null) parameters.put("size", size);
        
        String json = executeRequest("/sapi/v1/simple-earn/locked/list", parameters, "GET", true);
        return parseListResponse(json, new TypeReference<BinanceSimpleEarnListResponse<BinanceSimpleEarnLockedProduct>>() {});
    }

    /**
     * Subscribe Locked Product (Simple Earn)
     * <br>
     * POST /sapi/v1/simple-earn/locked/subscribe
     * <br>
     * 申购定期产品
     */
    public String subscribeLockedProduct(String projectId, BigDecimal amount, Boolean autoSubscribe, String sourceAccount, String redeemTo) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("projectId", projectId);
        parameters.put("amount", amount);
        if (autoSubscribe != null) parameters.put("autoSubscribe", autoSubscribe);
        if (sourceAccount != null) parameters.put("sourceAccount", sourceAccount);
        if (redeemTo != null) parameters.put("redeemTo", redeemTo); // SPOT or FUNDING
        
        return executeRequest("/sapi/v1/simple-earn/locked/subscribe", parameters, "POST", true);
    }

    /**
     * Redeem Locked Product (TRADE)
     */
    public String redeemLockedProduct(String positionId, String redeemTo) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("positionId", positionId);
        if (redeemTo != null) parameters.put("redeemTo", redeemTo);
        
        return executeRequest("/sapi/v1/simple-earn/locked/redeem", parameters, "POST", true);
    }

    /**
     * Get Locked Product Position (USER_DATA)
     */
    public BinanceSimpleEarnListResponse<BinanceSimpleEarnLockedPosition> getLockedProductPosition(String asset, String positionId, String projectId, Long current, Integer size) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (asset != null) parameters.put("asset", asset);
        if (positionId != null) parameters.put("positionId", positionId);
        if (projectId != null) parameters.put("projectId", projectId);
        if (current != null) parameters.put("current", current);
        if (size != null) parameters.put("size", size);
        
        String json = executeRequest("/sapi/v1/simple-earn/locked/position", parameters, "GET", true);
        return parseListResponse(json, new TypeReference<BinanceSimpleEarnListResponse<BinanceSimpleEarnLockedPosition>>() {});
    }

    // =========================== Account ===========================

    /**
     * Get Simple Earn Account (Simple Earn)
     * <br>
     * GET /sapi/v1/simple-earn/account
     * <br>
     * 获取理财账户信息
     */
    public BinanceSimpleEarnAccount getAccount() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/simple-earn/account", parameters, "GET", true);
        return parse(json, BinanceSimpleEarnAccount.class);
    }

    // =========================== Private Helpers ===========================

    private <T> T parse(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("Failed to parse response: {}", json, e);
            throw new RuntimeException("Failed to parse response", e);
        }
    }

    private <T> T parseListResponse(String json, TypeReference<T> typeReference) {
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
            // For POST requests in Binance API, parameters are usually sent in the query string or body.
            // But signed endpoints often require parameters in the query string or body to be signed.
            // Here we put everything in the body if it's POST, but for signed requests, 
            // the signature covers the body content if application/x-www-form-urlencoded is used.
            // The logic from BinanceMarginService puts everything in the body for POST/PUT/DELETE.
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
