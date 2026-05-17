package com.metaverse.coin.strategy.common.service.stats;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * Tier B (第十八批): 通用「按类别聚合 + 分位」数据源接口.
 *
 * <p>把 W2-T1 跟单 {@code MarketLiquidityPercentileService} 与 {@code PredictionMarketOpportunityService}
 * 之间的耦合抽象出来. 任意策略实现本接口后, 即可复用 {@link CategoryPercentileAggregator} 算法.</p>
 *
 * <p>设计原则:
 * <ul>
 *   <li>策略只需提供三件事: 样本集合, 分类规则, 数值提取规则;</li>
 *   <li>不预设 caching / scheduling — 由调用方按各自策略需要决定 cache TTL;</li>
 *   <li>不预设 DB / 内存 — 跟单可走 PredictionMarketOpportunityService.list(), 天气可走自己的 weather snapshot.</li>
 * </ul></p>
 *
 * <p><b>使用示例</b> (跟单):
 * <pre>{@code
 * PercentileDataSource<PredictionMarketOpportunity> liquiditySource = new PercentileDataSource<>() {
 *   @Override public Collection<PredictionMarketOpportunity> samples() {
 *       return opportunityService.list();
 *   }
 *   @Override public String classify(PredictionMarketOpportunity opp) {
 *       String c = opp.getCategory();
 *       return c == null || c.isBlank() ? "OTHER" : c.toUpperCase();
 *   }
 *   @Override public BigDecimal value(PredictionMarketOpportunity opp) {
 *       return opp.getLiquidityUsdc();
 *   }
 * };
 * CategoryPercentileAggregator.Result r = CategoryPercentileAggregator.aggregate(liquiditySource);
 * }</pre></p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 二十七节.</p>
 *
 * @param <T> 样本类型 (跟单=PredictionMarketOpportunity, 天气=WeatherMarketSample, ...)
 */
public interface PercentileDataSource<T> {

    /**
     * 待聚合的样本集合. 实现方应控制规模 (cap 在 ~万级以内),
     * 调用方需在 hot-path 之外触发 (例: scheduled job / cache rebuild).
     */
    Collection<T> samples();

    /**
     * 把单个样本归类. 返回的字符串将作为聚合 key,
     * 实现方应保证「同一逻辑类别」总是返回同一字符串 (大小写敏感).
     * 不允许返回 null — 必须用 "OTHER" 之类显式哨兵.
     */
    String classify(T sample);

    /**
     * 提取用于百分位计算的数值. 返回 null / ≤ 0 时, 该样本会被跳过.
     */
    BigDecimal value(T sample);
}
