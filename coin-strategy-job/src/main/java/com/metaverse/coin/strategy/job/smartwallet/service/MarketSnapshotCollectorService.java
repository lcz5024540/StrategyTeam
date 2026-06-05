package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketSnapshotMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

@Service
@Slf4j
@RequiredArgsConstructor
public class MarketSnapshotCollectorService {

    private final SmartWalletMarketSnapshotMapper marketSnapshotMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final PolymarketApiClient polymarketApiClient;
    private final PolymarketMarketDataStreamService marketDataStreamService;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final PolymarketMarketDataCacheService marketDataCache;
    private final SmartWalletProperties smartWalletProperties;
    private final LongAdder wsSnapshotHits = new LongAdder();
    private final LongAdder restSnapshotFallbacks = new LongAdder();
    private final LongAdder hotPathWsHits = new LongAdder();
    private final LongAdder hotPathDbFallbacks = new LongAdder();

    public SmartWalletMarketSnapshotEntity saveSnapshot(SmartWalletMarketSnapshotEntity snapshot) {
        if (snapshot == null || !StringUtils.hasText(snapshot.getMarketId()) || snapshot.getSnapshotAt() == null) {
            throw new IllegalArgumentException("market snapshot requires marketId and snapshotAt");
        }
        SmartWalletMarketSnapshotEntity existing = marketSnapshotMapper.selectOne(new LambdaQueryWrapper<SmartWalletMarketSnapshotEntity>()
                .eq(SmartWalletMarketSnapshotEntity::getMarketId, snapshot.getMarketId())
                .eq(SmartWalletMarketSnapshotEntity::getOutcome, snapshot.getOutcome())
                .eq(SmartWalletMarketSnapshotEntity::getSnapshotAt, snapshot.getSnapshotAt())
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        snapshot.setCreatedAt(snapshot.getCreatedAt() == null ? new Date() : snapshot.getCreatedAt());
        marketSnapshotMapper.insert(snapshot);
        return snapshot;
    }

    public SmartWalletMarketSnapshotEntity collectSnapshot(String conditionId, String outcome) {
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(conditionId);
        String normalizedOutcome = normalizeOutcome(outcome);
        String tokenId = metadata.getOutcomeTokenIds().get(normalizedOutcome);
        if (!StringUtils.hasText(tokenId)) {
            throw new IllegalStateException("Polymarket token id not found for outcome=" + normalizedOutcome
                    + " conditionId=" + conditionId);
        }
        PolymarketMarketDataStreamService.MarketDataSnapshot marketDataSnapshot = marketDataStreamService.getSnapshot(tokenId);
        boolean usedWs = marketDataSnapshot != null && marketDataSnapshot.getOrderBook() != null;
        if (usedWs) {
            wsSnapshotHits.increment();
        } else {
            restSnapshotFallbacks.increment();
            log.debug("Polymarket market snapshot fallback to REST marketId={} outcome={} tokenId={}",
                    conditionId, normalizedOutcome, tokenId);
        }
        PolymarketApiClient.OrderBook orderBook = usedWs
                ? marketDataSnapshot.getOrderBook()
                : marketDataCache.getOrderBook(tokenId);
        // 问题4 修复（2026-06-02）：CLOB /trades 端点需要 API key，test 环境未配置 → 401。
        // trades 仅用于补充 lastTradePrice/volume 等次要字段；盘口核心字段（bestBid/bestAsk/
        // depth/mid）来自 orderBook。此前 trades 抛 401 会让整个 collectSnapshot 抛出、快照不落库，
        // 导致 smart_wallet_market_snapshot 长期为空。这里把 trades 拉取降级为非致命：失败则空列表，
        // 仍保存基于盘口的快照，保证开单/估值价能用上真实盘口。
        List<PolymarketApiClient.MarketTrade> trades;
        try {
            trades = polymarketApiClient.fetchMarketTrades(conditionId, 50, recentEpochSeconds(120));
        } catch (Exception tradesEx) {
            trades = java.util.List.of();
            log.debug("Market snapshot trades fetch failed (non-fatal) marketId={} outcome={}: {}",
                    conditionId, normalizedOutcome, tradesEx.getMessage());
        }
        BigDecimal mid = safe(orderBook.getMidpoint());
        if (mid.compareTo(BigDecimal.ZERO) <= 0) {
            mid = midpointFromTop(orderBook);
        }

        SmartWalletMarketSnapshotEntity snapshot = new SmartWalletMarketSnapshotEntity()
                .setMarketId(conditionId)
                .setOutcome(normalizedOutcome)
                .setSnapshotAt(new Date())
                .setMidPrice(mid)
                .setBestBid(topPrice(orderBook.getBids()))
                .setBestAsk(topPrice(orderBook.getAsks()))
                .setSpreadBps(resolveSpreadBps(orderBook, mid))
                .setBidDepth1c(depthWithin(orderBook.getBids(), mid, new BigDecimal("0.01")))
                .setAskDepth1c(depthWithin(orderBook.getAsks(), mid, new BigDecimal("0.01")))
                .setBidDepth3c(depthWithin(orderBook.getBids(), mid, new BigDecimal("0.03")))
                .setAskDepth3c(depthWithin(orderBook.getAsks(), mid, new BigDecimal("0.03")))
                .setBidDepth5c(depthWithin(orderBook.getBids(), mid, new BigDecimal("0.05")))
                .setAskDepth5c(depthWithin(orderBook.getAsks(), mid, new BigDecimal("0.05")))
                .setOrderImbalance(orderImbalance(orderBook, mid))
                .setLastTradePrice(resolveLastTradePrice(marketDataSnapshot, trades))
                .setLastTradeSize(latestTradeSize(trades))
                .setTradeCount30s(countTradesWithin(trades, 30))
                .setBuyVolume30s(volumeWithin(trades, "BUY", 30))
                .setSellVolume30s(volumeWithin(trades, "SELL", 30))
                .setRealizedVol30s(realizedVolatility(trades, 30))
                .setTimeToEndSeconds(timeToEndSeconds(metadata.getEndAt()));
        return saveSnapshot(snapshot);
    }

    public List<SmartWalletMarketSnapshotEntity> collectRecentActiveMarketSnapshots(Integer marketLimit) {
        int normalizedLimit = marketLimit == null ? 20 : Math.max(1, Math.min(marketLimit, 100));
        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                .last("limit " + Math.max(normalizedLimit * 4, 20)));
        Set<String> marketOutcomeKeys = new LinkedHashSet<>();
        List<SmartWalletMarketSnapshotEntity> snapshots = new ArrayList<>();
        for (SmartWalletTradeEventEntity event : events) {
            if (event == null || !StringUtils.hasText(event.getMarketId())) {
                continue;
            }
            String outcome = normalizeOutcome(event.getOutcome());
            String key = event.getMarketId().trim() + "|" + outcome;
            if (!marketOutcomeKeys.add(key)) {
                continue;
            }
            try {
                snapshots.add(collectSnapshot(event.getMarketId(), outcome));
            } catch (Exception ex) {
                log.debug("Skip market snapshot for marketId={} outcome={} because {}", event.getMarketId(), outcome, ex.getMessage());
            }
            if (snapshots.size() >= normalizedLimit) {
                break;
            }
        }
        return snapshots;
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.market-snapshot-fixed-delay-ms:45000}")
    public void scheduledCollectRecentMarketSnapshots() {
        try {
            List<SmartWalletMarketSnapshotEntity> snapshots = collectRecentActiveMarketSnapshots(20);
            log.info("Scheduled market snapshot collection finished: snapshots={} wsHits={} restFallbacks={}",
                    snapshots.size(), wsSnapshotHits.sum(), restSnapshotFallbacks.sum());
        } catch (Exception ex) {
            log.warn("Scheduled market snapshot collection failed: {}", ex.getMessage());
        }
    }

    /** R3(2026-06-03)：最近一条快照的时间，用于健康监控判断盘口快照管道是否停滞/为空（null=表空）。 */
    public java.util.Date latestSnapshotAt() {
        SmartWalletMarketSnapshotEntity latest = marketSnapshotMapper.selectOne(
                new LambdaQueryWrapper<SmartWalletMarketSnapshotEntity>()
                        .orderByDesc(SmartWalletMarketSnapshotEntity::getSnapshotAt)
                        .last("limit 1"));
        return latest == null ? null : latest.getSnapshotAt();
    }

    public List<SmartWalletMarketSnapshotEntity> listRecentSnapshots(String marketId, String outcome, int limit) {
        LambdaQueryWrapper<SmartWalletMarketSnapshotEntity> query = new LambdaQueryWrapper<SmartWalletMarketSnapshotEntity>()
                .orderByDesc(SmartWalletMarketSnapshotEntity::getSnapshotAt)
                .last("limit " + Math.max(1, Math.min(limit, 500)));
        if (StringUtils.hasText(marketId)) {
            query.eq(SmartWalletMarketSnapshotEntity::getMarketId, marketId.trim());
        }
        if (StringUtils.hasText(outcome)) {
            query.eq(SmartWalletMarketSnapshotEntity::getOutcome, outcome.trim().toUpperCase());
        }
        return marketSnapshotMapper.selectList(query);
    }

    public SnapshotCollectionStats snapshotStats() {
        return SnapshotCollectionStats.builder()
                .wsHits(wsSnapshotHits.sum())
                .restFallbacks(restSnapshotFallbacks.sum())
                .build();
    }

    public SmartWalletMarketSnapshotEntity findNearestSnapshotAtOrAfter(String marketId, String outcome, Date targetTime) {
        if (!StringUtils.hasText(marketId) || targetTime == null) {
            return null;
        }
        LambdaQueryWrapper<SmartWalletMarketSnapshotEntity> query = new LambdaQueryWrapper<SmartWalletMarketSnapshotEntity>()
                .eq(SmartWalletMarketSnapshotEntity::getMarketId, marketId.trim())
                .eq(SmartWalletMarketSnapshotEntity::getOutcome, normalizeOutcome(outcome))
                .ge(SmartWalletMarketSnapshotEntity::getSnapshotAt, targetTime)
                .orderByAsc(SmartWalletMarketSnapshotEntity::getSnapshotAt)
                .last("limit 1");
        return marketSnapshotMapper.selectOne(query);
    }

    public SmartWalletMarketSnapshotEntity findLatestSnapshotAtOrBefore(String marketId, String outcome, Date targetTime) {
        if (!StringUtils.hasText(marketId) || targetTime == null) {
            return null;
        }
        LambdaQueryWrapper<SmartWalletMarketSnapshotEntity> query = new LambdaQueryWrapper<SmartWalletMarketSnapshotEntity>()
                .eq(SmartWalletMarketSnapshotEntity::getMarketId, marketId.trim())
                .eq(SmartWalletMarketSnapshotEntity::getOutcome, normalizeOutcome(outcome))
                .le(SmartWalletMarketSnapshotEntity::getSnapshotAt, targetTime)
                .orderByDesc(SmartWalletMarketSnapshotEntity::getSnapshotAt)
                .last("limit 1");
        return marketSnapshotMapper.selectOne(query);
    }

    /**
     * F-P1-7: Hot-path "freshest available snapshot" read.
     *
     * <p>当 {@code smart-wallet.polymarket.enable-ws-hot-path-snapshot=true} 时优先尝试从
     * {@link PolymarketMarketDataStreamService#getSnapshot(String)} (in-memory WS 实时数据,
     * 时延 < 1s) 合成一个 {@link SmartWalletMarketSnapshotEntity} 返回, 不可用 / stale
     * (超过 {@code wsHotPathSnapshotMaxAgeMs}) 时自动回落到 {@link #findLatestSnapshotAtOrBefore}
     * (45s polling 入库的 DB 结果)。开关关闭 (默认) 时与 {@code findLatestSnapshotAtOrBefore(..., now)}
     * 完全一致, 零回归风险。
     *
     * <p>设计意图: 让信号风控 / 入场漂移 / 流动性深度判定的 hot-path 不再等 45s polling 周期,
     * 减少 G6/G7/G9 在快速行情下的误判。WS 暂时只覆盖 orderbook + lastTradePrice, 其他维度
     * (tradeCount30s / buyVolume30s / realizedVol30s / timeToEndSeconds) 在合成 snapshot 中
     * 留空(下游消费方按 null 走 fallback 行为, 不会引入脏数据)。
     *
     * @return 最新可用 snapshot, 若 WS 与 DB 均不可用则返回 null
     */
    public SmartWalletMarketSnapshotEntity findFreshSnapshot(String marketId, String outcome) {
        if (!StringUtils.hasText(marketId)) {
            return null;
        }
        if (smartWalletProperties == null
                || smartWalletProperties.getPolymarket() == null
                || !smartWalletProperties.getPolymarket().isEnableWsHotPathSnapshot()) {
            // 开关关闭或配置缺失 — 旧行为 (DB polling 结果), 不计入 hot-path 计数器
            return findLatestSnapshotAtOrBefore(marketId, outcome, new Date());
        }
        SmartWalletMarketSnapshotEntity wsSnapshot = tryBuildHotPathSnapshotFromWs(marketId, outcome);
        if (wsSnapshot != null) {
            hotPathWsHits.increment();
            return wsSnapshot;
        }
        hotPathDbFallbacks.increment();
        return findLatestSnapshotAtOrBefore(marketId, outcome, new Date());
    }

    /**
     * 尝试从 WS 内存 snapshot 合成一个轻量 hot-path snapshot。
     * 失败 / stale / 元数据缺失全部返回 null, 让调用方走 DB fallback。
     */
    private SmartWalletMarketSnapshotEntity tryBuildHotPathSnapshotFromWs(String marketId, String outcome) {
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(marketId);
            if (metadata == null || metadata.getOutcomeTokenIds() == null) {
                return null;
            }
            String normalizedOutcome = normalizeOutcome(outcome);
            String tokenId = metadata.getOutcomeTokenIds().get(normalizedOutcome);
            if (!StringUtils.hasText(tokenId)) {
                return null;
            }
            PolymarketMarketDataStreamService.MarketDataSnapshot wsData = marketDataStreamService.getSnapshot(tokenId);
            if (wsData == null || wsData.getOrderBook() == null || wsData.getUpdatedAt() == null) {
                return null;
            }
            long maxAge = smartWalletProperties.getPolymarket().getWsHotPathSnapshotMaxAgeMs();
            if (maxAge > 0 && System.currentTimeMillis() - wsData.getUpdatedAt().getTime() > maxAge) {
                // WS 数据 stale — 视为不可用, 回落到 DB
                return null;
            }
            PolymarketApiClient.OrderBook orderBook = wsData.getOrderBook();
            BigDecimal mid = safe(orderBook.getMidpoint());
            if (mid.compareTo(BigDecimal.ZERO) <= 0) {
                mid = midpointFromTop(orderBook);
            }
            if (mid.compareTo(BigDecimal.ZERO) <= 0) {
                // orderbook 为空 — 视为不可用
                return null;
            }
            // 注意: 这是 *transient* entity, 不入库; 仅返回给 hot-path 调用者读 spread / depth / mid 等字段
            return new SmartWalletMarketSnapshotEntity()
                    .setMarketId(marketId.trim())
                    .setOutcome(normalizedOutcome)
                    .setSnapshotAt(wsData.getUpdatedAt())
                    .setMidPrice(mid)
                    .setBestBid(topPrice(orderBook.getBids()))
                    .setBestAsk(topPrice(orderBook.getAsks()))
                    .setSpreadBps(resolveSpreadBps(orderBook, mid))
                    .setBidDepth1c(depthWithin(orderBook.getBids(), mid, new BigDecimal("0.01")))
                    .setAskDepth1c(depthWithin(orderBook.getAsks(), mid, new BigDecimal("0.01")))
                    .setBidDepth3c(depthWithin(orderBook.getBids(), mid, new BigDecimal("0.03")))
                    .setAskDepth3c(depthWithin(orderBook.getAsks(), mid, new BigDecimal("0.03")))
                    .setBidDepth5c(depthWithin(orderBook.getBids(), mid, new BigDecimal("0.05")))
                    .setAskDepth5c(depthWithin(orderBook.getAsks(), mid, new BigDecimal("0.05")))
                    .setOrderImbalance(orderImbalance(orderBook, mid))
                    .setLastTradePrice(safe(wsData.getLastTradePrice()));
            // tradeCount30s / buyVolume30s / sellVolume30s / realizedVol30s / timeToEndSeconds 留空,
            // 这些指标 WS 没有 — 调用方读到 null 时走自己的 fallback 路径, 不会引入脏数据
        } catch (Exception ex) {
            // 任何异常 — 视为不可用, 静默回落, 不打 ERROR 日志避免 hot-path log spam
            log.debug("[F-P1-7] hot-path WS snapshot synthesis skipped marketId={} outcome={} reason={}",
                    marketId, outcome, ex.toString());
            return null;
        }
    }

    /** F-P1-7 hot-path 计数 — 用于运维观察 WS hit 率 / DB fallback 比例 */
    public long getHotPathWsHits() {
        return hotPathWsHits.sum();
    }

    public long getHotPathDbFallbacks() {
        return hotPathDbFallbacks.sum();
    }

    private String normalizeOutcome(String outcome) {
        if (!StringUtils.hasText(outcome)) {
            return "YES";
        }
        String normalized = outcome.trim().toUpperCase();
        if (normalized.startsWith("NO")) {
            return "NO";
        }
        if (normalized.startsWith("YES")) {
            return "YES";
        }
        return normalized;
    }

    private long recentEpochSeconds(int seconds) {
        return Math.max(0L, (System.currentTimeMillis() / 1000L) - seconds);
    }

    private BigDecimal topPrice(List<PolymarketApiClient.OrderBookLevel> levels) {
        return levels == null || levels.isEmpty() ? BigDecimal.ZERO : safe(levels.get(0).getPrice());
    }

    private BigDecimal midpointFromTop(PolymarketApiClient.OrderBook orderBook) {
        BigDecimal bestBid = topPrice(orderBook.getBids());
        BigDecimal bestAsk = topPrice(orderBook.getAsks());
        if (bestBid.compareTo(BigDecimal.ZERO) > 0 && bestAsk.compareTo(BigDecimal.ZERO) > 0) {
            return bestBid.add(bestAsk).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveSpreadBps(PolymarketApiClient.OrderBook orderBook, BigDecimal mid) {
        if (safe(orderBook.getSpread()).compareTo(BigDecimal.ZERO) > 0) {
            return orderBook.getSpread().setScale(8, RoundingMode.HALF_UP);
        }
        if (mid.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal bestBid = topPrice(orderBook.getBids());
        BigDecimal bestAsk = topPrice(orderBook.getAsks());
        if (bestBid.compareTo(BigDecimal.ZERO) <= 0 || bestAsk.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return bestAsk.subtract(bestBid)
                .divide(mid, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("10000"))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal depthWithin(List<PolymarketApiClient.OrderBookLevel> levels, BigDecimal mid, BigDecimal absoluteRange) {
        if (levels == null || levels.isEmpty() || mid.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (PolymarketApiClient.OrderBookLevel level : levels) {
            BigDecimal price = safe(level.getPrice());
            if (price.subtract(mid).abs().compareTo(absoluteRange) <= 0) {
                total = total.add(safe(level.getSize()));
            }
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal orderImbalance(PolymarketApiClient.OrderBook orderBook, BigDecimal mid) {
        BigDecimal bid = depthWithin(orderBook.getBids(), mid, new BigDecimal("0.01"));
        BigDecimal ask = depthWithin(orderBook.getAsks(), mid, new BigDecimal("0.01"));
        BigDecimal total = bid.add(ask);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return bid.subtract(ask).divide(total, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal latestTradePrice(List<PolymarketApiClient.MarketTrade> trades) {
        return trades == null || trades.isEmpty() ? BigDecimal.ZERO : safe(trades.get(0).getPrice());
    }

    private BigDecimal resolveLastTradePrice(PolymarketMarketDataStreamService.MarketDataSnapshot marketDataSnapshot,
                                             List<PolymarketApiClient.MarketTrade> trades) {
        BigDecimal wsPrice = marketDataSnapshot == null ? BigDecimal.ZERO : safe(marketDataSnapshot.getLastTradePrice());
        return wsPrice.compareTo(BigDecimal.ZERO) > 0 ? wsPrice : latestTradePrice(trades);
    }

    private BigDecimal latestTradeSize(List<PolymarketApiClient.MarketTrade> trades) {
        return trades == null || trades.isEmpty() ? BigDecimal.ZERO : safe(trades.get(0).getSize());
    }

    private int countTradesWithin(List<PolymarketApiClient.MarketTrade> trades, int seconds) {
        long threshold = System.currentTimeMillis() - seconds * 1000L;
        int count = 0;
        for (PolymarketApiClient.MarketTrade trade : trades) {
            if (trade.getTimestamp() != null && trade.getTimestamp() >= threshold) {
                count++;
            }
        }
        return count;
    }

    private BigDecimal volumeWithin(List<PolymarketApiClient.MarketTrade> trades, String side, int seconds) {
        long threshold = System.currentTimeMillis() - seconds * 1000L;
        BigDecimal volume = BigDecimal.ZERO;
        for (PolymarketApiClient.MarketTrade trade : trades) {
            if (trade.getTimestamp() == null || trade.getTimestamp() < threshold) {
                continue;
            }
            if (!side.equalsIgnoreCase(trade.getSide())) {
                continue;
            }
            volume = volume.add(safe(trade.getSize()));
        }
        return volume.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal realizedVolatility(List<PolymarketApiClient.MarketTrade> trades, int seconds) {
        long threshold = System.currentTimeMillis() - seconds * 1000L;
        BigDecimal sumSquared = BigDecimal.ZERO;
        BigDecimal previous = null;
        int observations = 0;
        for (int i = trades.size() - 1; i >= 0; i--) {
            PolymarketApiClient.MarketTrade trade = trades.get(i);
            if (trade.getTimestamp() == null || trade.getTimestamp() < threshold) {
                continue;
            }
            BigDecimal price = safe(trade.getPrice());
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (previous != null && previous.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ret = price.subtract(previous).divide(previous, 8, RoundingMode.HALF_UP);
                sumSquared = sumSquared.add(ret.multiply(ret));
                observations++;
            }
            previous = price;
        }
        if (observations == 0) {
            return BigDecimal.ZERO;
        }
        double value = Math.sqrt(sumSquared.doubleValue());
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }

    private Long timeToEndSeconds(Long endAtEpochMillis) {
        if (endAtEpochMillis == null || endAtEpochMillis <= 0) {
            return null;
        }
        return Math.max(0L, (endAtEpochMillis - System.currentTimeMillis()) / 1000L);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @lombok.Data
    @lombok.Builder
    public static class SnapshotCollectionStats {
        private long wsHits;
        private long restFallbacks;
    }
}
