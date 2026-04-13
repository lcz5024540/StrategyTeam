package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private final ConnectionPool connectionPool = new ConnectionPool(6, 5, TimeUnit.MINUTES);
    private final Dispatcher dispatcher = createDispatcher();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final OkHttpClient fastFallbackClient = httpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
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

    public BigDecimal fetchBalance(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return BigDecimal.ZERO;
        }
        JsonNode body = getJson(buildUri(properties.getPolymarket().getDataApiBaseUrl(),
                "/balance", Map.of("user", walletAddress)));
        if (body != null && body.has("balance")) {
            try {
                return new BigDecimal(body.get("balance").asText());
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 获取某市场全量持仓分布（不传 user），用于集中度分析。
     * 调用 Data API /positions?market={conditionId}&sizeThreshold=1&limit=N
     * 返回按 size 降序排列的持仓列表（Polymarket 默认排序）。
     */
    public List<MarketPositionEntry> fetchMarketTopPositions(String conditionId, int limit) {
        if (!StringUtils.hasText(conditionId)) {
            return List.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", conditionId.trim());
        params.put("sizeThreshold", "1");
        params.put("limit", String.valueOf(Math.max(1, Math.min(limit, 100))));

        JsonNode body = getJson(buildUri(properties.getPolymarket().getDataApiBaseUrl(),
                "/positions", params));
        List<MarketPositionEntry> result = new ArrayList<>();
        for (JsonNode node : iterableArray(extractArray(body))) {
            String walletAddress = text(node, "proxyWallet", "user", "walletAddress", "address");
            if (!StringUtils.hasText(walletAddress)) {
                continue;
            }
            result.add(MarketPositionEntry.builder()
                    .walletAddress(walletAddress)
                    .outcome(normalizeOutcome(text(node, "outcome", "outcomeName")))
                    .size(firstNonZero(num(node, "size"), num(node, "currentTokens")))
                    .avgPrice(num(node, "avgPrice", "averagePrice"))
                    .initialValue(num(node, "initialValue", "cashInvested"))
                    .build());
        }
        return result;
    }

    public List<JsonNode> fetchPositions(String walletAddress, String conditionId) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("user", walletAddress);
        if (StringUtils.hasText(conditionId)) {
            params.put("market", conditionId);
        }
        params.put("sizeThreshold", "0.01");

        JsonNode body = getJson(buildUri(properties.getPolymarket().getDataApiBaseUrl(),
                "/positions", params));
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode node : iterableArray(extractArray(body))) {
            result.add(node);
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
                    .outcome(normalizeOutcome(text(node, "outcome", "outcomeName")))
                    .side(normalizeSide(text(node, "side", "type")))
                    .price(num(node, "price"))
                    .size(num(node, "size", "sizeUsd", "amount"))
                    .tradedAt(epochMillis(node, "timestamp", "createdAt", "time"))
                    .build());
        }
        return result;
    }

    public OrderBook fetchOrderBook(String tokenId) {
        if (!StringUtils.hasText(tokenId)) {
            throw new IllegalArgumentException("tokenId is required");
        }
        JsonNode body = getJson(buildUri(properties.getExecutionWallet().getApiBaseUrl(),
                "/book", Map.of("tokenID", tokenId.trim())));
        List<OrderBookLevel> bids = parseOrderBookLevels(body.get("bids"));
        List<OrderBookLevel> asks = parseOrderBookLevels(body.get("asks"));
        bids.sort(Comparator.comparing(OrderBookLevel::getPrice).reversed());
        asks.sort(Comparator.comparing(OrderBookLevel::getPrice));
        BigDecimal midpoint = firstNonZero(
                num(body, "midpoint", "mid", "mid_price"),
                midpointFromBook(bids, asks)
        );
        BigDecimal spread = firstNonZero(
                num(body, "spread", "spreadBps", "spread_bps"),
                spreadFromBook(bids, asks)
        );
        return OrderBook.builder()
                .tokenId(tokenId.trim())
                .midpoint(midpoint)
                .spread(spread)
                .bids(bids)
                .asks(asks)
                .build();
    }

    public List<MarketTrade> fetchMarketTrades(String conditionId, Integer limit, Long afterEpochSeconds) {
        if (!StringUtils.hasText(conditionId)) {
            return List.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", conditionId.trim());
        params.put("limit", String.valueOf(limit == null ? 50 : Math.max(1, Math.min(limit, 500))));
        if (afterEpochSeconds != null && afterEpochSeconds > 0) {
            params.put("after", String.valueOf(afterEpochSeconds));
        }

        JsonNode body = getJson(buildUri(properties.getExecutionWallet().getApiBaseUrl(), "/trades", params));
        List<MarketTrade> result = new ArrayList<>();
        for (JsonNode node : iterableArray(extractArray(body))) {
            result.add(MarketTrade.builder()
                    .tradeId(text(node, "tradeID", "id"))
                    .marketId(text(node, "market", "conditionId", "marketId"))
                    .price(num(node, "price"))
                    .size(firstNonZero(num(node, "size"), num(node, "amount")))
                    .side(normalizeSide(text(node, "side")))
                    .fee(num(node, "fee"))
                    .makerAddress(text(node, "maker_address", "makerAddress"))
                    .takerAddress(text(node, "taker_address", "takerAddress"))
                    .timestamp(epochMillis(node, "timestamp", "createdAt", "time"))
                    .build());
        }
        result.sort(Comparator.comparing(MarketTrade::getTimestamp, Comparator.nullsLast(Comparator.reverseOrder())));
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
        return parseMarketMetadata(market);
    }

    /**
     * 批量拉取活跃市场列表（用于套利扫描，不需要搜索词）
     * 调用 Gamma API /markets?active=true&closed=false，按成交量降序返回
     */
    public List<MarketMetadata> fetchActiveMarkets(int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("active", "true");
        params.put("closed", "false");
        params.put("order", "volume24hr");
        params.put("ascending", "false");
        params.put("limit", String.valueOf(Math.max(1, Math.min(limit, 500))));
        JsonNode body = getJson(buildUri(properties.getPolymarket().getGammaApiBaseUrl(), "/markets", params));
        List<MarketMetadata> results = new ArrayList<>();
        JsonNode arr = extractArray(body);
        for (JsonNode market : iterableArray(arr)) {
            if (market != null && !market.isNull()) {
                results.add(parseMarketMetadata(market));
            }
        }
        return results;
    }

    public List<MarketMetadata> searchMarkets(String query, int limitPerType, boolean keepClosedMarkets) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query.trim());
        params.put("limit_per_type", String.valueOf(Math.max(limitPerType, 1)));
        params.put("keep_closed_markets", keepClosedMarkets ? "1" : "0");
        JsonNode body = getJson(buildUri(properties.getPolymarket().getGammaApiBaseUrl(), "/public-search", params));
        List<MarketMetadata> results = new ArrayList<>();
        collectSearchMarkets(body.get("markets"), results);
        JsonNode events = body.get("events");
        if (events != null && events.isArray()) {
            for (JsonNode event : events) {
                collectSearchMarkets(event == null ? null : event.get("markets"), results);
            }
        }
        return results;
    }

    private void collectSearchMarkets(JsonNode marketsNode, List<MarketMetadata> results) {
        if (marketsNode == null || !marketsNode.isArray()) {
            return;
        }
        for (JsonNode market : marketsNode) {
            if (market == null || market.isNull()) {
                continue;
            }
            results.add(parseMarketMetadata(market));
        }
    }

    private MarketMetadata parseMarketMetadata(JsonNode market) {
        List<String> outcomes = parseStringList(market.get("outcomes"));
        List<String> outcomePrices = parseStringList(market.get("outcomePrices"));
        List<String> clobTokenIds = parseStringList(firstNonNull(market.get("clobTokenIds"), market.get("clobToken_ids")));
        Map<String, String> outcomeTokenMap = new LinkedHashMap<>();
        boolean closed = bool(market, "closed", "isClosed");
        boolean acceptingOrders = bool(market, "acceptingOrders", "accepting_orders");
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
                .questionId(text(market, "questionID", "questionId", "question_id"))
                .question(text(market, "question", "title"))
                .category(text(market, "category", "groupItemTitle"))
                .minimumTickSize(num(market, "minimum_tick_size", "orderPriceMinTickSize", "tick_size"))
                .negRisk(bool(market, "negRisk", "neg_risk"))
                .active(bool(market, "active"))
                .closed(closed)
                .acceptingOrders(acceptingOrders)
                .umaResolutionStatus(text(market, "umaResolutionStatus", "uma_resolution_status"))
                .resolvedOutcome(resolvedOutcome)
                .endAt(epochMillis(market, "endDateISO", "endDate", "gameStartTime", "eventStartDate"))
                .umaEndAt(epochMillis(market, "umaEndDate", "umaEndDateISO", "uma_end_date"))
                .outcomes(new ArrayList<>(outcomes))
                .outcomePrices(new ArrayList<>(outcomePrices))
                .outcomeTokenIds(outcomeTokenMap)
                .build();
    }

    private JsonNode getJson(URI uri) {
        Request request = buildRequest(uri);
        IllegalStateException lastFailure = null;
        boolean fastFallbackRecommended = false;
        boolean curlFallbackAttempted = false;
        for (int attempt = 1; attempt <= 3; attempt++) {
            long startedAt = System.currentTimeMillis();
            OkHttpClient client = selectClient(uri, attempt, fastFallbackRecommended);
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 429 || response.code() >= 500) {
                        throw new IOException("Polymarket API temporary failure status=" + response.code());
                    }
                    throw new IllegalStateException("Polymarket API request failed: " + uri + ", status=" + response.code());
                }
                if (response.body() == null) {
                    throw new IllegalStateException("Polymarket API response body is empty: " + uri);
                }
                return objectMapper.readTree(response.body().string());
            } catch (IOException e) {
                lastFailure = new IllegalStateException("Failed to request Polymarket API: " + uri, e);
                boolean immediateFallback = isImmediateFallbackCandidate(e, uri);
                fastFallbackRecommended = fastFallbackRecommended || immediateFallback;
                if (attempt >= 3) {
                    break;
                }
                log.warn("Polymarket API request attempt {} failed for {} after {} ms: {}",
                        attempt, uri, System.currentTimeMillis() - startedAt, rootMessage(e));
                if (immediateFallback) {
                    if (!curlFallbackAttempted) {
                        curlFallbackAttempted = true;
                        JsonNode curlFallback = getJsonViaCurl(uri);
                        if (curlFallback != null) {
                            log.info("Polymarket API curl fallback succeeded for {}", uri);
                            return curlFallback;
                        }
                    }
                    break;
                }
                if (fastFallbackRecommended && attempt >= 2) {
                    break;
                }
                sleepQuietly(attempt * 300L);
            }
        }
        if (!curlFallbackAttempted) {
            JsonNode curlFallback = getJsonViaCurl(uri);
            if (curlFallback != null) {
                log.info("Polymarket API curl fallback succeeded for {}", uri);
                return curlFallback;
            }
        }
        throw lastFailure == null ? new IllegalStateException("Failed to request Polymarket API: " + uri) : lastFailure;
    }

    private JsonNode getJsonViaCurl(URI uri) {
        try {
            Process process = new ProcessBuilder(
                    "curl",
                    "-s",
                    "--http1.1",
                    "--connect-timeout",
                    "4",
                    "--max-time",
                    "12",
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

    private Request buildRequest(URI uri) {
        return new Request.Builder()
                .url(uri.toString())
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Connection", "keep-alive")
                .header("User-Agent", "CoinStrategy/1.0")
                .get()
                .build();
    }

    private static Dispatcher createDispatcher() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(8);
        dispatcher.setMaxRequestsPerHost(4);
        return dispatcher;
    }

    private OkHttpClient selectClient(URI uri, int attempt, boolean fastFallbackRecommended) {
        if (fastFallbackRecommended || isSlowDataEndpoint(uri)) {
            return attempt == 1 ? fastFallbackClient : httpClient;
        }
        return httpClient;
    }

    private boolean isSlowDataEndpoint(URI uri) {
        String value = uri == null ? "" : uri.toString();
        return value.contains("/v1/leaderboard")
                || value.contains("/closed-positions")
                || value.contains("/public-profile");
    }

    private boolean isImmediateFallbackCandidate(IOException e, URI uri) {
        String message = rootMessage(e).toLowerCase();
        return message.contains("ssl peer shut down incorrectly")
                || message.contains("remote host terminated the handshake")
                || message.contains("sslhandshakeexception")
                || message.contains("read timed out")
                || e instanceof SocketTimeoutException
                || isSlowDataEndpoint(uri);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        Throwable last = throwable;
        while (current != null) {
            last = current;
            current = current.getCause();
        }
        return last == null || last.getMessage() == null ? "unknown error" : last.getMessage();
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

    private List<OrderBookLevel> parseOrderBookLevels(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return Collections.emptyList();
        }
        List<OrderBookLevel> levels = new ArrayList<>();
        for (JsonNode level : node) {
            if (level == null || level.isNull()) {
                continue;
            }
            BigDecimal price = num(level, "price");
            BigDecimal size = firstNonZero(num(level, "size"), num(level, "amount"));
            levels.add(OrderBookLevel.builder()
                    .price(price)
                    .size(size)
                    .build());
        }
        return levels;
    }

    private BigDecimal midpointFromBook(List<OrderBookLevel> bids, List<OrderBookLevel> asks) {
        if (bids.isEmpty() || asks.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return bids.get(0).getPrice().add(asks.get(0).getPrice()).divide(new BigDecimal("2"), 8, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal spreadFromBook(List<OrderBookLevel> bids, List<OrderBookLevel> asks) {
        if (bids.isEmpty() || asks.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal mid = midpointFromBook(bids, asks);
        if (mid.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return asks.get(0).getPrice()
                .subtract(bids.get(0).getPrice())
                .divide(mid, 8, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("10000"));
    }

    private BigDecimal firstNonZero(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.compareTo(BigDecimal.ZERO) != 0) {
                return value;
            }
        }
        return BigDecimal.ZERO;
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
        return normalizeOutcomeLabel(value);
    }

    private String normalizeOutcomeLabel(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if ("NO".equals(normalized) || normalized.startsWith("NO ")) {
            return "NO";
        }
        if ("YES".equals(normalized) || normalized.startsWith("YES ")) {
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
    @Builder(toBuilder = true)
    public static class MarketMetadata {
        private String conditionId;
        private String marketId;
        private String questionId;
        private String question;
        private String category;
        private BigDecimal minimumTickSize;
        private boolean negRisk;
        private boolean active;
        private boolean closed;
        private boolean acceptingOrders;
        private String umaResolutionStatus;
        private String resolvedOutcome;
        private Long endAt;
        private Long umaEndAt;
        private String resolutionSource;
        private Long resolutionObservedAt;
        @Builder.Default
        private List<String> outcomes = new ArrayList<>();
        @Builder.Default
        private List<String> outcomePrices = new ArrayList<>();
        @Builder.Default
        private Map<String, String> outcomeTokenIds = new LinkedHashMap<>();

        public boolean isResolved() {
            return StringUtils.hasText(resolvedOutcome);
        }
    }

    @Data
    @Builder
    public static class OrderBookLevel {
        private BigDecimal price;
        private BigDecimal size;
    }

    @Data
    @Builder
    public static class OrderBook {
        private String tokenId;
        private BigDecimal midpoint;
        private BigDecimal spread;
        @Builder.Default
        private List<OrderBookLevel> bids = new ArrayList<>();
        @Builder.Default
        private List<OrderBookLevel> asks = new ArrayList<>();
    }

    @Data
    @Builder
    public static class MarketTrade {
        private String tradeId;
        private String marketId;
        private BigDecimal price;
        private BigDecimal size;
        private String side;
        private BigDecimal fee;
        private String makerAddress;
        private String takerAddress;
        private Long timestamp;
    }

    /** 市场持仓条目，用于集中度分析（fetchMarketTopPositions 返回） */
    @Data
    @Builder
    public static class MarketPositionEntry {
        /** 持仓钱包地址 */
        private String walletAddress;
        /** 持仓方向：YES / NO */
        private String outcome;
        /** 持仓 token 数量 */
        private BigDecimal size;
        /** 平均成本价 */
        private BigDecimal avgPrice;
        /** 初始 USDC 投入 */
        private BigDecimal initialValue;
    }
}
