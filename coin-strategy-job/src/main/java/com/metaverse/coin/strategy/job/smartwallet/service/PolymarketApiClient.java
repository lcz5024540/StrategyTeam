package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketApiClient {

    private final SmartWalletProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
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

    public MarketMetadata fetchMarketMetadata(String conditionId) {
        if (!StringUtils.hasText(conditionId)) {
            throw new IllegalArgumentException("conditionId is required");
        }
        JsonNode body = getJson(buildUri(properties.getPolymarket().getGammaApiBaseUrl(),
                "/markets", Map.of("condition_ids", conditionId)));
        JsonNode market = extractArray(body).isEmpty() ? null : extractArray(body).get(0);
        if (market == null || market.isNull()) {
            throw new IllegalStateException("Polymarket market metadata not found for conditionId=" + conditionId);
        }

        List<String> outcomes = parseStringList(market.get("outcomes"));
        List<String> clobTokenIds = parseStringList(firstNonNull(market.get("clobTokenIds"), market.get("clobToken_ids")));
        Map<String, String> outcomeTokenMap = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(outcomes.size(), clobTokenIds.size()); i++) {
            String outcome = normalizeOutcomeLabel(outcomes.get(i));
            String tokenId = clobTokenIds.get(i);
            if (StringUtils.hasText(outcome) && StringUtils.hasText(tokenId)) {
                outcomeTokenMap.put(outcome, tokenId.trim());
            }
        }
        String resolvedOutcome = null;
        JsonNode tokensNode = market.get("tokens");
        if (tokensNode != null && tokensNode.isArray()) {
            for (JsonNode token : tokensNode) {
                String outcome = normalizeOutcomeLabel(text(token, "outcome"));
                String tokenId = text(token, "token_id", "tokenId", "clobTokenId");
                if (StringUtils.hasText(outcome) && StringUtils.hasText(tokenId) && !outcomeTokenMap.containsKey(outcome)) {
                    outcomeTokenMap.put(outcome, tokenId.trim());
                }
                if (resolvedOutcome == null && bool(token, "winner", "winning")) {
                    resolvedOutcome = outcome;
                }
            }
        }
        if (resolvedOutcome == null) {
            String explicitWinner = normalizeOutcomeLabel(text(market, "winner", "winningOutcome", "resolutionOutcome"));
            if (StringUtils.hasText(explicitWinner)) {
                resolvedOutcome = explicitWinner;
            }
        }

        return MarketMetadata.builder()
                .conditionId(text(market, "conditionId", "condition_id"))
                .marketId(text(market, "id", "marketId", "market_id"))
                .question(text(market, "question", "title"))
                .category(text(market, "category", "groupItemTitle"))
                .minimumTickSize(num(market, "minimum_tick_size", "orderPriceMinTickSize", "tick_size"))
                .negRisk(bool(market, "negRisk", "neg_risk"))
                .active(bool(market, "active"))
                .closed(bool(market, "closed", "isClosed"))
                .acceptingOrders(bool(market, "acceptingOrders", "accepting_orders"))
                .resolvedOutcome(resolvedOutcome)
                .endAt(epochMillis(market, "endDateISO", "endDate", "gameStartTime", "eventStartDate"))
                .outcomeTokenIds(outcomeTokenMap)
                .build();
    }

    private JsonNode getJson(URI uri) {
        Request request = new Request.Builder()
                .url(uri.toString())
                .header("Accept", "application/json")
                .header("User-Agent", "CoinStrategy/1.0")
                .get()
                .build();
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("Polymarket API request failed: " + uri + ", status=" + response.code());
                }
                if (response.body() == null) {
                    throw new IllegalStateException("Polymarket API response body is empty: " + uri);
                }
                return objectMapper.readTree(response.body().string());
            } catch (IOException e) {
                lastFailure = new IllegalStateException("Failed to request Polymarket API: " + uri, e);
                if (attempt >= 3) {
                    break;
                }
                log.warn("Polymarket API request attempt {} failed for {}", attempt, uri);
                sleepQuietly(attempt * 300L);
            }
        }
        JsonNode curlFallback = getJsonViaCurl(uri);
        if (curlFallback != null) {
            log.info("Polymarket API curl fallback succeeded for {}", uri);
            return curlFallback;
        }
        throw lastFailure == null ? new IllegalStateException("Failed to request Polymarket API: " + uri) : lastFailure;
    }

    private JsonNode getJsonViaCurl(URI uri) {
        try {
            Process process = new ProcessBuilder(
                    "curl",
                    "-s",
                    "--http1.1",
                    "-H",
                    "Accept: application/json",
                    "-H",
                    "User-Agent: CoinStrategy/1.0",
                    uri.toString()
            ).start();
            byte[] stdout = readAll(process.getInputStream());
            byte[] stderr = readAll(process.getErrorStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Polymarket API curl fallback failed for {} with exitCode={} stderr={}",
                        uri, exitCode, new String(stderr, StandardCharsets.UTF_8));
                return null;
            }
            String body = new String(stdout, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(body)) {
                return null;
            }
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("Polymarket API curl fallback errored for {}", uri, e);
            return null;
        }
    }

    private byte[] readAll(InputStream inputStream) throws IOException {
        return inputStream == null ? new byte[0] : inputStream.readAllBytes();
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private JsonNode firstNonNull(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && !candidate.isNull()) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> parseStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }
        try {
            if (node.isArray()) {
                List<String> values = new ArrayList<>();
                for (JsonNode element : node) {
                    if (element != null && !element.isNull()) {
                        values.add(element.asText());
                    }
                }
                return values;
            }
            if (node.isTextual()) {
                String raw = node.asText();
                if (!StringUtils.hasText(raw)) {
                    return Collections.emptyList();
                }
                JsonNode parsed = objectMapper.readTree(raw);
                if (parsed.isArray()) {
                    List<String> values = new ArrayList<>();
                    for (JsonNode element : parsed) {
                        if (element != null && !element.isNull()) {
                            values.add(element.asText());
                        }
                    }
                    return values;
                }
            }
        } catch (IOException e) {
            log.debug("Failed to parse Polymarket list field {}", node, e);
        }
        return Collections.emptyList();
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

    private boolean bool(JsonNode node, String... fields) {
        String text = text(node, fields);
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return "true".equalsIgnoreCase(text) || "1".equals(text);
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

    private String normalizeOutcomeLabel(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.contains("NO")) {
            return "NO";
        }
        if (normalized.contains("YES")) {
            return "YES";
        }
        return normalized;
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

    @Data
    @Builder
    public static class MarketMetadata {
        private String conditionId;
        private String marketId;
        private String question;
        private String category;
        private BigDecimal minimumTickSize;
        private boolean negRisk;
        private boolean active;
        private boolean closed;
        private boolean acceptingOrders;
        private String resolvedOutcome;
        private Long endAt;
        @Builder.Default
        private Map<String, String> outcomeTokenIds = new LinkedHashMap<>();

        public boolean isResolved() {
            return StringUtils.hasText(resolvedOutcome) || (closed && !acceptingOrders);
        }
    }
}
