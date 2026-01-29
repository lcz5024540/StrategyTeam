package com.metaverse.coin.strategy.common.client.binance.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.base.utils.SignUtils;
import com.metaverse.coin.strategy.common.model.binance.entity.mining.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class BinanceMiningService {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String secretKey;

    public BinanceMiningService(String baseUrl, String apiKey, String secretKey) {
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.binance.com";
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.httpClient = new OkHttpClient();
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Acquiring Algorithm (MARKET_DATA)
     * GET /sapi/v1/mining/pub/algoList
     */
    public List<BinanceMiningAlgo> getAlgoList() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/mining/pub/algoList", parameters, "GET", false, true);
        return parseResponse(json, new TypeReference<List<BinanceMiningAlgo>>() {});
    }

    /**
     * Acquiring CoinName (MARKET_DATA)
     * GET /sapi/v1/mining/pub/coinList
     */
    public List<BinanceMiningCoin> getCoinList() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        String json = executeRequest("/sapi/v1/mining/pub/coinList", parameters, "GET", false, true);
        return parseResponse(json, new TypeReference<List<BinanceMiningCoin>>() {});
    }

    /**
     * Request for Detail Miner List (USER_DATA)
     * GET /sapi/v1/mining/worker/detail
     */
    public List<BinanceMiningWorker> getWorkerDetail(String algo, String userName, String workerName) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("algo", algo);
        parameters.put("userName", userName);
        parameters.put("workerName", workerName);
        String json = executeRequest("/sapi/v1/mining/worker/detail", parameters, "GET", true, true);
        return parseResponse(json, new TypeReference<List<BinanceMiningWorker>>() {});
    }

    /**
     * Request for Miner List (USER_DATA)
     * GET /sapi/v1/mining/worker/list
     */
    public List<BinanceMiningWorker> getWorkerList(String algo, String userName, Integer pageIndex, Integer sort, Integer sortColumn, Integer workerStatus) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("algo", algo);
        parameters.put("userName", userName);
        if (pageIndex != null) parameters.put("pageIndex", pageIndex);
        if (sort != null) parameters.put("sort", sort);
        if (sortColumn != null) parameters.put("sortColumn", sortColumn);
        if (workerStatus != null) parameters.put("workerStatus", workerStatus);
        
        String json = executeRequest("/sapi/v1/mining/worker/list", parameters, "GET", true, true);
        // Worker list response might be wrapped in a page object or just list in 'data'.
        // Assuming 'data' contains the list based on 'example_rest.md' typical structure.
        // If 'data' is a page object { total: 100, workerDatas: [...] }, we need to handle that.
        // Let's assume list for now or check response structure later.
        // Common mining response for list: "data": { "workerDatas": [...], "total": ... }
        // I'll need to parse 'workerDatas' specifically if that's the case.
        return parseResponseExtractField(json, "workerDatas", new TypeReference<List<BinanceMiningWorker>>() {});
    }

    /**
     * Earnings List (USER_DATA)
     * GET /sapi/v1/mining/payment/list
     */
    public List<BinanceMiningPayment> getPaymentList(String algo, String userName, String coin, Long startDate, Long endDate, Integer pageIndex, Integer pageSize) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("algo", algo);
        parameters.put("userName", userName);
        if (coin != null) parameters.put("coin", coin);
        if (startDate != null) parameters.put("startDate", startDate);
        if (endDate != null) parameters.put("endDate", endDate);
        if (pageIndex != null) parameters.put("pageIndex", pageIndex);
        if (pageSize != null) parameters.put("pageSize", pageSize);
        
        String json = executeRequest("/sapi/v1/mining/payment/list", parameters, "GET", true, true);
        return parseResponseExtractField(json, "accountProfits", new TypeReference<List<BinanceMiningPayment>>() {});
    }

    /**
     * Extra Bonus List (USER_DATA)
     * GET /sapi/v1/mining/payment/other
     */
    public List<BinanceMiningPayment> getOtherPaymentList(String algo, String userName, String coin, Long startDate, Long endDate, Integer pageIndex, Integer pageSize) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("algo", algo);
        parameters.put("userName", userName);
        if (coin != null) parameters.put("coin", coin);
        if (startDate != null) parameters.put("startDate", startDate);
        if (endDate != null) parameters.put("endDate", endDate);
        if (pageIndex != null) parameters.put("pageIndex", pageIndex);
        if (pageSize != null) parameters.put("pageSize", pageSize);

        String json = executeRequest("/sapi/v1/mining/payment/other", parameters, "GET", true, true);
        return parseResponseExtractField(json, "otherProfits", new TypeReference<List<BinanceMiningPayment>>() {});
    }

    /**
     * Hashrate Resale List (USER_DATA)
     * GET /sapi/v1/mining/hash-transfer/config/details
     */
    public List<BinanceHashrateResaleConfig> getHashrateResaleList(Integer pageIndex, Integer pageSize) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        if (pageIndex != null) parameters.put("pageIndex", pageIndex);
        if (pageSize != null) parameters.put("pageSize", pageSize);
        
        String json = executeRequest("/sapi/v1/mining/hash-transfer/config/details", parameters, "GET", true, true);
        return parseResponseExtractField(json, "configDetails", new TypeReference<List<BinanceHashrateResaleConfig>>() {});
    }

    /**
     * Hashrate Resale Detail (USER_DATA)
     * GET /sapi/v1/mining/hash-transfer/profit/details
     */
    public List<BinanceHashrateResaleDetail> getHashrateResaleDetail(Integer configId, String userName, Integer pageIndex, Integer pageSize) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("configId", configId);
        parameters.put("userName", userName);
        if (pageIndex != null) parameters.put("pageIndex", pageIndex);
        if (pageSize != null) parameters.put("pageSize", pageSize);

        String json = executeRequest("/sapi/v1/mining/hash-transfer/profit/details", parameters, "GET", true, true);
        return parseResponseExtractField(json, "profitTransferDetails", new TypeReference<List<BinanceHashrateResaleDetail>>() {});
    }

    /**
     * Hashrate Resale Request (USER_DATA)
     * POST /sapi/v1/mining/hash-transfer/config
     */
    public Long hashrateResaleRequest(String userName, String algo, String toPoolUser, Long endDate, Long startDate, String toPoolUserRegion, Long hashRate) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("userName", userName);
        parameters.put("algo", algo);
        parameters.put("toPoolUser", toPoolUser);
        parameters.put("endDate", endDate);
        parameters.put("startDate", startDate);
        parameters.put("toPoolUserRegion", toPoolUserRegion);
        parameters.put("hashRate", hashRate);

        String json = executeRequest("/sapi/v1/mining/hash-transfer/config", parameters, "POST", true, true);
        // Returns configId
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.has("data")) {
                return root.get("data").asLong();
            }
            return root.asLong();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse configId", e);
        }
    }

    /**
     * Cancel Hashrate Resale Configuration (USER_DATA)
     * POST /sapi/v1/mining/hash-transfer/config/cancel
     */
    public boolean cancelHashrateResale(Integer configId, String userName) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("configId", configId);
        parameters.put("userName", userName);
        
        String json = executeRequest("/sapi/v1/mining/hash-transfer/config/cancel", parameters, "POST", true, true);
        try {
            JsonNode root = objectMapper.readTree(json);
            return root.has("code") && root.get("code").asInt() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Statistic List (USER_DATA)
     * GET /sapi/v1/mining/statistics/user/status
     */
    public BinanceMiningStat getStatistic(String algo, String userName) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("algo", algo);
        parameters.put("userName", userName);
        
        String json = executeRequest("/sapi/v1/mining/statistics/user/status", parameters, "GET", true, true);
        return parseResponse(json, new TypeReference<BinanceMiningStat>() {});
    }

    /**
     * Account List (USER_DATA)
     * GET /sapi/v1/mining/statistics/user/list
     */
    public List<BinanceMiningAccount> getAccountList(String algo, String userName) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("algo", algo);
        parameters.put("userName", userName);

        String json = executeRequest("/sapi/v1/mining/statistics/user/list", parameters, "GET", true, true);
        return parseResponse(json, new TypeReference<List<BinanceMiningAccount>>() {});
    }

    // =========================== Helper Methods ===========================

    private <T> T parseResponse(String json, TypeReference<T> typeReference) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.has("code") && root.has("data")) {
                int code = root.get("code").asInt();
                if (code != 0) {
                     throw new RuntimeException("Binance Mining API Error: " + root.get("msg").asText());
                }
                return objectMapper.readValue(root.get("data").traverse(), typeReference);
            }
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse mining response", e);
        }
    }

    private <T> T parseResponseExtractField(String json, String fieldName, TypeReference<T> typeReference) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.has("code") && root.has("data")) {
                int code = root.get("code").asInt();
                if (code != 0) {
                    throw new RuntimeException("Binance Mining API Error: " + root.get("msg").asText());
                }
                JsonNode data = root.get("data");
                if (data.has(fieldName)) {
                    return objectMapper.readValue(data.get(fieldName).traverse(), typeReference);
                }
                // If data itself is the list (unlikely if fieldName provided, but fallback)
                return objectMapper.readValue(data.traverse(), typeReference);
            }
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse mining response", e);
        }
    }

    private String executeRequest(String path, Map<String, Object> parameters, String method, boolean signed, boolean withApiKey) {
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
        if (withApiKey && apiKey != null) {
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
            // Note: Mining API returns 200 even for logical errors (code != 0), so we check code in parseResponse
            if (!response.isSuccessful()) {
                 throw new RuntimeException("Failed to execute request: " + response.code() + " " + json);
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
