package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.PmMarketLiquidityPercentileDailyMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmMarketLiquidityPercentileDailyEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.service.stats.CategoryPercentileAggregator;
import com.metaverse.coin.strategy.common.service.stats.PercentileDataSource;
import com.metaverse.coin.strategy.common.service.stats.Percentiles;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * W2-T1.2 流动性分位日级快照 service.
 *
 * <p>每日由 {@link MarketLiquidityPercentileSnapshotJob} 触发一次, 按 normalizedCategory
 * 分桶, 计算 P10 / P30 / P50 / P70 / mean / max, 写入 {@code pm_market_liquidity_percentile_daily}
 * (按 (category, asOfDate) upsert).</p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响</b>: 纯写入 + 内存聚合,
 * 不参与 signal/order 决策链路. 是 W2-T1 启用门控的真历史数据基础, 配合
 * {@link MarketLiquidityPercentileService} 优先读表逻辑使用.</p>
 *
 * <p><b>第二十批 (2026-05-16 自动化追加 · 第二十批) 重构</b>: 流动性分位算法委托到
 * common-service {@link CategoryPercentileAggregator} (Tier B), 与
 * {@link MarketLiquidityPercentileService#rebuild} 第十九批委托同一算法源, 避免双实现 drift;
 * 价差 P70 单独走 {@link Percentiles#linear} (因为是逐市场 latest spread → 按 category 分桶,
 * 不符合 PercentileDataSource 的「单遍 samples()」语义)。mean / max 在 Aggregator 之外
 * 单独 reduce 一次。</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十节 (Tier B).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLiquidityPercentileSnapshotService {

    private static final String UNCATEGORIZED = "OTHER";

    private final PredictionMarketOpportunityService opportunityService;
    private final PmMarketLiquidityPercentileDailyMapper dailyMapper;
    /** W2-T1.4: 取每个市场最近一笔 market_snapshot 的 spreadBps, 按 category 算 P70. */
    private final SmartWalletMarketSnapshotMapper marketSnapshotMapper;

    /** Job 入口. 调度异常由 Job 类捕获. */
    public SnapshotResult runScheduled() {
        return buildAndPersist(LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }

    /** 直调入口 (运维 / 调试). */
    public SnapshotResult buildAndPersist(LocalDate asOfDate) {
        Date now = new Date();

        // 1) 拉一遍 opportunity → 收集 marketId→category 映射 (供后续 spread 聚合用)
        Collection<PredictionMarketOpportunity> opportunities = opportunityService.list();
        Map<String, String> marketIdToCategory = new HashMap<>();
        Map<String, List<BigDecimal>> liquiditiesByCategory = new LinkedHashMap<>();
        for (PredictionMarketOpportunity opp : opportunities) {
            if (opp == null || opp.getLiquidityUsdc() == null
                    || opp.getLiquidityUsdc().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String key = normalize(opp.getCategory());
            liquiditiesByCategory.computeIfAbsent(key, k -> new ArrayList<>()).add(opp.getLiquidityUsdc());
            if (opp.getMarketId() != null && !marketIdToCategory.containsKey(opp.getMarketId())) {
                marketIdToCategory.put(opp.getMarketId(), key);
            }
        }

        // 2) 第二十批: P10/P30/P50/P70 委托到 Tier B CategoryPercentileAggregator,
        //    与 MarketLiquidityPercentileService.rebuild 算法 bit-equivalent.
        SnapshotPercentileDataSource source = new SnapshotPercentileDataSource(opportunities);
        CategoryPercentileAggregator.Result aggregated = CategoryPercentileAggregator.aggregate(source);

        // 3) W2-T1.4: 按 category 聚合 spreadBps P70 (取每个市场最近一笔 snapshot)
        Map<String, BigDecimal> spreadP70ByCategory = computeSpreadP70ByCategory(marketIdToCategory);

        // 4) 落表: 用 aggregator 的 P10/P30/P50/P70 + 本地算的 mean/max + spread P70
        int upsertOk = 0;
        int upsertFail = 0;
        for (Map.Entry<String, CategoryPercentileAggregator.CategoryStats> entry : aggregated.byCategory.entrySet()) {
            String category = entry.getKey();
            CategoryPercentileAggregator.CategoryStats agg = entry.getValue();
            List<BigDecimal> liquidities = liquiditiesByCategory.get(category);
            try {
                BigDecimal mean = computeMean(liquidities);
                BigDecimal max = computeMax(liquidities);
                PmMarketLiquidityPercentileDailyEntity row = new PmMarketLiquidityPercentileDailyEntity()
                        .setCategory(category)
                        .setAsOfDate(asOfDate)
                        .setSampleCount(agg.sampleCount)
                        .setLiquidityP10(agg.p10)
                        .setLiquidityP30(agg.p30)
                        .setLiquidityP50(agg.p50)
                        .setLiquidityP70(agg.p70)
                        .setLiquidityMean(mean)
                        .setLiquidityMax(max)
                        .setSpreadBpsP70(spreadP70ByCategory.get(category))
                        .setComputedAt(now)
                        .setUpdatedAt(now);
                upsert(row, now);
                upsertOk++;
            } catch (RuntimeException ex) {
                upsertFail++;
                log.warn("[MarketLiquidityPercentileSnapshot] upsert failed category={} reason={}",
                        category, ex.toString());
            }
        }

        log.info("[MarketLiquidityPercentileSnapshot] asOfDate={} categories={} ok={} fail={} spreadCats={} viaTierB=true",
                asOfDate, aggregated.byCategory.size(), upsertOk, upsertFail, spreadP70ByCategory.size());
        return new SnapshotResult(asOfDate, aggregated.byCategory.size(), upsertOk, upsertFail);
    }

    /**
     * W2-T1.4 价差分位计算: 取每个 marketId 最近一笔 snapshot 的 spreadBps,
     * 按 category 分桶, 算 P70. 失败 / 缺数据返回空 map.
     *
     * <p>第二十批: 单 percentile 调用走 {@link Percentiles#linear} 直接拿,
     * 而不是再造一遍 Aggregator (因为 latest spread per market 不符合 samples() 单遍语义)。</p>
     */
    private Map<String, BigDecimal> computeSpreadP70ByCategory(Map<String, String> marketIdToCategory) {
        if (marketIdToCategory.isEmpty()) return Collections.emptyMap();
        try {
            List<String> marketIds = new ArrayList<>(marketIdToCategory.keySet());
            // batch query 最近 N 条 snapshot, 按 snapshotAt desc 排, 后续按 marketId 取首条
            List<SmartWalletMarketSnapshotEntity> snapshots = marketSnapshotMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletMarketSnapshotEntity>()
                            .in(SmartWalletMarketSnapshotEntity::getMarketId, marketIds)
                            .isNotNull(SmartWalletMarketSnapshotEntity::getSpreadBps)
                            .orderByDesc(SmartWalletMarketSnapshotEntity::getSnapshotAt));
            // 每个 marketId 仅保留最新一笔
            Map<String, BigDecimal> latestSpreadByMarket = new HashMap<>();
            for (SmartWalletMarketSnapshotEntity s : snapshots) {
                if (s.getMarketId() == null || s.getSpreadBps() == null) continue;
                latestSpreadByMarket.putIfAbsent(s.getMarketId(), s.getSpreadBps());
            }
            // 按 category 分桶
            Map<String, List<BigDecimal>> spreadByCat = new HashMap<>();
            for (Map.Entry<String, BigDecimal> e : latestSpreadByMarket.entrySet()) {
                String cat = marketIdToCategory.get(e.getKey());
                if (cat == null) continue;
                spreadByCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(e.getValue());
            }
            // 算 P70 (委托 Percentiles.linear, 与 CategoryPercentileAggregator 同一算法源)
            return spreadByCat.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> {
                                List<BigDecimal> sorted = e.getValue().stream()
                                        .filter(Objects::nonNull).sorted().collect(Collectors.toList());
                                return sorted.isEmpty() ? null : Percentiles.linear(sorted, 0.70);
                            }));
        } catch (RuntimeException ex) {
            log.warn("[MarketLiquidityPercentileSnapshot] spread P70 compute failed: {}", ex.toString());
            return Collections.emptyMap();
        }
    }

    private void upsert(PmMarketLiquidityPercentileDailyEntity row, Date now) {
        PmMarketLiquidityPercentileDailyEntity existing = dailyMapper.selectOne(
                new LambdaQueryWrapper<PmMarketLiquidityPercentileDailyEntity>()
                        .eq(PmMarketLiquidityPercentileDailyEntity::getCategory, row.getCategory())
                        .eq(PmMarketLiquidityPercentileDailyEntity::getAsOfDate, row.getAsOfDate())
                        .last("limit 1"));
        if (existing == null) {
            row.setCreatedAt(now);
            dailyMapper.insert(row);
        } else {
            row.setId(existing.getId()).setCreatedAt(existing.getCreatedAt());
            dailyMapper.updateById(row);
        }
    }

    private BigDecimal computeMean(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal computeMax(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return BigDecimal.ZERO;
        BigDecimal max = values.get(0);
        for (BigDecimal v : values) {
            if (v != null && v.compareTo(max) > 0) max = v;
        }
        return max;
    }

    private String normalize(String category) {
        if (category == null || category.isBlank()) return UNCATEGORIZED;
        return category.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 内部 adapter: 把 opportunity stream 喂进通用聚合器.
     * 与 {@link MarketLiquidityPercentileService} 的内部 adapter 等价, 但因 PrivateAdapter
     * 语义就近, 不走跨类共享 — 保持「common-service 抽算法 / job 提 adapter」职责边界。
     */
    private final class SnapshotPercentileDataSource implements PercentileDataSource<PredictionMarketOpportunity> {
        private final Collection<PredictionMarketOpportunity> samples;

        private SnapshotPercentileDataSource(Collection<PredictionMarketOpportunity> samples) {
            this.samples = samples;
        }

        @Override
        public Collection<PredictionMarketOpportunity> samples() {
            return samples;
        }

        @Override
        public String classify(PredictionMarketOpportunity sample) {
            return normalize(sample.getCategory());
        }

        @Override
        public BigDecimal value(PredictionMarketOpportunity sample) {
            return sample.getLiquidityUsdc();
        }
    }

    public record SnapshotResult(LocalDate asOfDate, int categories, int upsertOk, int upsertFail) {
    }
}
