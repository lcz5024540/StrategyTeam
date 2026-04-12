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
import java.util.concurrent.TimeUnit;

/**
 * 基于 Polygonscan API 的入金来源聚类器
 * 通过分析钱包的第一笔入金来源地址，识别同一实体控制的多钱包
 *
 * Phase 1 简化策略：
 * - 仅使用入金来源（最高置信度聚类依据）
 * - 无 API Key 时优雅降级，返回 empty（视为独立实体）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingSourceClusterer {

    private static final String POLYGONSCAN_API_BASE = "https://api.polygonscan.com/api";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 15;

    private final ObjectMapper objectMapper;

    /**
     * 查询钱包的第一笔入金来源地址
     *
     * @param walletAddress 目标钱包地址（小写）
     * @param apiKey        Polygonscan API Key（为空时跳过）
     * @return 入金来源地址（小写），无数据时返回 empty
     */
    public Optional<String> findFundingSource(String walletAddress, String apiKey) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(walletAddress)) {
            return Optional.empty();
        }
        try {
            return queryFirstInboundTxSender(walletAddress.toLowerCase(), apiKey);
        } catch (Exception e) {
            log.warn("[FundingSourceClusterer] Failed to query Polygonscan for wallet={}, error={}",
                    walletAddress, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> queryFirstInboundTxSender(String walletAddress, String apiKey) throws Exception {
        String url = POLYGONSCAN_API_BASE
                + "?module=account&action=txlist"
                + "&address=" + walletAddress
                + "&startblock=0&endblock=99999999"
                + "&sort=asc&page=1&offset=5"
                + "&apikey=" + apiKey;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
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
}
