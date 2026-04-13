package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Polygonscan API 的入金来源聚类器
 * 通过分析钱包的第一笔入金来源地址，识别同一实体控制的多钱包
 *
 * 优化：
 * - 共享 OkHttpClient（连接池复用，避免每次调用创建新客户端）
 * - 查询结果 24h 本地缓存（同一地址只查一次 Polygonscan）
 * - 最多 3 次重试 + 指数退避（提升外部 API 成功率）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingSourceClusterer {

    private static final String POLYGONSCAN_API_BASE = "https://api.polygonscan.com/api";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 15;
    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1_000L, 2_000L, 4_000L};
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1_000; // 24 hours

    /** 空结果哨兵：表示"已查询、但未找到入金来源" */
    private static final String NO_SOURCE_SENTINEL = "__NONE__";

    /** 共享 HTTP 客户端（Spring 单例，连接池只初始化一次） */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    /** 入金来源查询结果缓存 <walletAddress, CacheEntry> */
    private final ConcurrentHashMap<String, CacheEntry> fundingCache = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    /**
     * 查询钱包的第一笔入金来源地址（带缓存 + 重试）
     *
     * @param walletAddress 目标钱包地址（小写）
     * @param apiKey        Polygonscan API Key（为空时跳过）
     * @return 入金来源地址（小写），无数据时返回 empty
     */
    public Optional<String> findFundingSource(String walletAddress, String apiKey) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(walletAddress)) {
            return Optional.empty();
        }
        String normalized = walletAddress.toLowerCase();

        // 命中缓存
        CacheEntry cached = fundingCache.get(normalized);
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMs < CACHE_TTL_MS) {
            return NO_SOURCE_SENTINEL.equals(cached.value) ? Optional.empty() : Optional.of(cached.value);
        }

        Optional<String> result = queryWithRetry(normalized, apiKey);

        // 无论找到与否都缓存（避免重复请求不存在的来源）
        fundingCache.put(normalized, new CacheEntry(result.orElse(NO_SOURCE_SENTINEL), System.currentTimeMillis()));
        return result;
    }

    /** 清除指定地址的缓存（供测试或手动触发使用） */
    public void evictCache(String walletAddress) {
        if (StringUtils.hasText(walletAddress)) {
            fundingCache.remove(walletAddress.toLowerCase());
        }
    }

    private Optional<String> queryWithRetry(String walletAddress, String apiKey) {
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
            try {
                Optional<String> result = queryFirstInboundTxSender(walletAddress, apiKey);
                if (attempt > 0) {
                    log.info("[FundingSourceClusterer] Succeeded on attempt {} for wallet={}", attempt + 1, walletAddress);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                log.warn("[FundingSourceClusterer] Attempt {}/{} failed for wallet={}: {}",
                        attempt + 1, MAX_RETRIES, walletAddress, e.getMessage());
            }
        }
        log.warn("[FundingSourceClusterer] All {} attempts exhausted for wallet={}: {}",
                MAX_RETRIES, walletAddress, lastException != null ? lastException.getMessage() : "unknown");
        return Optional.empty();
    }

    private Optional<String> queryFirstInboundTxSender(String walletAddress, String apiKey) throws Exception {
        String url = POLYGONSCAN_API_BASE
                + "?module=account&action=txlist"
                + "&address=" + walletAddress
                + "&startblock=0&endblock=99999999"
                + "&sort=asc&page=1&offset=5"
                + "&apikey=" + apiKey;

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                if (response.code() >= 500) {
                    throw new RuntimeException("Polygonscan server error: HTTP " + response.code());
                }
                return Optional.empty();
            }
            String body = response.body().string();
            PolygonscanTxListResponse txList = objectMapper.readValue(body, PolygonscanTxListResponse.class);

            if (!"1".equals(txList.getStatus()) || txList.getResult() == null || txList.getResult().isEmpty()) {
                return Optional.empty();
            }

            // 找到第一笔 TO 是本钱包的入金交易（来源是外部 EOA，不是合约）
            return txList.getResult().stream()
                    .filter(tx -> "0".equals(tx.getIsError()))
                    .filter(tx -> walletAddress.equalsIgnoreCase(tx.getTo()))
                    .filter(tx -> StringUtils.hasText(tx.getFrom()))
                    .filter(tx -> !walletAddress.equalsIgnoreCase(tx.getFrom()))  // 排除自转
                    .map(tx -> tx.getFrom().toLowerCase())
                    .findFirst();
        }
    }

    // ---- Polygonscan API 响应模型 ----

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PolygonscanTxListResponse {
        @JsonProperty("status")
        private String status;
        @JsonProperty("message")
        private String message;
        @JsonProperty("result")
        private List<PolygonscanTx> result;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PolygonscanTx {
        @JsonProperty("blockNumber")
        private String blockNumber;
        @JsonProperty("from")
        private String from;
        @JsonProperty("to")
        private String to;
        @JsonProperty("value")
        private String value;
        @JsonProperty("timeStamp")
        private String timeStamp;
        @JsonProperty("isError")
        private String isError;
    }

    private static class CacheEntry {
        final String value;
        final long cachedAtMs;
        CacheEntry(String value, long cachedAtMs) {
            this.value = value;
            this.cachedAtMs = cachedAtMs;
        }
    }
}
