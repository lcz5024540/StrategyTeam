package com.metaverse.coin.strategy.job.engine.monitor;

/**
 * 告警类型枚举。
 */
public enum AlertType {

    /** 策略健康检查失败 */
    HEALTH_CHECK_FAIL(AlertLevel.CRITICAL, "策略健康检查失败"),

    /** 全局曝险超限 */
    EXPOSURE_LIMIT(AlertLevel.CRITICAL, "全局曝险超限"),

    /** 单策略曝险超限 */
    STRATEGY_EXPOSURE_LIMIT(AlertLevel.WARN, "单策略曝险超限"),

    /** 每日亏损超限 */
    DAILY_LOSS_LIMIT(AlertLevel.CRITICAL, "每日亏损超限"),

    /** 策略连续错误 */
    CONSECUTIVE_ERRORS(AlertLevel.WARN, "策略连续错误"),

    /** 策略心跳丢失（长时间未运行） */
    HEARTBEAT_MISS(AlertLevel.CRITICAL, "策略心跳丢失"),

    /** 紧急熔断触发 */
    KILL_SWITCH(AlertLevel.FATAL, "紧急熔断已触发"),

    /** 策略模式变更 */
    MODE_CHANGED(AlertLevel.INFO, "策略模式变更"),

    /** 策略启停 */
    STRATEGY_TOGGLED(AlertLevel.INFO, "策略启停操作");

    private final AlertLevel defaultLevel;
    private final String description;

    AlertType(AlertLevel defaultLevel, String description) {
        this.defaultLevel = defaultLevel;
        this.description = description;
    }

    public AlertLevel getDefaultLevel() {
        return defaultLevel;
    }

    public String getDescription() {
        return description;
    }
}
