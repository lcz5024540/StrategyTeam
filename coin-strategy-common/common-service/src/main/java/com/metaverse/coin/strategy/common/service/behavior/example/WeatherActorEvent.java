package com.metaverse.coin.strategy.common.service.behavior.example;

/**
 * 第十七批 (2026-05-16) Tier B 跨策略复用样例 — 天气策略 actor 事件 PoC.
 *
 * <p>本类是天气策略未来真正落地时 actor-event 的<b>最小骨架</b>, 用于验证
 * {@link com.metaverse.coin.strategy.common.service.behavior.BehaviorVectorizer} +
 * {@link com.metaverse.coin.strategy.common.service.behavior.CentroidDriftDetector}
 * 通用接口是否真的能被一个"非跟单"策略复用. 与跟单的 {@code SmartWalletTradeEventEntity}
 * 不同, 本类不引入 MyBatis / Spring 依赖, 纯 PoJo, 一旦天气策略真正上线, 此类应被替换为
 * 持久化实体 + DAO 层.</p>
 *
 * <p><b>语义</b>: 一条"actor (例如某气象数据源 / 某个 leader 账户) 在 t 时刻发出的天气信号事件".
 * 字段是天气策略真实可能的最小集合, 但不是最终设计 — 上线时由天气策略 owner 增删字段即可.</p>
 *
 * <p><b>对跟单系统的影响</b>: ⚪ 无 — 本类位于 common-service 跨策略包,
 * 不被任何跟单 hot path 引用, 也不参与跟单 SignalRisk / Accounting / Order 路径.
 * 编译 / 加载本类对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率全部为零影响.</p>
 *
 * <p>详见 docs/polymarket/cross-strategy-reusable-infrastructure.md 二.1 Tier B
 * 与 copy-trading-improvement-decision-2026-05-16.md 第二十六节.</p>
 */
public final class WeatherActorEvent {

    /** 信号源 actor 标识 (例如气象站 ID / leader 钱包地址). */
    private final String actorId;

    /**
     * 城市标识. 用于一热编码 — 与跟单的 category 桶语义对齐 (POLITICS/CRYPTO/SPORTS/...).
     * 实际生产中天气策略可能监控有限的城市集合, 这里用字符串保持灵活.
     */
    private final String city;

    /**
     * 信号方向. 例如 "HOT" / "COLD" / "RAIN" / "SNOW".
     * 用于一热编码 — 与跟单的 side (BUY/SELL) 语义对齐.
     */
    private final String direction;

    /**
     * 信号强度 (单位无关, 例如 0-100 的置信度分数, 或与温度偏离 baseline 的绝对值).
     * 用对数归一化, 与跟单 sizeUsdc 处理一致.
     */
    private final double magnitude;

    /** epoch millis. PoJo 不引入 java.time 也能跨平台序列化. */
    private final long timestampMs;

    public WeatherActorEvent(String actorId, String city, String direction,
                             double magnitude, long timestampMs) {
        this.actorId = actorId == null ? "" : actorId;
        this.city = city == null ? "" : city;
        this.direction = direction == null ? "" : direction;
        this.magnitude = Math.max(0.0, magnitude);
        this.timestampMs = timestampMs;
    }

    public String getActorId() { return actorId; }
    public String getCity() { return city; }
    public String getDirection() { return direction; }
    public double getMagnitude() { return magnitude; }
    public long getTimestampMs() { return timestampMs; }
}
