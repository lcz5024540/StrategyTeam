package com.metaverse.coin.strategy.common.service.behavior.example;

import com.metaverse.coin.strategy.common.service.behavior.BehaviorVectorizer;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 第十七批 (2026-05-16) Tier B 跨策略复用样例 — {@link BehaviorVectorizer}<{@link WeatherActorEvent}> 实现.
 *
 * <p>把单个天气 actor 事件转成 8 维特征向量 (与跟单的 7 维独立设计):
 * <ol>
 *   <li>{@code magnitudeBucket} — {@code log10(magnitude + 1) / 3}, 上限 1.0</li>
 *   <li>{@code directionHot}    — direction = HOT → 1, 否则 0</li>
 *   <li>{@code directionCold}   — direction = COLD → 1, 否则 0</li>
 *   <li>{@code cityNY}          — city = NY → 1, 否则 0</li>
 *   <li>{@code cityLA}          — city = LA → 1, 否则 0</li>
 *   <li>{@code cityCHI}         — city = CHI → 1, 否则 0</li>
 *   <li>{@code cityLON}         — city = LON → 1, 否则 0</li>
 *   <li>{@code cityOther}       — 兜底</li>
 * </ol></p>
 *
 * <p><b>对比跟单 {@code SmartWalletTradeEventVectorizer}</b>:
 * <ul>
 *   <li>跟单维度 = 7 (1 size + 1 side + 5 category)</li>
 *   <li>天气维度 = 8 (1 magnitude + 2 direction + 5 city)</li>
 *   <li>两者用同一 {@link BehaviorVectorizer} 接口契约 + 同一
 *       {@link com.metaverse.coin.strategy.common.service.behavior.CentroidDriftDetector}
 *       下游 drift 算法 — 这正是 Tier B 抽象成立的证据.</li>
 * </ul></p>
 *
 * <p><b>设计要点</b>:
 * <ul>
 *   <li>纯 PoJo 无 Spring 依赖, 任何上下文可 {@code new} 实例化;</li>
 *   <li>城市列表固定为 NY/LA/CHI/LON + OTHER 5 类, 一旦天气策略真正上线, 可根据实际监控城市
 *       扩展 / 替换字段, 但维度必须随之冻结 (与跟单 CATEGORY_KEYS 设计一致);</li>
 *   <li>{@link #dimension()} = 8, 恒定.</li>
 * </ul></p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无影响</b> —
 * 本类位于跨策略 common-service example 包, 跟单系统不引用.</p>
 */
public final class WeatherActorEventVectorizer
        implements BehaviorVectorizer<WeatherActorEvent> {

    public static final List<String> CITY_KEYS = Arrays.asList("NY", "LA", "CHI", "LON", "OTHER");

    public static final int FEATURE_DIM = 1 + 2 + 5;

    private static final double MAG_LOG10_DIVISOR = 3.0;

    @Override
    public int dimension() {
        return FEATURE_DIM;
    }

    @Override
    public double[] vectorize(WeatherActorEvent event) {
        double[] v = new double[FEATURE_DIM];
        if (event == null) return v;
        v[0] = Math.min(1.0, Math.log10(event.getMagnitude() + 1.0) / MAG_LOG10_DIVISOR);
        String dir = event.getDirection() == null ? "" : event.getDirection().toUpperCase(Locale.ROOT);
        v[1] = "HOT".equals(dir) ? 1.0 : 0.0;
        v[2] = "COLD".equals(dir) ? 1.0 : 0.0;
        String city = event.getCity() == null ? "" : event.getCity().toUpperCase(Locale.ROOT);
        int idx = CITY_KEYS.indexOf(city);
        if (idx < 0) {
            idx = CITY_KEYS.indexOf("OTHER");
        }
        v[3 + idx] = 1.0;
        return v;
    }
}
