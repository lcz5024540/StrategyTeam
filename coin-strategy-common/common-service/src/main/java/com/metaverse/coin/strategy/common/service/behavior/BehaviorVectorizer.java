package com.metaverse.coin.strategy.common.service.behavior;

/**
 * Tier B (第十五批): 行为特征向量化通用接口.
 *
 * <p>把任意 actor (跟单 leader / 天气数据源 / BTC 5min 信号源) 的单个事件转成 N 维特征向量,
 * 用于 {@link CentroidDriftDetector} 计算行为漂移. 各策略各自实现自己的 vectorizer:
 * <ul>
 *   <li><b>跟单</b>: 7 维 = [log10(size+1)/6, sideBuy, 5-cat one-hot] (现 LeaderBehaviorDriftService)</li>
 *   <li><b>天气</b>: 可能是 [城市 one-hot, 温度区间, 时段] 等</li>
 *   <li><b>BTC 5min</b>: 可能是 [价差档位, 持仓方向, 滑点] 等</li>
 * </ul></p>
 *
 * <p><b>使用约束</b>:
 * <ol>
 *   <li>{@link #dimension()} 必须恒定 — 一旦实现, 维度不能跨版本变化, 否则历史 centroid 失效;</li>
 *   <li>{@link #vectorize(Object)} 必须确定性 — 同一 event 应总是返回同一向量;</li>
 *   <li>返回数组长度必须等于 {@link #dimension()}.</li>
 * </ol></p>
 *
 * <p>详见 docs/polymarket/cross-strategy-reusable-infrastructure.md 二.1 Tier B.</p>
 *
 * @param <E> 事件类型 (跟单=SmartWalletTradeEventEntity, 天气=自定义, BTC=自定义)
 */
public interface BehaviorVectorizer<E> {

    /** 特征向量维度. 必须恒定. */
    int dimension();

    /**
     * 将单个 event 向量化为 N 维数组. 返回长度必须等于 {@link #dimension()}.
     * event 为 null 时返回全零向量.
     */
    double[] vectorize(E event);

    /** 给定一组 event 计算 centroid (均值向量). 默认实现可直接复用. */
    default double[] computeCentroid(java.util.Collection<E> events) {
        int dim = dimension();
        double[] sum = new double[dim];
        if (events == null || events.isEmpty()) return sum;
        int count = 0;
        for (E e : events) {
            double[] v = vectorize(e);
            if (v == null || v.length != dim) continue;
            for (int i = 0; i < dim; i++) sum[i] += v[i];
            count++;
        }
        if (count == 0) return sum;
        double[] centroid = new double[dim];
        for (int i = 0; i < dim; i++) centroid[i] = sum[i] / count;
        return centroid;
    }
}
