package com.metaverse.coin.strategy.common.service.behavior.example;

import com.metaverse.coin.strategy.common.service.behavior.BehaviorVectorizer;
import com.metaverse.coin.strategy.common.service.behavior.CentroidDriftDetector;

import java.util.Collection;

/**
 * 第十七批 (2026-05-16) Tier B 跨策略复用样例 — 天气 actor drift 检测原型.
 *
 * <p>本类是天气策略未来真正上线时, "actor behavior drift" 风控门的<b>最小原型</b>.
 * 它把跟单 W2-T6 ({@code LeaderBehaviorDriftService}) 的核心算法用通用 Tier B 接口重写一遍,
 * 不依赖跟单专属的 SmartWalletXxx 实体, 仅依赖
 * {@link BehaviorVectorizer}<{@link WeatherActorEvent}> + {@link CentroidDriftDetector}.</p>
 *
 * <p><b>用法示例</b> (天气策略真正上线时只需替换 Vectorizer 实例 + 数据源):
 * <pre>{@code
 *   WeatherActorEventVectorizer vec = new WeatherActorEventVectorizer();
 *   WeatherActorDriftDetector detector = new WeatherActorDriftDetector(vec, 0.40, 0.60, 10);
 *
 *   // 历史 N 天事件 (从 DB / Kafka / Redis pull)
 *   List<WeatherActorEvent> history = pullActorHistory("station-A", 30);
 *   WeatherActorEvent current = nextEvent("station-A");
 *
 *   WeatherActorDriftDetector.Result r = detector.evaluate(history, current);
 *   if (r.status() == CentroidDriftDetector.Status.DRIFTED_SEVERE) {
 *       // 拒绝信号 (与 SmartWalletSignalRiskService Gate 2c 等价)
 *   } else if (r.status() == CentroidDriftDetector.Status.DRIFTED) {
 *       // 降级仓位 (例如 ×0.25)
 *   }
 * }</pre></p>
 *
 * <p><b>对比跟单 LeaderBehaviorDriftService</b>:
 * <ul>
 *   <li>跟单内嵌 5min TTL cache / DB profile 表 / Spring bean 装配 — 是<b>生产级</b>实现;</li>
 *   <li>本类是<b>原型 PoC</b>: 无 cache / 无 DB / 无 Spring — 仅证明算法可复用;</li>
 *   <li>天气策略真正上线时, 应当模仿 LeaderBehaviorDriftService 的"hot path + profile snapshot + Job 聚合"模式
 *       新建 WeatherActorDriftService (Spring bean), 但<b>共享</b>本类的 cosine + classify 算法.</li>
 * </ul></p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无影响</b> —
 * 本类位于 example 包, 跟单系统不引用, 编译验证 / 单元测试零副作用.</p>
 *
 * <p>详见 docs/polymarket/cross-strategy-reusable-infrastructure.md
 * 与 copy-trading-improvement-decision-2026-05-16.md 第二十六节.</p>
 */
public final class WeatherActorDriftDetector {

    private final BehaviorVectorizer<WeatherActorEvent> vectorizer;
    private final double threshold;
    private final double severeThreshold;
    private final int minSamples;

    /**
     * @param vectorizer      行为向量化实现 (例如 {@link WeatherActorEventVectorizer})
     * @param threshold       DRIFTED 触发的下限 (建议 0.40)
     * @param severeThreshold DRIFTED_SEVERE 触发的下限 (建议 0.60); 必须 ≥ threshold
     * @param minSamples      触发漂移判定所需的最小历史样本数 (cold-start 保护; 建议 10)
     */
    public WeatherActorDriftDetector(BehaviorVectorizer<WeatherActorEvent> vectorizer,
                                     double threshold, double severeThreshold, int minSamples) {
        if (vectorizer == null) {
            throw new IllegalArgumentException("vectorizer required");
        }
        if (severeThreshold < threshold) {
            throw new IllegalArgumentException("severeThreshold must be >= threshold");
        }
        if (minSamples < 1) {
            throw new IllegalArgumentException("minSamples must be >= 1");
        }
        this.vectorizer = vectorizer;
        this.threshold = threshold;
        this.severeThreshold = severeThreshold;
        this.minSamples = minSamples;
    }

    /**
     * 评估当前事件是否相对历史发生行为漂移.
     *
     * @param history 历史事件集合; 样本数 < {@code minSamples} 时按 ACTIVE 处理 (cold-start)
     * @param current 当前事件; 为 null 或全零向量时按 ACTIVE 处理
     */
    public Result evaluate(Collection<WeatherActorEvent> history, WeatherActorEvent current) {
        int sampleCount = history == null ? 0 : history.size();
        if (sampleCount < minSamples) {
            return new Result(CentroidDriftDetector.Status.ACTIVE,
                    Double.NaN, sampleCount, true, "cold-start");
        }
        double[] centroid = vectorizer.computeCentroid(history);
        double[] currentVec = vectorizer.vectorize(current);
        double distance = CentroidDriftDetector.cosineDistance(centroid, currentVec);
        CentroidDriftDetector.Status status =
                CentroidDriftDetector.classify(distance, threshold, severeThreshold);
        return new Result(status, distance, sampleCount, false, null);
    }

    /** 评估结果. */
    public static final class Result {
        private final CentroidDriftDetector.Status status;
        private final double cosineDistance;
        private final int sampleCount;
        private final boolean coldStart;
        private final String note;

        public Result(CentroidDriftDetector.Status status, double cosineDistance,
                      int sampleCount, boolean coldStart, String note) {
            this.status = status;
            this.cosineDistance = cosineDistance;
            this.sampleCount = sampleCount;
            this.coldStart = coldStart;
            this.note = note;
        }

        public CentroidDriftDetector.Status status() { return status; }
        public double cosineDistance() { return cosineDistance; }
        public int sampleCount() { return sampleCount; }
        public boolean coldStart() { return coldStart; }
        public String note() { return note; }
    }
}
