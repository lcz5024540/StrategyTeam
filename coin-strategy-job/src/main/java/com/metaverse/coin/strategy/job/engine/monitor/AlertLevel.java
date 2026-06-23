package com.metaverse.coin.strategy.job.engine.monitor;

/**
 * 告警级别。
 */
public enum AlertLevel {

    /** 信息性通知 */
    INFO,

    /** 需要关注的警告 */
    WARN,

    /** 需要立即处理的严重问题 */
    CRITICAL,

    /** 系统性故障，触发熔断 */
    FATAL;

    public boolean isActionRequired() {
        return this == CRITICAL || this == FATAL;
    }
}
