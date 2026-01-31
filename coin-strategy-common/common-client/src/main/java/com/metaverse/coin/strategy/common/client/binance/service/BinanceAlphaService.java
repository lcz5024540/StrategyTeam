package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.base.utils.SignUtils;
import com.metaverse.coin.strategy.common.model.binance.entity.alpha.BinanceAlphaOrder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for Binance Alpha (Algo) API endpoints.
 * Implements endpoints typically found in clients/alpha or clients/algo.
 */
@Slf4j
public class BinanceAlphaService {
    private final SpotClient spotClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String secretKey;

    public BinanceAlphaService(SpotClient spotClient, String baseUrl, String apiKey, String secretKey) {
        this.spotClient = spotClient;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.binance.com";
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.httpClient = new OkHttpClient();
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Place a new Algo Order (VP/TWAP)
     * <br>
     * POST /sapi/v1/algo/spot/newOrder
     * <br>
     * 下单 (VP/TWAP 策略)
     * <br>
     * @param symbol Trading symbol, e.g. BNBUSDT
     * @param side BUY or SELL
     * @param quantity Quantity of base asset
     * @param algoType Algo type: VP or TWAP
     * @param additionalParams Additional parameters (limitPrice, urgency, etc.)
     * @return BinanceAlphaOrder
     */
    public BinanceAlphaOrder newOrder(String symbol, String side, String quantity, String algoType, Map<String, Object> additionalParams) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("side", side);
        parameters.put("quantity", quantity);
        parameters.put("algoType", algoType);
        if (additionalParams != null) {
            parameters.putAll(additionalParams);
        }
        String json = executeRequest("/sapi/v1/algo/spot/newOrder", parameters, "POST", true);
        return parse(json, BinanceAlphaOrder.class);
    }

    /**
     * Cancel an Algo Order
     * <br>
     * DELETE /sapi/v1/algo/spot/order
     * <br>
     * 取消策略订单
     * <br>
     * @param algoId Algo Order ID
     * @return BinanceAlphaOrder
     */
    public BinanceAlphaOrder cancelOrder(Long algoId) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("algoId", algoId);
        String json = executeRequest("/sapi/v1/algo/spot/order", parameters, "DELETE", true);
        return parse(json, BinanceAlphaOrder.class);
    }

    /**
     * Get Open Algo Orders
     * <br>
     * GET /sapi/v1/algo/spot/openOrders
     * <br>
     * 查询进行中的策略订单
     * <br>
     * @return List of BinanceAlphaOrder
     */
    public List<BinanceAlphaOrder> getOpenOrders() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/algo/spot/openOrders", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceAlphaOrder>>() {});
    }

    /**
     * Get Historical Algo Orders
     * <br>
     * GET /sapi/v1/algo/spot/historicalOrders
     * <br>
     * 查询历史策略订单
     * <br>
     * @param symbol Trading symbol (optional)
     * @param side BUY or SELL (optional)
     * @param startTime Start time in ms (optional)
     * @param endTime End time in ms (optional)
     * @param page Page number (optional)
     * @param pageSize Page size (optional)
     * @return List of BinanceAlphaOrder
     */
    public List<BinanceAlphaOrder> getHistoricalOrders(String symbol, String side, Long startTime, Long endTime, Integer page, Integer pageSize) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (symbol != null) parameters.put("symbol", symbol);
        if (side != null) parameters.put("side", side);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        if (page != null) parameters.put("page", page);
        if (pageSize != null) parameters.put("pageSize", pageSize);
        
        String json = executeRequest("/sapi/v1/algo/spot/historicalOrders", parameters, "GET", true);
        return parseList(json, new TypeReference<List<BinanceAlphaOrder>>() {});
    }

    /**
     * Query Sub Orders
     * <br>
     * GET /sapi/v1/algo/spot/subOrders
     * <br>
     * 查询子订单
     * <br>
     * @param algoId Algo Order ID
     * @param page Page number (optional)
     * @param pageSize Page size (optional)
     * @return JSON String of sub orders
     */
    public String getSubOrders(Long algoId, Integer page, Integer pageSize) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (algoId != null) parameters.put("algoId", algoId);
        if (page != null) parameters.put("page", page);
        if (pageSize != null) parameters.put("pageSize", pageSize);

        return executeRequest("/sapi/v1/algo/spot/subOrders", parameters, "GET", true);
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
