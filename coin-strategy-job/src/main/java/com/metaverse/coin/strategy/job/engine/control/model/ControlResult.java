package com.metaverse.coin.strategy.job.engine.control.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 控制操作结果。
 */
@Data
@Builder
public class ControlResult {

    /** 操作是否成功 */
    private boolean success;

    /** 操作类型 */
    private ControlAction action;

    /** 策略 ID（全局操作为 null） */
    private String strategyId;

    /** 结果消息 */
    private String message;

    /** 操作时间 */
    private Instant timestamp;
}
