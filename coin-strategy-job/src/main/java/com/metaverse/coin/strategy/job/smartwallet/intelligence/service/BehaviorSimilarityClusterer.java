package com.metaverse.coin.strategy.job.smartwallet.intelligence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletEntityMapMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletEntityMapEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 行为相似度聚类器（Phase 2）
 *
 * 基于历史交易行为计算两个钱包之间的相似度：
 * 1. 市场重叠度（Jaccard）：交易市场集合的 Jaccard 相似系数（权重 50%）
 * 2. 交易时间分布相似度（余弦）：24小时交易时间直方图的余弦相似度（权重 30%）
 * 3. 仓位规模相似度：共同市场中的平均仓位大小相关性（权重 20%）
 *
 * 相似度 >= 0.75 → 判定为同一实体（MEDIUM 置信度 0.70）
 *
 * 最小数据要求：每个钱包至少 5 笔历史交易记录
 *
 * 性能优化：
 * - findMostSimilarEntityId 预先提取目标钱包特征，避免在循环内重复加载数据库记录
 * - buildHourHistogram 使用位运算代替 Calendar/TimeZone 实例创建，减少对象分配
 * - computeSimilarity(features, trades) 内部路径复用预计算特征
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorSimilarityClusterer {

    private static final int MIN_TRADES_REQUIRED = 5;
    private static final BigDecimal BEHAVIOR_SIM_CONFIDENCE = new BigDecimal("0.70");

    // 相似度权重
    private static final double WEIGHT_MARKET_OVERLAP = 0.50;
    private static final double WEIGHT_TIME_DIST = 0.30;
    private static final double WEIGHT_SIZE_CORR = 0.20;

    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final WalletEntityMapMapper entityMapMapper;
    private final SmartWalletProperties properties;

    /**
     * 计算两个钱包的行为相似度
     *
     * @return 相似度分值 0-1，数据不足时返回 0
     */
    public BigDecimal computeSimilarity(String walletA, String walletB) {
        if (!StringUtils.hasText(walletA) || !StringUtils.hasText(walletB)
                || walletA.equalsIgnoreCase(walletB)) {
            return BigDecimal.ZERO;
        }

        List<SmartWalletTradeEventEntity> tradesA = loadTrades(walletA);
        List<SmartWalletTradeEventEntity> tradesB = loadTrades(walletB);

        if (tradesA.size() < MIN_TRADES_REQUIRED || tradesB.size() < MIN_TRADES_REQUIRED) {
            return BigDecimal.ZERO;
        }

        WalletFeatures fa = extractFeatures(tradesA);
        WalletFeatures fb = extractFeatures(tradesB);
        return computeSimilarityFromFeatures(fa, fb);
    }

    /**
     * 在 wallet_entity_map 中的现有实体里，找到与给定钱包最相似的实体
     *
     * 优化：目标钱包特征（市场集合、时间直方图、平均仓位）只提取一次，
     * 不在每次比较中重复加载数据库并重新计算。
     *
     * @param walletAddress 待聚类的钱包
     * @return 相似实体ID（相似度 >= threshold 时），否则 empty
     */
    public Optional<String> findMostSimilarEntityId(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return Optional.empty();
        }
        String normalized = walletAddress.toLowerCase().trim();

        List<SmartWalletTradeEventEntity> myTrades = loadTrades(normalized);
        if (myTrades.size() < MIN_TRADES_REQUIRED) {
            return Optional.empty();
        }

        // 预先提取目标钱包特征（整个 for 循环内复用，不再重复查库）
        WalletFeatures myFeatures = extractFeatures(myTrades);

        // 加载所有已有实体的代表钱包（每个实体取一个主钱包对比即可）
        List<WalletEntityMapEntity> existingPrimaries = entityMapMapper.selectList(
                new LambdaQueryWrapper<WalletEntityMapEntity>()
                        .eq(WalletEntityMapEntity::getIsPrimaryWallet, true)
                        .ne(WalletEntityMapEntity::getWalletAddress, normalized));

        String bestEntityId = null;
        double bestScore = similarityThreshold();

        for (WalletEntityMapEntity primary : existingPrimaries) {
            try {
                List<SmartWalletTradeEventEntity> primaryTrades = loadTrades(primary.getWalletAddress());
                if (primaryTrades.size() < MIN_TRADES_REQUIRED) {
                    continue;
                }
                WalletFeatures primaryFeatures = extractFeatures(primaryTrades);
                BigDecimal sim = computeSimilarityFromFeatures(myFeatures, primaryFeatures);
                if (sim.doubleValue() > bestScore) {
                    bestScore = sim.doubleValue();
                    bestEntityId = primary.getEntityId();
                }
            } catch (Exception e) {
                log.debug("[BehaviorSimilarityClusterer] Error computing similarity wallet={} vs {}: {}",
                        normalized, primary.getWalletAddress(), e.getMessage());
            }
        }

        if (bestEntityId != null) {
            log.info("[BehaviorSimilarityClusterer] Found similar entity for wallet={}: entityId={} score={}",
                    normalized, bestEntityId, bestScore);
        }

        return Optional.ofNullable(bestEntityId);
    }

    /**
     * 返回行为相似度聚类的置信度（固定值，用于写入 wallet_entity_map）
     */
    public BigDecimal getClusterConfidence() {
        return BEHAVIOR_SIM_CONFIDENCE;
    }

    public double similarityThreshold() {
        Double configured = properties.getIntelligence().getBehaviorSimilarityThreshold();
        if (configured == null) {
            return 0.75D;
        }
        return Math.max(0D, Math.min(1D, configured));
    }

    // ---- 特征提取 ----

    /**
     * 预计算钱包特征，供多次比较复用（避免重复遍历交易列表）
     */
    private WalletFeatures extractFeatures(List<SmartWalletTradeEventEntity> trades) {
        Set<String> markets = trades.stream()
                .map(SmartWalletTradeEventEntity::getMarketId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        double[] hourHistogram = buildHourHistogram(trades);

        Map<String, Double> avgSizeByMarket = trades.stream()
                .filter(t -> t.getMarketId() != null && t.getSizeUsdc() != null)
                .collect(Collectors.groupingBy(
                        SmartWalletTradeEventEntity::getMarketId,
                        Collectors.averagingDouble(t -> t.getSizeUsdc().doubleValue())));

        return new WalletFeatures(markets, hourHistogram, avgSizeByMarket);
    }

    /**
     * 基于预提取特征计算相似度（避免重复读取数据库）
     */
    private BigDecimal computeSimilarityFromFeatures(WalletFeatures fa, WalletFeatures fb) {
        double marketOverlap = jaccardFromSets(fa.markets, fb.markets);
        double timeSim = cosineSimilarity(fa.hourHistogram, fb.hourHistogram);
        double sizeSim = sizeSimilarityFromMaps(fa.avgSizeByMarket, fb.avgSizeByMarket);

        double combined = WEIGHT_MARKET_OVERLAP * marketOverlap
                + WEIGHT_TIME_DIST * timeSim
                + WEIGHT_SIZE_CORR * sizeSim;

        return BigDecimal.valueOf(combined).setScale(4, RoundingMode.HALF_UP);
    }

    // ---- 私有计算方法 ----

    private List<SmartWalletTradeEventEntity> loadTrades(String walletAddress) {
        return tradeEventMapper.selectList(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getWalletAddress, walletAddress)
                .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                .last("limit 200"));
    }

    /**
     * 市场 Jaccard 相似度：|A∩B| / |A∪B|
     */
    private double jaccardFromSets(Set<String> marketsA, Set<String> marketsB) {
        if (marketsA.isEmpty() || marketsB.isEmpty()) {
            return 0.0;
        }
        long intersection = marketsA.stream().filter(marketsB::contains).count();
        long union = marketsA.size() + marketsB.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /**
     * 24小时交易时间分布直方图
     * 使用 epoch ms 位运算代替 Calendar/TimeZone 实例，减少对象分配
     */
    private double[] buildHourHistogram(List<SmartWalletTradeEventEntity> trades) {
        double[] hist = new double[24];
        for (SmartWalletTradeEventEntity trade : trades) {
            if (trade.getOpenedAt() != null) {
                // UTC hour：epoch ms → 小时数 → 取模 24
                int hour = (int) ((trade.getOpenedAt().getTime() / 3_600_000L) % 24);
                hist[hour]++;
            }
        }
        return hist;
    }

    private double cosineSimilarity(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }

    /**
     * 仓位规模相似度：比较共同市场中两个钱包的平均仓位大小
     * 使用 1 - |sizeA - sizeB| / max(sizeA, sizeB) 计算相似度
     */
    private double sizeSimilarityFromMaps(Map<String, Double> avgSizeA, Map<String, Double> avgSizeB) {
        Set<String> commonMarkets = new HashSet<>(avgSizeA.keySet());
        commonMarkets.retainAll(avgSizeB.keySet());

        if (commonMarkets.isEmpty()) {
            return 0.0;
        }

        double totalSim = 0.0;
        for (String market : commonMarkets) {
            double sA = avgSizeA.get(market);
            double sB = avgSizeB.get(market);
            double maxSize = Math.max(sA, sB);
            if (maxSize > 0) {
                totalSim += 1.0 - Math.abs(sA - sB) / maxSize;
            } else {
                totalSim += 1.0;
            }
        }

        return totalSim / commonMarkets.size();
    }

    // ---- 内部数据结构 ----

    /** 钱包预计算特征，供多次相似度比较复用 */
    private static class WalletFeatures {
        final Set<String> markets;
        final double[] hourHistogram;
        final Map<String, Double> avgSizeByMarket;

        WalletFeatures(Set<String> markets, double[] hourHistogram, Map<String, Double> avgSizeByMarket) {
            this.markets = markets;
            this.hourHistogram = hourHistogram;
            this.avgSizeByMarket = avgSizeByMarket;
        }
    }
}
