package com.metaverse.coin.strategy.common.service.behavior;

/**
 * Tier B (第十五批): 基于 centroid + cosine 距离的通用行为漂移检测器.
 *
 * <p>跟单 W2-T6 / 天气 / BTC 5min 共用同一套漂移判定逻辑, 只是 vectorizer 不同.
 * 与 LeaderBehaviorDriftService 算法等价 — cosine 距离 ∈ [0, 2], 三态:
 * <ul>
 *   <li>{@code distance < threshold}                   → {@link Status#ACTIVE}</li>
 *   <li>{@code threshold ≤ distance < severeThreshold} → {@link Status#DRIFTED}</li>
 *   <li>{@code distance ≥ severeThreshold}             → {@link Status#DRIFTED_SEVERE}</li>
 * </ul></p>
 *
 * <p>无 Spring 依赖, 纯算法, 任意策略实例化即用. 阈值由调用方传入.</p>
 *
 * <p>详见 docs/polymarket/cross-strategy-reusable-infrastructure.md 二.1 Tier B.</p>
 */
public final class CentroidDriftDetector {

    private CentroidDriftDetector() {
    }

    /** 检测结果三态. */
    public enum Status { ACTIVE, DRIFTED, DRIFTED_SEVERE }

    /**
     * @return cosine 距离 ∈ [0, 2]; 任一向量为全零或长度不等返回 NaN
     */
    public static double cosineDistance(double[] centroid, double[] current) {
        if (centroid == null || current == null || centroid.length != current.length) {
            return Double.NaN;
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < centroid.length; i++) {
            dot += centroid[i] * current[i];
            na += centroid[i] * centroid[i];
            nb += current[i] * current[i];
        }
        if (na == 0.0 || nb == 0.0) return Double.NaN;
        double cos = dot / (Math.sqrt(na) * Math.sqrt(nb));
        // 数值精度修正
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return 1.0 - cos;
    }

    /**
     * 判定状态. distance 为 NaN/Infinity 时按 ACTIVE 处理 (放行 + 上层 cold-start 兜底).
     *
     * @param distance        cosineDistance() 输出
     * @param threshold       触发 DRIFTED 的下限 (建议 0.40)
     * @param severeThreshold 触发 DRIFTED_SEVERE 的下限 (建议 0.60)
     */
    public static Status classify(double distance, double threshold, double severeThreshold) {
        if (Double.isNaN(distance) || Double.isInfinite(distance)) return Status.ACTIVE;
        if (distance >= severeThreshold) return Status.DRIFTED_SEVERE;
        if (distance >= threshold) return Status.DRIFTED;
        return Status.ACTIVE;
    }
}
