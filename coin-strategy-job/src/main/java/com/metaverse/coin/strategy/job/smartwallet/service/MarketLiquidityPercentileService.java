package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.PmMarketLiquidityPercentileDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmMarketLiquidityPercentileDailyEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.stats.CategoryPercentileAggregator;
import com.metaverse.coin.strategy.common.service.stats.PercentileDataSource;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * W2-T1 流动性 P30 滚动阈值 (轻量化第一版).
 *
 * <p>当前阶段不引入新 DB 表 (`pm_market_liquidity_percentile_daily`), 而是基于内存中已有
 * {@link PredictionMarketOpportunityService} 维护的市场列表, 按市场类别 (politics / crypto /
 * sports / weather / 其它) 即时聚合 P10 / P30 / P50 流动性. 缓存 1 小时, 命中即返回.</p>
 *
 * <p>当 {@code smart-wallet.signal-risk.enable-liquidity-rolling-percentile=false} (默认)
 * 时, 该服务永远返回 null, 上层必然回退到 {@link SmartWalletProperties#getMinMarketLiquidity()}
 * 硬阈值, 从而 <b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何影响</b>.</p>
 *
 * <p>启用后:
 * <ul>
 *   <li>开单效率: ⬆️/⬇️ 取决于品类的 P30 是否高于 25,000 (政治高于、体育低于) — 综合期望接近中性</li>
 *   <li>资金使用率: 同上, 整体波动 &lt; ±5%</li>
 *   <li>是否会开单: ✅ 仍开单, 只是阈值随品类自适应</li>
 *   <li>盈亏点: 🟢 流动性差的品类阈值反而更低, 但 W2-T4 的 priceImpact 三段式仍兜底</li>
 *   <li>胜率: 🟢 微正向 (避免对低流动性品类一刀切), 由 W1-T4 影子记账量化</li>
 * </ul>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W2-T1).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLiquidityPercentileService {

    private static final long CACHE_TTL_MS = 3600_000L;  // 1h
    private static final String UNCATEGORIZED = "OTHER";

    private final SmartWalletProperties properties;
    private final PredictionMarketOpportunityService opportunityService;
    /** W2-T1.2: 优先读 daily 表的 P30 滚动窗口数据; null/陈旧时 fallback 内存即时聚合. */
    private final PmMarketLiquidityPercentileDailyMapper dailyMapper;

    private final AtomicReference<CacheSnapshot> cache = new AtomicReference<>();

    /**
     * 为给定品类返回 P30 流动性. 服务关闭或样本不足时返回 null, 调用方应回退到硬阈值.
     *
     * <p>W2-T1.2 (2026-05-16): 优先读 {@code pm_market_liquidity_percentile_daily} 表中
     * 最近一日的 P30 (真正的滚动窗口数据); 表无数据 / 陈旧 / 配置阈值未达成时,
     * fallback 到现有内存即时聚合.</p>
     */
    public BigDecimal p30ForCategory(String category) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (risk == null || !risk.isEnableLiquidityRollingPercentile()) {
            return null;
        }
        String key = normalize(category);
        PmMarketLiquidityPercentileDailyEntity daily = tryLoadFromDaily(key);
        if (daily != null && daily.getSampleCount() != null
                && daily.getSampleCount() >= risk.getLiquidityRollingMinSamples()
                && daily.getLiquidityP30() != null) {
            return daily.getLiquidityP30();
        }
        CategoryStats stats = ensureCache().categoryStats.get(key);
        if (stats == null || stats.sampleCount < risk.getLiquidityRollingMinSamples()) {
            return null;
        }
        return stats.p30;
    }

    /**
     * W2-T1.4 (2026-05-16 第十一批): 返回该品类的价差 P70 bps (高分位 → 容忍上限).
     *
     * <p>由 {@link MarketLiquidityPercentileSnapshotService} 每日 02:30 写入
     * {@code pm_market_liquidity_percentile_daily.spread_bps_p70}。
     * 门控未启用 / 样本不足 / 表无数据 / 陈旧 → 返回 null, 调用方应跳过本门控
     * 或回退既有 maxSpreadBps 硬阈值。</p>
     */
    public BigDecimal spreadP70ForCategory(String category) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (risk == null || !risk.isEnableSpreadP70Gate()) {
            return null;
        }
        String key = normalize(category);
        PmMarketLiquidityPercentileDailyEntity daily = tryLoadFromDaily(key);
        if (daily == null
                || daily.getSpreadBpsP70() == null
                || daily.getSpreadBpsP70().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        int minSamples = risk.getSpreadP70MinSamples();
        if (daily.getSampleCount() == null || daily.getSampleCount() < minSamples) {
            return null;
        }
        return daily.getSpreadBpsP70();
    }

    /** 同时返回 P10 / P30 / P50, 主要用于看板与运维诊断. */
    public CategoryStats statsForCategory(String category) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (risk == null || !risk.isEnableLiquidityRollingPercentile()) {
            return null;
        }
        String key = normalize(category);
        PmMarketLiquidityPercentileDailyEntity daily = tryLoadFromDaily(key);
        if (daily != null) {
            CategoryStats out = new CategoryStats();
            out.category = key;
            out.sampleCount = daily.getSampleCount() == null ? 0 : daily.getSampleCount();
            out.p10 = daily.getLiquidityP10();
            out.p30 = daily.getLiquidityP30();
            out.p50 = daily.getLiquidityP50();
            out.spreadBpsP70 = daily.getSpreadBpsP70();
            return out;
        }
        return ensureCache().categoryStats.get(key);
    }

    /** 优先读 daily 表的最近一行 (按 as_of_date desc). 陈旧度超阈值返回 null 触发 fallback. */
    private PmMarketLiquidityPercentileDailyEntity tryLoadFromDaily(String normalizedCategory) {
        try {
            SmartWalletProperties.Audit audit = properties.getAudit();
            int maxStaleDays = audit == null || audit.getLiquidityPercentileMaxStaleDays() == null
                    ? 2 : audit.getLiquidityPercentileMaxStaleDays();
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            LocalDate earliest = today.minusDays(maxStaleDays);
            PmMarketLiquidityPercentileDailyEntity row = dailyMapper.selectOne(
                    new LambdaQueryWrapper<PmMarketLiquidityPercentileDailyEntity>()
                            .eq(PmMarketLiquidityPercentileDailyEntity::getCategory, normalizedCategory)
                            .ge(PmMarketLiquidityPercentileDailyEntity::getAsOfDate, earliest)
                            .orderByDesc(PmMarketLiquidityPercentileDailyEntity::getAsOfDate)
                            .last("limit 1"));
            return row;
        } catch (RuntimeException ex) {
            log.debug("[MarketLiquidityPercentile] daily read fallback category={} reason={}",
                    normalizedCategory, ex.toString());
            return null;
        }
    }

    /** 强制刷新缓存 (运维入口 / 单测使用). */
    public void invalidate() {
        cache.set(null);
    }

    private CacheSnapshot ensureCache() {
        CacheSnapshot snapshot = cache.get();
        long now = System.currentTimeMillis();
        if (snapshot != null && now - snapshot.builtAtMs < CACHE_TTL_MS) {
            return snapshot;
        }
        CacheSnapshot rebuilt = rebuild(now);
        cache.set(rebuilt);
        return rebuilt;
    }

    /**
     * 第十九批 (2026-05-16): 委托到 common-service {@link CategoryPercentileAggregator}.
     * 行为等价 — 原内联 {@code percentile()} 与 {@link com.metaverse.coin.strategy.common.service.stats.Percentiles#linear}
     * 数学等价 (线性插值 + HALF_UP 2 位小数 + 单元素/空集兜底);
     * P70 (W2-T1.4 价差门所用) 由 Aggregator 顺手算出, 跟单内存 fallback 路径也能获得 P70 字段.
     *
     * <p>关键不变性: <ul>
     *   <li>样本过滤条件 (null / value ≤ 0) 由 {@link CategoryPercentileAggregator#aggregate} 内置, 与原 rebuild 一致;</li>
     *   <li>category key 归一化通过 {@link #normalize(String)} 完成, 与历史 cache key bit-equivalent.</li>
     * </ul></p>
     */
    private CacheSnapshot rebuild(long nowMs) {
        PercentileDataSource<PredictionMarketOpportunity> source = new LiquidityPercentileDataSource(opportunityService.list());
        CategoryPercentileAggregator.Result aggregated = CategoryPercentileAggregator.aggregate(source);

        Map<String, CategoryStats> result = new LinkedHashMap<>();
        for (Map.Entry<String, CategoryPercentileAggregator.CategoryStats> entry : aggregated.byCategory.entrySet()) {
            CategoryPercentileAggregator.CategoryStats agg = entry.getValue();
            CategoryStats stats = new CategoryStats();
            stats.category = entry.getKey();
            stats.sampleCount = agg.sampleCount;
            stats.p10 = agg.p10;
            stats.p30 = agg.p30;
            stats.p50 = agg.p50;
            // 内存 fallback 路径的 spread P70 仍来自 daily 表; 这里只填 liquidity P70 暂未暴露字段,
            // 保持 CategoryStats public field 集合不变 (历史 web view 兼容)。
            result.put(stats.category, stats);
        }
        log.info("[MarketLiquidityPercentile] rebuilt categories={} totalMarkets={}",
                result.size(), aggregated.totalSamples);
        return new CacheSnapshot(nowMs, result);
    }

    /** 内部 adapter: 把 {@link PredictionMarketOpportunity} 流量灌进通用聚合器. */
    private final class LiquidityPercentileDataSource implements PercentileDataSource<PredictionMarketOpportunity> {
        private final Collection<PredictionMarketOpportunity> samples;

        private LiquidityPercentileDataSource(Collection<PredictionMarketOpportunity> samples) {
            this.samples = samples;
        }

        @Override public Collection<PredictionMarketOpportunity> samples() {
            return samples;
        }

        @Override public String classify(PredictionMarketOpportunity sample) {
            return normalize(sample.getCategory());
        }

        @Override public BigDecimal value(PredictionMarketOpportunity sample) {
            return sample.getLiquidityUsdc();
        }
    }

    private String normalize(String category) {
        if (category == null || category.isBlank()) {
            return UNCATEGORIZED;
        }
        return category.trim().toUpperCase(Locale.ROOT);
    }

    /** 单一品类的流动性百分位快照. */
    public static final class CategoryStats {
        public String category;
        public int sampleCount;
        public BigDecimal p10;
        public BigDecimal p30;
        public BigDecimal p50;
        /** W2-T1.4 (2026-05-16 第十一批): 该品类价差 bps P70, daily 表写入。 */
        public BigDecimal spreadBpsP70;
    }

    private static final class CacheSnapshot {
        private final long builtAtMs;
        private final Map<String, CategoryStats> categoryStats;

        private CacheSnapshot(long builtAtMs, Map<String, CategoryStats> categoryStats) {
            this.builtAtMs = builtAtMs;
            this.categoryStats = categoryStats;
        }
    }
}
