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
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketApiClient {

    private static final int DEFAULT_FULL_HISTORY_PAGE_SIZE = 200;
    private static final int MAX_FULL_HISTORY_PAGES = 20;
    public static final int MAX_USER_TRADE_HISTORY_OFFSET = 3_000;
    private static final String METADATA_ENDPOINT_COOLDOWN_KEY = "metadata-endpoint:markets-condition-ids";

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
    private final Map<String, Long> externalFailureCooldownUntilMs = new ConcurrentHashMap<>();
    private final AtomicInteger metadataEndpointConsecutiveFailures = new AtomicInteger();
    private volatile int adaptiveGammaChunkSize = -1;

    public List<LeaderboardEntry> fetchLeaderboard() {
        SmartWalletProperties.PolymarketMonitor monitor = properties.getPolymarket();
        SmartWalletProperties.PolymarketMonitor.LeaderboardQuery query = new SmartWalletProperties.PolymarketMonitor.LeaderboardQuery();
        query.setCategory(monitor.getLeaderboardCategory());
        query.setTimePeriod(monitor.getLeaderboardTimePeriod());
        query.setOrderBy(monitor.getLeaderboardOrderBy());
        query.setLimit(monitor.getLeaderboardLimit());
        return fetchLeaderboard(query);
    }

    public List<LeaderboardEntry> fetchLeaderboard(SmartWalletProperties.PolymarketMonitor.LeaderboardQuery query) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("category", query.getCategory());
        params.put("timePeriod", query.getTimePeriod());
        params.put("sortBy", query.getOrderBy());
        params.put("limit", String.valueOf(query.getLimit()));

        params.put("offset", "0");
        JsonNode body = getJson(buildUri(properties.getPolymarket().getDataApiBaseUrl(), "/v1/leaderboard", params));
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
        return fetchClosedPositions(walletAddress,
                properties.getPolymarket().getClosedPositionLimit(),
                0);
    }

    public List<ClosedPosition> fetchClosedPositions(String walletAddress, Integer limit, int offset) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("user", walletAddress);
        params.put("limit", String.valueOf(limit == null
                ? properties.getPolymarket().getClosedPositionLimit()
                : Math.max(1, limit)));
        params.put("offset", String.valueOf(Math.max(0, offset)));

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
                    .openedAt(epochMillis(node, "openedAt", "createdAt", "startDate"))
                    .closedAt(epochMillis(node, "closedAt", "redeemedAt", "endDate"))
                    .build());
        }
        return result;
    }

    public List<ClosedPosition> fetchAllClosedPositions(String walletAddress) {
        return fetchAllClosedPositions(walletAddress, DEFAULT_FULL_HISTORY_PAGE_SIZE);
    }

    public List<ClosedPosition> fetchAllClosedPositions(String walletAddress, int pageSize) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        int safePageSize = Math.max(1, Math.min(pageSize, 500));
        List<ClosedPosition> result = new ArrayList<>();
        Map<String, ClosedPosition> deduped = new LinkedHashMap<>();
        for (int page = 0; page < MAX_FULL_HISTORY_PAGES; page++) {
            int offset = page * safePageSize;
            List<ClosedPosition> chunk = fetchClosedPositions(walletAddress, safePageSize, offset);
            if (chunk.isEmpty()) {
                break;
            }
            for (ClosedPosition position : chunk) {
                if (position == null) {
                    continue;
                }
                deduped.put(closedPositionKey(position), position);
            }
            if (chunk.size() < safePageSize) {
                break;
            }
        }
        result.addAll(deduped.values());
        result.sort(Comparator.comparing(ClosedPosition::getClosedAt, Comparator.nullsLast(Comparator.reverseOrder())));
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
     * 获取某市场的持仓分布，用于集中度分析。
     * 调用 Data API /v1/market-positions?market={conditionId}&status=OPEN&limit=N
     * 返回结果按 outcome token 分组，这里会打平后再按 size 全局降序排列。
     */
    public List<MarketPositionEntry> fetchMarketTopPositions(String conditionId, int limit) {
        if (!StringUtils.hasText(conditionId)) {
            return List.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", conditionId.trim());
        params.put("status", "OPEN");
        params.put("sortBy", "TOKENS");
        params.put("sortDirection", "DESC");
        params.put("limit", String.valueOf(Math.max(1, Math.min(limit, 500))));

        JsonNode body = getJson(buildUri(properties.getPolymarket().getDataApiBaseUrl(),
                "/v1/market-positions", params));
        return parseMarketTopPositions(body);
    }

    private List<MarketPositionEntry> parseMarketTopPositions(JsonNode body) {
        List<MarketPositionEntry> result = new ArrayList<>();
        for (JsonNode group : iterableArray(extractArray(body))) {
            JsonNode positions = group == null ? null : group.get("positions");
            for (JsonNode node : iterableArray(positions)) {
                String walletAddress = text(node, "proxyWallet", "user", "walletAddress", "address");
                if (!StringUtils.hasText(walletAddress)) {
                    continue;
                }
                result.add(MarketPositionEntry.builder()
                        .walletAddress(walletAddress)
                        .outcome(normalizeOutcome(text(node, "outcome", "outcomeName", "asset")))
                        .size(firstNonZero(num(node, "size"), num(node, "currentTokens")))
                        .avgPrice(num(node, "avgPrice", "averagePrice"))
                        .initialValue(firstNonZero(
                                num(node, "initialValue"),
                                num(node, "totalBought"),
                                num(node, "cashInvested")))
                        .build());
            }
        }
        result.sort(Comparator.comparing(MarketPositionEntry::getSize, Comparator.nullsLast(Comparator.reverseOrder())));
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

    public List<ActivePosition> fetchActivePositions(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        List<ActivePosition> result = new ArrayList<>();
        for (JsonNode node : fetchPositions(walletAddress, null)) {
            ActivePosition position = parseActivePosition(walletAddress, node);
            if (position == null) {
                continue;
            }
            if (safe(position.getSize()).compareTo(BigDecimal.ZERO) <= 0
                    && safe(position.getCurrentValueUsdc()).compareTo(BigDecimal.ZERO) <= 0
                    && safe(position.getInvestedUsdc()).compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            result.add(position);
        }
        result.sort(Comparator.comparing(ActivePosition::getOpenedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    public List<UserTrade> fetchUserTrades(String walletAddress) {
        return fetchUserTrades(walletAddress, properties.getPolymarket().getRecentTradeLimit());
    }

    public List<UserTrade> fetchUserTrades(String walletAddress, Integer limit) {
        return fetchUserTrades(walletAddress, limit, 0);
    }

    public List<UserTrade> fetchUserTrades(String walletAddress, Integer limit, int offset) {
        int safeOffset = Math.max(0, offset);
        if (safeOffset > MAX_USER_TRADE_HISTORY_OFFSET) {
            log.debug("Skip Polymarket user trades fetch because offset exceeds supported history window: wallet={} offset={} maxOffset={}",
                    walletAddress, safeOffset, MAX_USER_TRADE_HISTORY_OFFSET);
            return List.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("user", walletAddress);
        params.put("limit", String.valueOf(limit == null ? properties.getPolymarket().getRecentTradeLimit() : limit));
        params.put("offset", String.valueOf(safeOffset));
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

    public List<UserTrade> fetchAllUserTrades(String walletAddress) {
        return fetchAllUserTrades(walletAddress, DEFAULT_FULL_HISTORY_PAGE_SIZE);
    }

    public List<UserTrade> fetchAllUserTrades(String walletAddress, int pageSize) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        int safePageSize = Math.max(1, Math.min(pageSize, 500));
        Map<String, UserTrade> deduped = new LinkedHashMap<>();
        for (int page = 0; page < MAX_FULL_HISTORY_PAGES; page++) {
            int offset = page * safePageSize;
            if (offset > MAX_USER_TRADE_HISTORY_OFFSET) {
                break;
            }
            List<UserTrade> chunk = fetchUserTrades(walletAddress, safePageSize, offset);
            if (chunk.isEmpty()) {
                break;
            }
            for (UserTrade trade : chunk) {
                if (trade == null) {
                    continue;
                }
                deduped.put(userTradeKey(trade), trade);
            }
            if (chunk.size() < safePageSize) {
                break;
            }
        }
        List<UserTrade> result = new ArrayList<>(deduped.values());
        result.sort(Comparator.comparing(UserTrade::getTradedAt, Comparator.nullsLast(Comparator.naturalOrder())));
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
        Map<String, MarketMetadata> metadataByConditionId = fetchMarketMetadataBatch(List.of(conditionId.trim()), 1);
        MarketMetadata metadata = metadataByConditionId.get(conditionId.trim());
        if (metadata == null) {
            throw new IllegalStateException("Polymarket market metadata not found for conditionId=" + conditionId);
        }
        return metadata;
    }

    public Map<String, MarketMetadata> fetchMarketMetadataBatch(Collection<String> conditionIds) {
        return fetchMarketMetadataBatch(conditionIds, configuredGammaChunkSize());
    }

    public Map<String, MarketMetadata> fetchMarketMetadataBatch(Collection<String> conditionIds, int chunkSize) {
        if (conditionIds == null || conditionIds.isEmpty()) {
            return Map.of();
        }
        List<String> normalizedIds = conditionIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        int safeChunkSize = effectiveGammaChunkSize(chunkSize);
        Map<String, MarketMetadata> result = new LinkedHashMap<>();
        List<String> failedIds = new ArrayList<>();
        List<String> cooledDownIds = new ArrayList<>();
        GammaRequestBudget requestBudget = new GammaRequestBudget(maxGammaRequestsPerRefresh());
        for (int start = 0; start < normalizedIds.size(); start += safeChunkSize) {
            List<String> chunk = normalizedIds.subList(start, Math.min(start + safeChunkSize, normalizedIds.size()));
            fetchMarketMetadataChunkResilient(chunk, result, failedIds, cooledDownIds, requestBudget);
        }
        if (!failedIds.isEmpty()) {
            log.warn("Polymarket market metadata fetch degraded status={} failedCount={} cooledDownCount={} requestCount={} endpointCooldownMs={} currentChunkSize={} failedSample={} cooledDownSample={}",
                    firstFailureStatus(failedIds), failedIds.size(), cooledDownIds.size(), requestBudget.used,
                    metadataEndpointCooldownMs(), safeChunkSize, sample(failedIds, 5), sample(cooledDownIds, 5));
        } else if (!cooledDownIds.isEmpty()) {
            log.debug("Polymarket market metadata fetch skipped by cooldown cooledDownCount={} requestCount={} cooledDownSample={}",
                    cooledDownIds.size(), requestBudget.used, sample(cooledDownIds, 5));
        }
        return result;
    }

    private int configuredGammaChunkSize() {
        SmartWalletProperties.MarketMonitoring.MetadataRefresh refresh = properties.getMarketMonitoring().getMetadataRefresh();
        Integer configuredChunkSize = refresh == null ? null : refresh.getGammaChunkSize();
        return configuredChunkSize == null || configuredChunkSize < 1 ? 5 : configuredChunkSize;
    }

    private int effectiveGammaChunkSize(int requestedChunkSize) {
        int configured = configuredGammaChunkSize();
        int adaptive = adaptiveGammaChunkSize > 0 ? adaptiveGammaChunkSize : configured;
        return Math.max(1, Math.min(Math.min(requestedChunkSize, configured), adaptive));
    }

    public boolean isMetadataEndpointCoolingDown() {
        return isCoolingDown(METADATA_ENDPOINT_COOLDOWN_KEY);
    }

    private void fetchMarketMetadataChunkResilient(List<String> conditionIds,
                                                   Map<String, MarketMetadata> result,
                                                   List<String> failedIds,
                                                   List<String> cooledDownIds,
                                                   GammaRequestBudget requestBudget) {
        if (isCoolingDown(METADATA_ENDPOINT_COOLDOWN_KEY)) {
            cooledDownIds.addAll(conditionIds);
            return;
        }
        List<String> activeIds = conditionIds.stream()
                .filter(id -> {
                    boolean coolingDown = isCoolingDown(metadataCooldownKey(id));
                    if (coolingDown) {
                        cooledDownIds.add(id);
                    }
                    return !coolingDown;
                })
                .toList();
        if (activeIds.isEmpty()) {
            return;
        }
        if (!requestBudget.tryAcquire()) {
            cooledDownIds.addAll(activeIds);
            return;
        }
        try {
            Map<String, MarketMetadata> fetched = doFetchMarketMetadataBatch(activeIds);
            result.putAll(fetched);
            fetched.keySet().forEach(id -> clearCooldown(metadataCooldownKey(id)));
            if (!fetched.isEmpty()) {
                metadataEndpointConsecutiveFailures.set(0);
                adaptiveGammaChunkSize = configuredGammaChunkSize();
                clearCooldown(METADATA_ENDPOINT_COOLDOWN_KEY);
            }
        } catch (RuntimeException error) {
            if (isMetadataEndpointBackoffFailure(error)) {
                activeIds.forEach(id -> markCooldown(metadataCooldownKey(id), metadataFailureCooldownMs()));
                failedIds.addAll(activeIds.stream().map(id -> id + ":" + rootMessage(error)).toList());
                adaptiveGammaChunkSize = 1;
                if (metadataEndpointConsecutiveFailures.incrementAndGet() >= endpointFailureThreshold()) {
                    markCooldown(METADATA_ENDPOINT_COOLDOWN_KEY, metadataEndpointCooldownMs());
                }
                return;
            }
            if (activeIds.size() > 1) {
                int midpoint = activeIds.size() / 2;
                fetchMarketMetadataChunkResilient(activeIds.subList(0, midpoint), result, failedIds, cooledDownIds, requestBudget);
                fetchMarketMetadataChunkResilient(activeIds.subList(midpoint, activeIds.size()), result, failedIds, cooledDownIds, requestBudget);
                return;
            }
            String conditionId = activeIds.get(0);
            failedIds.add(conditionId + ":" + rootMessage(error));
            markCooldown(metadataCooldownKey(conditionId), metadataFailureCooldownMs());
        }
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
        return parseMarketMetadataList(body);
    }

    public List<MarketMetadata> searchMarkets(String query, int limitPerType, boolean keepClosedMarkets) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String cooldownKey = publicSearchCooldownKey(query);
        if (isCoolingDown(cooldownKey)) {
            log.debug("Polymarket public-search skipped during cooldown query={}", query);
            return List.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query.trim());
        params.put("limit_per_type", String.valueOf(Math.max(limitPerType, 1)));
        params.put("keep_closed_markets", keepClosedMarkets ? "1" : "0");
        JsonNode body;
        try {
            body = getJson(buildUri(properties.getPolymarket().getGammaApiBaseUrl(), "/public-search", params));
            clearCooldown(cooldownKey);
        } catch (RuntimeException error) {
            markCooldown(cooldownKey, publicSearchFailureCooldownMs());
            log.warn("Polymarket public-search failed query={} cooldownMs={} error={}",
                    query.trim(), publicSearchFailureCooldownMs(), rootMessage(error));
            return List.of();
        }
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

    private String metadataCooldownKey(String conditionId) {
        return "metadata:" + (conditionId == null ? "" : conditionId.trim().toLowerCase());
    }

    private String publicSearchCooldownKey(String query) {
        return "public-search:" + (query == null ? "" : query.trim().toLowerCase());
    }

    private boolean isCoolingDown(String key) {
        Long untilMs = externalFailureCooldownUntilMs.get(key);
        if (untilMs == null) {
            return false;
        }
        if (untilMs <= System.currentTimeMillis()) {
            externalFailureCooldownUntilMs.remove(key, untilMs);
            return false;
        }
        return true;
    }

    private void markCooldown(String key, long ttlMs) {
        externalFailureCooldownUntilMs.put(key, System.currentTimeMillis() + Math.max(1_000L, ttlMs));
    }

    private void clearCooldown(String key) {
        externalFailureCooldownUntilMs.remove(key);
    }

    private long metadataFailureCooldownMs() {
        Long configured = properties.getMarketMonitoring().getMetadataRefresh().getMetadataFailureCooldownMs();
        return configured == null ? 300_000L : Math.max(1_000L, configured);
    }

    private long publicSearchFailureCooldownMs() {
        Long configured = properties.getMarketMonitoring().getMetadataRefresh().getPublicSearchFailureCooldownMs();
        return configured == null ? 900_000L : Math.max(1_000L, configured);
    }

    private long metadataEndpointCooldownMs() {
        Long configured = properties.getMarketMonitoring().getMetadataRefresh().getMetadataEndpointCooldownMs();
        long base = configured == null ? 120_000L : Math.max(1_000L, configured);
        Long max = properties.getMarketMonitoring().getMetadataRefresh().getEndpointCooldownMaxMs();
        return Math.min(base, max == null ? 900_000L : Math.max(1_000L, max));
    }

    private int maxGammaRequestsPerRefresh() {
        Integer configured = properties.getMarketMonitoring().getMetadataRefresh().getMaxGammaRequestsPerRefresh();
        return configured == null ? 20 : Math.max(1, Math.min(configured, 100));
    }

    private int endpointFailureThreshold() {
        Integer configured = properties.getMarketMonitoring().getMetadataRefresh().getEndpointFailureThreshold();
        return configured == null ? 2 : Math.max(1, configured);
    }

    private boolean isMetadataEndpointBackoffFailure(Throwable error) {
        String message = rootMessage(error).toLowerCase();
        return message.contains("status=429")
                || message.contains("status=500")
                || message.contains("status=502")
                || message.contains("status=503")
                || message.contains("status=504")
                || message.contains("status=403");
    }

    private String firstFailureStatus(List<String> failedIds) {
        if (failedIds == null || failedIds.isEmpty()) {
            return "none";
        }
        String first = failedIds.get(0);
        int index = first == null ? -1 : first.indexOf("status=");
        if (index < 0) {
            return "unknown";
        }
        int start = index + "status=".length();
        int end = start;
        while (end < first.length() && Character.isDigit(first.charAt(end))) {
            end++;
        }
        return end > start ? first.substring(start, end) : "unknown";
    }

    private List<String> sample(List<String> values, int max) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.size() <= max ? values : values.subList(0, max);
    }

    private static class GammaRequestBudget {
        private final int limit;
        private int used;

        private GammaRequestBudget(int limit) {
            this.limit = limit;
        }

        private boolean tryAcquire() {
            if (used >= limit) {
                return false;
            }
            used++;
            return true;
        }
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

    private Map<String, MarketMetadata> doFetchMarketMetadataBatch(List<String> conditionIds) {
        String joinedConditionIds = String.join(",", conditionIds);
        JsonNode body = getJsonSingleAttempt(buildUri(properties.getPolymarket().getGammaApiBaseUrl(),
                "/markets", Map.of("condition_ids", joinedConditionIds)));
        Map<String, MarketMetadata> result = new LinkedHashMap<>();
        for (MarketMetadata metadata : parseMarketMetadataList(body)) {
            if (metadata != null && StringUtils.hasText(metadata.getConditionId())) {
                result.put(metadata.getConditionId().trim(), metadata);
            }
        }
        return result;
    }

    private List<MarketMetadata> parseMarketMetadataList(JsonNode body) {
        List<MarketMetadata> results = new ArrayList<>();
        JsonNode arr = extractArray(body);
        for (JsonNode market : iterableArray(arr)) {
            if (market != null && !market.isNull()) {
                results.add(parseMarketMetadata(market));
            }
        }
        return results;
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
        String winningTokenId = null;
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
                    winningTokenId = StringUtils.hasText(tokenId) ? tokenId.trim() : null;
                }
            }
        }
        if (resolvedOutcome == null) {
            String explicitWinner = normalizeOutcomeLabel(text(market, "winner", "winningOutcome", "resolutionOutcome"));
            if (StringUtils.hasText(explicitWinner)) {
                resolvedOutcome = explicitWinner;
            }
        }
        if (!StringUtils.hasText(winningTokenId) && StringUtils.hasText(resolvedOutcome)) {
            winningTokenId = outcomeTokenMap.get(resolvedOutcome);
        }
        // Build tokenId → indexSet mapping from the outcomeTokenIds ordering.
        // CTF convention: indexSet = 1 << outcomeIndex (1 for first outcome, 2 for second, etc.)
        Map<String, Integer> indexSetMap = new LinkedHashMap<>();
        int outcomeIdx = 0;
        for (Map.Entry<String, String> entry : outcomeTokenMap.entrySet()) {
            if (StringUtils.hasText(entry.getValue())) {
                indexSetMap.put(entry.getValue().trim(), 1 << outcomeIdx);
            }
            outcomeIdx++;
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
                .winningTokenId(winningTokenId)
                .endAt(epochMillis(market, "endDateISO", "endDate", "gameStartTime", "eventStartDate"))
                .umaEndAt(epochMillis(market, "umaEndDate", "umaEndDateISO", "uma_end_date"))
                .outcomes(new ArrayList<>(outcomes))
                .outcomePrices(new ArrayList<>(outcomePrices))
                .outcomeTokenIds(outcomeTokenMap)
                .tokenIdToIndexSet(indexSetMap)
                .build();
    }

    private ActivePosition parseActivePosition(String walletAddress, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        BigDecimal size = firstNonZero(
                num(node, "size"),
                num(node, "currentTokens"),
                num(node, "sizeBought"),
                num(node, "amount"));
        BigDecimal investedUsdc = firstNonZero(
                num(node, "cashInvested"),
                num(node, "initialValue"),
                num(node, "totalBought"),
                num(node, "costBasis"),
                num(node, "costBasisUsdc"));
        BigDecimal currentValueUsdc = firstNonZero(
                num(node, "currentValue"),
                num(node, "currentValueUsdc"),
                num(node, "value"),
                num(node, "positionValue"));
        BigDecimal unrealizedPnlUsdc = firstNonZero(
                num(node, "unrealizedPnl"),
                num(node, "unrealizedPnlUsdc"),
                num(node, "pnl"));
        if (unrealizedPnlUsdc == null && currentValueUsdc != null && investedUsdc != null) {
            unrealizedPnlUsdc = currentValueUsdc.subtract(investedUsdc);
        }
        return ActivePosition.builder()
                .walletAddress(walletAddress)
                .marketId(text(node, "conditionId", "market", "marketId"))
                .question(text(node, "question", "title"))
                .outcome(normalizeOutcome(text(node, "outcome", "outcomeName", "asset")))
                .size(size)
                .avgPrice(firstNonZero(num(node, "avgPrice", "averagePrice"), priceFromCostAndSize(investedUsdc, size)))
                .investedUsdc(investedUsdc)
                .currentValueUsdc(currentValueUsdc)
                .unrealizedPnlUsdc(unrealizedPnlUsdc)
                .openedAt(epochMillis(node, "openedAt", "createdAt", "timestamp"))
                .marketEndAt(epochMillis(node, "endDateISO", "endDate", "gameStartTime", "eventStartDate"))
                .settlementAt(epochMillis(node, "closedAt", "redeemedAt", "settlementAt"))
                .build();
    }

    private BigDecimal parsePrice(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.debug("Failed to parse Polymarket price {}", raw, e);
            return null;
        }
    }

    private BigDecimal priceFromCostAndSize(BigDecimal investedUsdc, BigDecimal size) {
        if (investedUsdc == null || size == null || size.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return investedUsdc.divide(size, 8, RoundingMode.HALF_UP);
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
                return parseJsonBody(response.body().string(), uri, "http");
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

    private JsonNode getJsonSingleAttempt(URI uri) {
        Request request = buildRequest(uri);
        try (Response response = fastFallbackClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Polymarket API request failed: " + uri + ", status=" + response.code());
            }
            if (response.body() == null) {
                throw new IllegalStateException("Polymarket API response body is empty: " + uri);
            }
            return parseJsonBody(response.body().string(), uri, "http");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to request Polymarket API: " + uri, e);
        }
    }

    private JsonNode getJsonViaCurl(URI uri) {
        try {
            Process process = new ProcessBuilder(
                    "curl",
                    "-sS",
                    "-f",
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
            try {
                return parseJsonBody(body, uri, "curl fallback");
            } catch (IOException e) {
                log.warn("Polymarket API curl fallback returned unusable response for {}: {}", uri, rootMessage(e));
                return null;
            }
        } catch (Exception e) {
            log.warn("Polymarket API curl fallback errored for {}", uri, e);
            return null;
        }
    }

    private JsonNode parseJsonBody(String body, URI uri, String channel) throws IOException {
        if (!StringUtils.hasText(body)) {
            throw new IOException("Polymarket API " + channel + " response body is empty");
        }
        String trimmed = body.trim();
        if (!looksLikeJson(trimmed)) {
            throw new IOException("Polymarket API " + channel + " returned non-JSON response for "
                    + uri + ": " + summarizeBody(trimmed));
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (IOException e) {
            throw new IOException("Polymarket API " + channel + " returned invalid JSON for "
                    + uri + ": " + summarizeBody(trimmed), e);
        }
    }

    private boolean looksLikeJson(String body) {
        if (!StringUtils.hasText(body)) {
            return false;
        }
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        return first == '{'
                || first == '['
                || first == '"'
                || first == '-'
                || (first >= '0' && first <= '9')
                || trimmed.startsWith("true")
                || trimmed.startsWith("false")
                || trimmed.startsWith("null");
    }

    private String summarizeBody(String body) {
        if (!StringUtils.hasText(body)) {
            return "<empty>";
        }
        String compact = body.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
        if (compact.length() <= 160) {
            return compact;
        }
        return compact.substring(0, 157) + "...";
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

    private String closedPositionKey(ClosedPosition position) {
        if (position == null) {
            return "";
        }
        return defaultString(position.getMarketId())
                + "|" + defaultString(position.getOutcome())
                + "|" + (position.getClosedAt() == null ? "NULL" : position.getClosedAt());
    }

    private String userTradeKey(UserTrade trade) {
        if (trade == null) {
            return "";
        }
        if (StringUtils.hasText(trade.getTradeId())) {
            return trade.getTradeId().trim();
        }
        return defaultString(trade.getMarketId())
                + "|" + defaultString(trade.getOutcome())
                + "|" + defaultString(trade.getSide())
                + "|" + (trade.getTradedAt() == null ? "NULL" : trade.getTradedAt());
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
        private Long openedAt;
        private Long closedAt;
    }

    @Data
    @Builder
    public static class ActivePosition {
        private String walletAddress;
        private String marketId;
        private String question;
        private String outcome;
        private BigDecimal size;
        private BigDecimal avgPrice;
        private BigDecimal investedUsdc;
        private BigDecimal currentValueUsdc;
        private BigDecimal unrealizedPnlUsdc;
        private Long openedAt;
        private Long marketEndAt;
        private Long settlementAt;
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
        private String winningTokenId;
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
        @Builder.Default
        private Map<String, Integer> tokenIdToIndexSet = new LinkedHashMap<>();

        public boolean isResolved() {
            return StringUtils.hasText(resolvedOutcome);
        }

        /**
         * Resolve the CTF indexSet for a given tokenId.
         * In Polymarket CTF: indexSet = 1 << outcomeIndex (1 for outcome 0 / YES, 2 for outcome 1 / NO).
         * Returns null if the tokenId is not mapped.
         */
        public Integer resolveIndexSet(String tokenId) {
            if (!StringUtils.hasText(tokenId) || tokenIdToIndexSet == null || tokenIdToIndexSet.isEmpty()) {
                return deriveIndexSetFromOutcomes(tokenId);
            }
            return tokenIdToIndexSet.get(tokenId.trim());
        }

        /**
         * Fallback: derive indexSet by matching tokenId against outcomeTokenIds ordering.
         */
        private Integer deriveIndexSetFromOutcomes(String tokenId) {
            if (!StringUtils.hasText(tokenId) || outcomeTokenIds == null || outcomeTokenIds.isEmpty()) {
                return null;
            }
            int index = 0;
            for (Map.Entry<String, String> entry : outcomeTokenIds.entrySet()) {
                if (StringUtils.hasText(entry.getValue()) && tokenId.trim().equals(entry.getValue().trim())) {
                    return 1 << index;
                }
                index++;
            }
            return null;
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
