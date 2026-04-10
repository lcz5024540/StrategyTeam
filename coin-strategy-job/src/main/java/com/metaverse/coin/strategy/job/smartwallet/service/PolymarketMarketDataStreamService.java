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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    public MarketDataSnapshot getSnapshot(String tokenId) {
        if (!StringUtils.hasText(tokenId)) {
            return null;
        }
        return snapshotCache.get(tokenId.trim());
    }

    public MarketDataStreamStats getStats() {
        return MarketDataStreamStats.builder()
                .enabled(properties.getExecutionWallet().isWsEnabled())
                .connected(false)
                .subscribedTokenCount(snapshotCache.size())
                .cachedTokenCount(snapshotCache.size())
                .messagesReceived(messagesReceived.get())
                .bookEventsReceived(bookEventsReceived.get())
                .priceEventsReceived(priceEventsReceived.get())
                .lastEventAt(lastEventAt)
                .build();
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
            MarketDataSnapshot snapshot = MarketDataSnapshot.builder()
                    .tokenId(tokenId.trim())
                    .orderBook(orderBook)
                    .lastTradePrice(lastTradePrice)
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
    }
}
