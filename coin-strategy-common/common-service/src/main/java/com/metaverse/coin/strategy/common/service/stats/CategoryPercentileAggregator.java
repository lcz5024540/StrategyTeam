package com.metaverse.coin.strategy.common.service.stats;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier B (第十八批): 通用「按类别聚合 + 多分位」算法.
 *
 * <p>读 {@link PercentileDataSource} 一次性算出每个 category 的样本数 + P10/P30/P50/P70.
 * 跟单 {@code MarketLiquidityPercentileService} 内联的 rebuild 算法迁移到这里,
 * 天气 / BTC 5min 策略可直接调用.</p>
 *
 * <p><b>纯算法 + 无 Spring 依赖</b>. 由调用方决定何时触发与缓存策略.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 二十七节.</p>
 */
public final class CategoryPercentileAggregator {

    private CategoryPercentileAggregator() {
    }

    /**
     * 单 category 的统计快照.
     */
    public static final class CategoryStats {
        public final String category;
        public final int sampleCount;
        public final BigDecimal p10;
        public final BigDecimal p30;
        public final BigDecimal p50;
        public final BigDecimal p70;

        public CategoryStats(String category, int sampleCount,
                             BigDecimal p10, BigDecimal p30,
                             BigDecimal p50, BigDecimal p70) {
            this.category = category;
            this.sampleCount = sampleCount;
            this.p10 = p10;
            this.p30 = p30;
            this.p50 = p50;
            this.p70 = p70;
        }
    }

    /**
     * 聚合结果. {@code byCategory} 保持插入顺序 (LinkedHashMap).
     */
    public static final class Result {
        public final int totalSamples;
        public final Map<String, CategoryStats> byCategory;

        Result(int totalSamples, Map<String, CategoryStats> byCategory) {
            this.totalSamples = totalSamples;
            this.byCategory = Collections.unmodifiableMap(byCategory);
        }
    }

    /**
     * 主入口: 读 source → 分桶 → 排序 → 算 P10/P30/P50/P70.
     *
     * <p>null / 空样本 / value ≤ 0 自动跳过. 异常向上抛, 由调用方决定如何处理.</p>
     */
    public static <T> Result aggregate(PercentileDataSource<T> source) {
        if (source == null) {
            return new Result(0, new LinkedHashMap<>());
        }
        Collection<T> samples = source.samples();
        Map<String, List<BigDecimal>> bucket = new LinkedHashMap<>();
        int total = 0;
        if (samples != null) {
            for (T s : samples) {
                if (s == null) continue;
                BigDecimal v = source.value(s);
                if (v == null || v.signum() <= 0) continue;
                String key = source.classify(s);
                if (key == null) continue;
                bucket.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
                total++;
            }
        }
        Map<String, CategoryStats> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<BigDecimal>> e : bucket.entrySet()) {
            List<BigDecimal> sorted = new ArrayList<>(e.getValue());
            Collections.sort(sorted);
            out.put(e.getKey(), new CategoryStats(
                    e.getKey(),
                    sorted.size(),
                    Percentiles.linear(sorted, 0.10),
                    Percentiles.linear(sorted, 0.30),
                    Percentiles.linear(sorted, 0.50),
                    Percentiles.linear(sorted, 0.70)));
        }
        return new Result(total, out);
    }
}
