package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketSnapshotMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
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
    private final LongAdder wsSnapshotHits = new LongAdder();
    private final LongAdder restSnapshotFallbacks = new LongAdder();

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
        List<PolymarketApiClient.MarketTrade> trades = polymarketApiClient.fetchMarketTrades(conditionId, 50, recentEpochSeconds(120));
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
