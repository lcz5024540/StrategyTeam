package com.metaverse.coin.strategy.job.engine;

/**
 * 策略运行模式。
 * 所有策略共享同一套模式定义，确保行为一致。
 */
public enum StrategyMode {

    /** 关闭 —— 策略不运行 */
    OFF,

    /** 影子模式 —— 产生信号并记录日志，不下单 */
    SHADOW,

    /** 模拟模式 —— 产生信号并在本地 Paper 账本中记录虚拟仓位 */
    PAPER,

    /** 实盘模式 —— 真实下单到 Polymarket CLOB */
    LIVE;

    /** 是否允许真实下单 */
    public boolean isLiveTrading() {
        return this == LIVE;
    }

    /** 是否会产生仓位记录（Paper 或 Live） */
    public boolean isPositionTracking() {
        return this == PAPER || this == LIVE;
    }

    /** 是否启用（非 OFF） */
    public boolean isActive() {
        return this != OFF;
    }
}
