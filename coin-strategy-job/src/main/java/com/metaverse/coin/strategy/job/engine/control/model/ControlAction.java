package com.metaverse.coin.strategy.job.engine.control.model;

/**
 * 控制操作类型。
 */
public enum ControlAction {

    /** 激活熔断 */
    KILL_SWITCH_ACTIVATE,

    /** 解除熔断 */
    KILL_SWITCH_DEACTIVATE,

    /** 手动触发信号扫描 */
    MANUAL_RUN_CYCLE,

    /** 手动触发退出扫描 */
    MANUAL_EXIT_SCAN,

    /** 状态查询 */
    STATUS_QUERY
}
