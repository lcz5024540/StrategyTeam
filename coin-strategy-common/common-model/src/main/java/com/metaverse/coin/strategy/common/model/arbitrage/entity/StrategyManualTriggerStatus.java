package com.metaverse.coin.strategy.common.model.arbitrage.entity;

/**
 * 手动触发命令状态常量。
 */
public final class StrategyManualTriggerStatus {

    /** 等待 consumer 拾取 */
    public static final String PENDING = "PENDING";

    /** consumer 已 CAS 标记为运行中 */
    public static final String RUNNING = "RUNNING";

    /** 成功完成 */
    public static final String COMPLETED = "COMPLETED";

    /** 执行失败 */
    public static final String FAILED = "FAILED";

    private StrategyManualTriggerStatus() {
    }
}
