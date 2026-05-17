package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketMarketDataStreamService {

    private final SmartWalletProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, MarketDataSnapshot> snapshotCache = new ConcurrentHashMap<>();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong bookEventsReceived = new AtomicLong();
    private final AtomicLong priceEventsReceived = new AtomicLong();
    private volatile Date lastEventAt;
    // F-P1-7 第十轮：真实 WS 客户端连接状态。由 PolymarketWebSocketClient 在 onOpen/onClosed/onFailure
    // 回调里调 markConnected(true/false)，getStats().connected 透出真实 live 状态（之前硬编码 false）。
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile Date connectedAt;
    private volatile Date disconnectedAt;
    private final AtomicLong reconnectAttempts = new AtomicLong();

    public MarketDataSnapshot getSnapshot(String tokenId) {
        if (!StringUtils.hasText(tokenId)) {
            return null;
        }
        String normalizedTokenId = tokenId.trim();
        MarketDataSnapshot snapshot = snapshotCache.get(normalizedTokenId);
        if (snapshot == null) {
            return null;
        }
        Long ttlMs = properties.getExecutionWallet() == null ? null : properties.getExecutionWallet().getWsCacheTtlMs();
        if (ttlMs != null && ttlMs > 0 && snapshot.getUpdatedAt() != null
                && System.currentTimeMillis() - snapshot.getUpdatedAt().getTime() > ttlMs) {
            snapshotCache.remove(normalizedTokenId, snapshot);
            return null;
        }
        return snapshot;
    }

    public MarketDataSnapshot getCachedSnapshot(String tokenId) {
        return getSnapshot(tokenId);
    }

    public MarketDataStreamStats getStats() {
        return MarketDataStreamStats.builder()
                .enabled(properties.getExecutionWallet().isWsEnabled())
                .connected(connected.get())
                .subscribedTokenCount(snapshotCache.size())
                .cachedTokenCount(snapshotCache.size())
                .messagesReceived(messagesReceived.get())
                .bookEventsReceived(bookEventsReceived.get())
                .priceEventsReceived(priceEventsReceived.get())
                .lastEventAt(lastEventAt)
                .connectedAt(connectedAt)
                .disconnectedAt(disconnectedAt)
                .reconnectAttempts(reconnectAttempts.get())
                .build();
    }

    /**
     * F-P1-7 第十轮：WS 客户端连接状态回调入口。
     * <p>由 {@code PolymarketWebSocketClient} 在 onOpen / onClosed / onFailure 时调用，
     * 不直接依赖 dataStream 内部字段，保证 dataStream 在测试 / 无 WS 客户端的环境下行为不变。
     *
     * @param isConnected true=已连接（标记 connectedAt），false=已断开（标记 disconnectedAt）
     */
    public void markConnected(boolean isConnected) {
        boolean prev = connected.getAndSet(isConnected);
        Date now = new Date();
        if (isConnected) {
            connectedAt = now;
        } else {
            disconnectedAt = now;
        }
        if (prev != isConnected) {
            log.info("[POLY_WS_DATA] action=连接状态变更 from={} to={}", prev, isConnected);
        }
    }

    /** F-P1-7 第十轮：reconnect attempts 计数透出。 */
    public void incrementReconnectAttempts() {
        reconnectAttempts.incrementAndGet();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void ingestMockEvent(String payload) {
        if (!StringUtils.hasText(payload)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode event = root.has("event") ? root.get("event") : root;
            String tokenId = text(event, "tokenId", "token_id", "asset_id");
            if (!StringUtils.hasText(tokenId)) {
                log.debug("Skip mock market-data event because tokenId is missing");
                return;
            }
            PolymarketApiClient.OrderBook orderBook = parseOrderBook(event, tokenId);
            BigDecimal lastTradePrice = number(event, "lastTradePrice", "last_trade_price", "price");
            MarketDataSnapshot previous = snapshotCache.get(tokenId.trim());
            MarketDataSnapshot snapshot = MarketDataSnapshot.builder()
                    .tokenId(tokenId.trim())
                    .orderBook(orderBook != null ? orderBook : previous == null ? null : previous.getOrderBook())
                    .lastTradePrice(lastTradePrice != null ? lastTradePrice : previous == null ? null : previous.getLastTradePrice())
                    .updatedAt(new Date())
                    .build();
            snapshotCache.put(tokenId.trim(), snapshot);
            messagesReceived.incrementAndGet();
            if (orderBook != null) {
                bookEventsReceived.incrementAndGet();
            }
            if (lastTradePrice != null && lastTradePrice.compareTo(BigDecimal.ZERO) > 0) {
                priceEventsReceived.incrementAndGet();
            }
            lastEventAt = snapshot.getUpdatedAt();
        } catch (Exception e) {
            log.warn("Failed to ingest mock Polymarket WS event: {}", e.getMessage());
        }
    }

    public void handleMessage(String payload) {
        ingestMockEvent(payload);
    }

    private PolymarketApiClient.OrderBook parseOrderBook(JsonNode node, String tokenId) {
        JsonNode bidsNode = firstPresent(node, "bids", "buy");
        JsonNode asksNode = firstPresent(node, "asks", "sell");
        List<PolymarketApiClient.OrderBookLevel> bids = parseLevels(bidsNode);
        List<PolymarketApiClient.OrderBookLevel> asks = parseLevels(asksNode);
        if (bids.isEmpty() && asks.isEmpty()) {
            return null;
        }
        BigDecimal midpoint = number(node, "midpoint", "mid", "mid_price");
        if (midpoint == null || midpoint.compareTo(BigDecimal.ZERO) <= 0) {
            midpoint = midpointFromBook(bids, asks);
        }
        BigDecimal spread = number(node, "spread", "spread_bps", "spreadBps");
        if ((spread == null || spread.compareTo(BigDecimal.ZERO) <= 0)
                && midpoint != null
                && midpoint.compareTo(BigDecimal.ZERO) > 0
                && !bids.isEmpty()
                && !asks.isEmpty()) {
            spread = asks.get(0).getPrice()
                    .subtract(bids.get(0).getPrice())
                    .divide(midpoint, 8, BigDecimal.ROUND_HALF_UP)
                    .multiply(new BigDecimal("10000"));
        }
        return PolymarketApiClient.OrderBook.builder()
                .tokenId(tokenId)
                .midpoint(midpoint == null ? BigDecimal.ZERO : midpoint)
                .spread(spread == null ? BigDecimal.ZERO : spread)
                .bids(bids)
                .asks(asks)
                .build();
    }

    private List<PolymarketApiClient.OrderBookLevel> parseLevels(JsonNode levelsNode) {
        List<PolymarketApiClient.OrderBookLevel> levels = new ArrayList<>();
        if (levelsNode == null || !levelsNode.isArray()) {
            return levels;
        }
        for (JsonNode level : levelsNode) {
            if (level == null || level.isNull()) {
                continue;
            }
            BigDecimal price;
            BigDecimal size;
            if (level.isArray() && level.size() >= 2) {
                price = decimal(level.get(0));
                size = decimal(level.get(1));
            } else {
                price = number(level, "price", "p");
                size = number(level, "size", "s", "quantity");
            }
            if (price == null || size == null) {
                continue;
            }
            levels.add(PolymarketApiClient.OrderBookLevel.builder()
                    .price(price)
                    .size(size)
                    .build());
        }
        return levels;
    }

    private BigDecimal midpointFromBook(List<PolymarketApiClient.OrderBookLevel> bids,
                                        List<PolymarketApiClient.OrderBookLevel> asks) {
        if (bids.isEmpty() || asks.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return bids.get(0).getPrice()
                .add(asks.get(0).getPrice())
                .divide(new BigDecimal("2"), 8, BigDecimal.ROUND_HALF_UP);
    }

    private JsonNode firstPresent(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode candidate = node.get(key);
            if (candidate != null && !candidate.isNull()) {
                return candidate;
            }
        }
        return null;
    }

    private String text(JsonNode node, String... keys) {
        JsonNode value = firstPresent(node, keys);
        return value == null ? null : value.asText(null);
    }

    private BigDecimal number(JsonNode node, String... keys) {
        return decimal(firstPresent(node, keys));
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Data
    @Builder
    public static class MarketDataSnapshot {
        private String tokenId;
        private PolymarketApiClient.OrderBook orderBook;
        private BigDecimal lastTradePrice;
        private Date updatedAt;
    }

    @Data
    @Builder
    public static class MarketDataStreamStats {
        private boolean enabled;
        private boolean connected;
        private int subscribedTokenCount;
        private int cachedTokenCount;
        private long messagesReceived;
        private long bookEventsReceived;
        private long priceEventsReceived;
        private Date lastEventAt;
        // F-P1-7 第十轮：连接生命周期可观测性字段（向后兼容；旧调用方不读这些字段也能编译）
        private Date connectedAt;
        private Date disconnectedAt;
        private long reconnectAttempts;
    }
}
