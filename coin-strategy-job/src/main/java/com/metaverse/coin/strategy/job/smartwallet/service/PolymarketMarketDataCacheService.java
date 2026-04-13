package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polymarket 市场数据集中缓存服务
 *
 * 解决两个策略（跟单 + 对冲）并发调用相同 Polymarket REST API 导致的限频风险：
 *
 * 1. 活跃市场列表 —— 由单一 @Scheduled 任务每 {@code polymarket.market-data-cache.markets-refresh-ms}
 *    毫秒刷新一次，所有调用方通过 {@link #getActiveMarkets()} 读取同一份缓存，
 *    彻底消除 HedgeScanJob 每 10s 一次 searchMarkets() 调用。
 *
 * 2. 订单簿 —— cache-aside 模式，TTL = {@code polymarket.market-data-cache.order-book-ttl-ms}（默认 20s）。
 *    同一 tokenId 在 TTL 内被多个调用方（ArbitrageDetectorService、MarketSnapshotCollectorService）
 *    复用，最多每 20s 发起一次真实 REST 请求。
 *
 * 线程安全：
 *   - cachedActiveMarkets 是 volatile 引用替换，读取无锁
 *   - orderBookCache 使用 ConcurrentHashMap；同一 tokenId 在缓存过期时极端情况下可能
 *     触发两次并发 API 调用（不加锁是有意的，避免 HedgeScanJob 批量扫描时形成锁竞争）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketMarketDataCacheService {

    private final PolymarketApiClient apiClient;

    /** 活跃市场列表每次刷新拉取的最大市场数（默认 500） */
    @Value("${polymarket.market-data-cache.markets-batch-size:500}")
    private int marketsBatchSize;

    /** 订单簿缓存有效期（毫秒），默认 20s */
    @Value("${polymarket.market-data-cache.order-book-ttl-ms:20000}")
    private long orderBookTtlMs;

    // — 活跃市场列表 —
    private volatile List<PolymarketApiClient.MarketMetadata> cachedActiveMarkets = Collections.emptyList();
    private volatile long marketsLastRefreshedAt = 0L;

    // — 订单簿 cache-aside —
    private final ConcurrentHashMap<String, OrderBookEntry> orderBookCache = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // 活跃市场列表
    // -----------------------------------------------------------------------

    /**
     * 定时刷新活跃市场列表。
     * 默认每 60s 一次，将原来 HedgeScanJob 每 10s 调用 searchMarkets() 减少为 1/6。
     */
    @Scheduled(fixedDelayString = "${polymarket.market-data-cache.markets-refresh-ms:60000}")
    public void refreshActiveMarkets() {
        try {
            List<PolymarketApiClient.MarketMetadata> markets = apiClient.fetchActiveMarkets(marketsBatchSize);
            if (markets != null && !markets.isEmpty()) {
                cachedActiveMarkets = markets;
                marketsLastRefreshedAt = System.currentTimeMillis();
                log.debug("[MarketDataCache] Refreshed active markets: {} entries", markets.size());
            }
        } catch (Exception e) {
            log.warn("[MarketDataCache] Failed to refresh active markets: {}", e.getMessage());
        }
    }

    /**
     * 返回最近一次刷新的活跃市场列表（可能略有延迟，可接受）。
     * 如果缓存为空（首次启动尚未刷新），立即触发同步刷新。
     */
    public List<PolymarketApiClient.MarketMetadata> getActiveMarkets() {
        if (cachedActiveMarkets.isEmpty()) {
            refreshActiveMarkets();
        }
        return cachedActiveMarkets;
    }

    /** 返回市场列表上次刷新时间（ms epoch），用于监控 */
    public long getMarketsLastRefreshedAt() {
        return marketsLastRefreshedAt;
    }

    // -----------------------------------------------------------------------
    // 订单簿缓存
    // -----------------------------------------------------------------------

    /**
     * 获取指定 tokenId 的订单簿（cache-aside）。
     * 同一 tokenId 在 TTL 内复用同一份结果，TTL 过期后惰性刷新。
     */
    public PolymarketApiClient.OrderBook getOrderBook(String tokenId) {
        if (!StringUtils.hasText(tokenId)) {
            throw new IllegalArgumentException("tokenId is required");
        }
        long now = System.currentTimeMillis();
        OrderBookEntry entry = orderBookCache.get(tokenId);
        if (entry != null && now - entry.cachedAt() < orderBookTtlMs) {
            return entry.orderBook();
        }
        // Cache miss or stale — fetch from upstream
        PolymarketApiClient.OrderBook book = apiClient.fetchOrderBook(tokenId);
        orderBookCache.put(tokenId, new OrderBookEntry(book, now));
        return book;
    }

    /**
     * 主动使某 tokenId 的订单簿缓存失效（下次访问会重新拉取）。
     * 执行下单前可调用此方法以获得最新价格。
     */
    public void invalidateOrderBook(String tokenId) {
        if (StringUtils.hasText(tokenId)) {
            orderBookCache.remove(tokenId);
        }
    }

    /**
     * 清理超过 10 倍 TTL 的陈旧订单簿条目，防止内存无限增长。
     * 每 5 分钟运行一次。
     */
    @Scheduled(fixedDelay = 300_000)
    public void evictStaleOrderBooks() {
        long threshold = System.currentTimeMillis() - orderBookTtlMs * 10;
        int removed = 0;
        var it = orderBookCache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().cachedAt() < threshold) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[MarketDataCache] Evicted {} stale order book entries", removed);
        }
    }

    /** 缓存统计（用于监控） */
    public CacheStats getStats() {
        return new CacheStats(
                cachedActiveMarkets.size(),
                marketsLastRefreshedAt,
                orderBookCache.size()
        );
    }

    // -----------------------------------------------------------------------
    // Internal types
    // -----------------------------------------------------------------------

    private record OrderBookEntry(PolymarketApiClient.OrderBook orderBook, long cachedAt) {}

    public record CacheStats(int activeMarketsCount, long marketsLastRefreshedAt, int orderBookCacheSize) {}
}
