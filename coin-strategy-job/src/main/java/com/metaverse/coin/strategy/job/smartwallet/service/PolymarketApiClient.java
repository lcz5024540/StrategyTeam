package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketApiClient {

    private final SmartWalletProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public List<LeaderboardEntry> fetchLeaderboard() {
        SmartWalletProperties.PolymarketMonitor monitor = properties.getPolymarket();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("category", monitor.getLeaderboardCategory());
        params.put("timePeriod", monitor.getLeaderboardTimePeriod());
        params.put("sortBy", monitor.getLeaderboardOrderBy());
        params.put("limit", String.valueOf(monitor.getLeaderboardLimit()));

        params.put("offset", "0");
        JsonNode body = getJson(buildUri(monitor.getDataApiBaseUrl(), "/v1/leaderboard", params));
        List<LeaderboardEntry> result = new ArrayList<>();
        for (JsonNode node : iterableArray(extractArray(body))) {
            String walletAddress = text(node, "proxyWallet", "walletAddress", "wallet", "address");
            if (!StringUtils.hasText(walletAddress)) {
                continue;
            }
            result.add(LeaderboardEntry.builder()
                    .walletAddress(walletAddress)
                    .alias(text(node, "name", "username", "ens"))
                    .volume(num(node, "volume", "volumeTraded"))
                    .pnl(num(node, "pnl", "profit"))
                    .build());
        }
        return result;
    }

    public WalletProfile fetchProfile(String walletAddress) {
        JsonNode body = getJson(buildUri(properties.getPolymarket().getGammaApiBaseUrl(),
                "/public-profile", Map.of("address", walletAddress)));
        JsonNode node = body.isArray() && body.size() > 0 ? body.get(0) : body;
        return WalletProfile.builder()
                .walletAddress(walletAddress)
                .alias(text(node, "name", "username", "displayName", "pseudonym"))
                .build();
    }

    public List<ClosedPosition> fetchClosedPositions(String walletAddress) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("user", walletAddress);
        params.put("limit", String.valueOf(properties.getPolymarket().getClosedPositionLimit()));
        params.put("offset", "0");

        JsonNode body = getJson(buildUri(properties.getPolymarket().getDataApiBaseUrl(),
                "/closed-positions", params));
        List<ClosedPosition> result = new ArrayList<>();
        for (JsonNode node : iterableArray(extractArray(body))) {
            result.add(ClosedPosition.builder()
                    .marketId(text(node, "conditionId", "market", "marketId"))
                    .question(text(node, "question", "title"))
                    .outcome(text(node, "outcome", "outcomeName"))
                    .sizeBought(num(node, "sizeBought", "totalBought", "amount"))
                    .realizedPnl(num(node, "realizedPnl", "pnl", "profit"))
                    .cashPnl(num(node, "cashPnl"))
                    .avgPrice(num(node, "avgPrice", "averagePrice"))
                    .closedAt(epochMillis(node, "closedAt", "redeemedAt", "endDate"))
                    .build());
        }
        return result;
    }

    public List<UserTrade> fetchUserTrades(String walletAddress) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("user", walletAddress);
        params.put("limit", String.valueOf(properties.getPolymarket().getRecentTradeLimit()));
        params.put("offset", "0");
        params.put("takerOnly", "true");

        JsonNode body = getJson(buildUri(properties.getPolymarket().getDataApiBaseUrl(),
                "/trades", params));
        List<UserTrade> result = new ArrayList<>();
        for (JsonNode node : iterableArray(extractArray(body))) {
            String status = text(node, "status");
            if (StringUtils.hasText(status) && !"CONFIRMED".equalsIgnoreCase(status)) {
                continue;
            }
            result.add(UserTrade.builder()
                    .tradeId(text(node, "id", "transactionHash"))
                    .walletAddress(walletAddress)
                    .marketId(text(node, "conditionId", "market", "marketId"))
                    .question(text(node, "question", "title"))
                    .outcome(normalizeOutcome(text(node, "outcome", "outcomeName", "side")))
                    .side(normalizeSide(text(node, "side", "type")))
                    .price(num(node, "price"))
                    .size(num(node, "size", "sizeUsd", "amount"))
                    .tradedAt(epochMillis(node, "timestamp", "createdAt", "time"))
                    .build());
        }
        return result;
    }

    private JsonNode getJson(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Polymarket API request failed: " + uri + ", status=" + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to request Polymarket API: " + uri, e);
        }
    }

    private URI buildUri(String baseUrl, String path, Map<String, String> params) {
        StringBuilder builder = new StringBuilder(baseUrl);
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            builder.setLength(builder.length() - 1);
        }
        builder.append(path);
        if (!params.isEmpty()) {
            builder.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!StringUtils.hasText(entry.getValue())) {
                    continue;
                }
                if (!first) {
                    builder.append("&");
                }
                builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                builder.append("=");
                builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }
        return URI.create(builder.toString());
    }

    private JsonNode extractArray(JsonNode body) {
        if (body == null || body.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (body.isArray()) {
            return body;
        }
        for (String field : List.of("data", "items", "results")) {
            JsonNode candidate = body.get(field);
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }
        return objectMapper.createArrayNode();
    }

    private Iterable<JsonNode> iterableArray(JsonNode node) {
        return () -> node == null ? List.<JsonNode>of().iterator() : node.elements();
    }

    private String text(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private BigDecimal num(JsonNode node, String... fields) {
        String text = text(node, fields);
        if (!StringUtils.hasText(text)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            log.debug("Failed to parse Polymarket numeric field {}", text, e);
            return BigDecimal.ZERO;
        }
    }

    private Long epochMillis(JsonNode node, String... fields) {
        String text = text(node, fields);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            if (text.matches("\\d+")) {
                long value = Long.parseLong(text);
                return text.length() <= 10 ? value * 1000 : value;
            }
            return java.time.Instant.parse(text).toEpochMilli();
        } catch (Exception e) {
            log.debug("Failed to parse Polymarket time field {}", text, e);
            return null;
        }
    }

    private String normalizeOutcome(String value) {
        if (!StringUtils.hasText(value)) {
            return "YES";
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.contains("NO")) {
            return "NO";
        }
        return "YES";
    }

    private String normalizeSide(String value) {
        if (!StringUtils.hasText(value)) {
            return "BUY";
        }
        String normalized = value.trim().toUpperCase();
        return normalized.contains("SELL") ? "SELL" : "BUY";
    }

    @Data
    @Builder
    public static class LeaderboardEntry {
        private String walletAddress;
        private String alias;
        private BigDecimal pnl;
        private BigDecimal volume;
    }

    @Data
    @Builder
    public static class WalletProfile {
        private String walletAddress;
        private String alias;
    }

    @Data
    @Builder
    public static class ClosedPosition {
        private String marketId;
        private String question;
        private String outcome;
        private BigDecimal sizeBought;
        private BigDecimal realizedPnl;
        private BigDecimal cashPnl;
        private BigDecimal avgPrice;
        private Long closedAt;
    }

    @Data
    @Builder
    public static class UserTrade {
        private String tradeId;
        private String walletAddress;
        private String marketId;
        private String question;
        private String outcome;
        private String side;
        private BigDecimal price;
        private BigDecimal size;
        private Long tradedAt;
    }
}
