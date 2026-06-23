package com.metaverse.coin.strategy.common.service.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Tier B (第十八批): 通用百分位算法.
 *
 * <p>跟单 W2-T1 流动性 P30 / 价差 P70 / 滑点 P95 等多处使用相同的线性插值百分位算法,
 * 抽出来防止天气 / BTC 5min 策略重新实现一遍 (不一致风险).</p>
 *
 * <p><b>语义</b>: 给定已 <b>升序排序</b> 的 BigDecimal 列表与 p ∈ [0, 1],
 * 返回 (p × (n-1)) 处线性插值结果. 与 numpy.percentile 默认的 'linear' 模式等价.</p>
 *
 * <p><b>边界</b>:
 * <ul>
 *   <li>null / 空列表 → 返回 {@link BigDecimal#ZERO}</li>
 *   <li>单元素 → 返回该元素</li>
 *   <li>p &lt; 0 或 p &gt; 1 → 钳到 [0, 1] 后插值</li>
 *   <li>结果保留 2 位小数 (HALF_UP)</li>
 * </ul></p>
 *
 * <p>无 Spring 依赖, 纯算法, 任意策略可直接调用.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 二十七节.</p>
 */
public final class Percentiles {

    private Percentiles() {
    }

    /**
     * 线性插值百分位.
     *
     * @param sortedAsc 已升序排序的样本列表
     * @param p         百分位 ∈ [0, 1]; 越界自动钳位
     * @return 插值结果 (保留 2 位小数); 空列表 / null 返回 0
     */
    public static BigDecimal linear(List<BigDecimal> sortedAsc, double p) {
        if (sortedAsc == null || sortedAsc.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (sortedAsc.size() == 1) {
            return sortedAsc.get(0);
        }
        double pp = Math.max(0.0, Math.min(1.0, p));
        double rank = pp * (sortedAsc.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sortedAsc.get(lower);
        }
        BigDecimal weight = BigDecimal.valueOf(rank - lower);
        return sortedAsc.get(lower)
                .add(sortedAsc.get(upper).subtract(sortedAsc.get(lower)).multiply(weight))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
