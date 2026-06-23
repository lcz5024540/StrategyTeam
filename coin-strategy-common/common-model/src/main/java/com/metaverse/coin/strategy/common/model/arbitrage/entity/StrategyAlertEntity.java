package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 策略告警记录实体。
 */
@Data
@Accessors(chain = true)
@TableName("strategy_alert")
public class StrategyAlertEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略 ID（ALL 表示全局） */
    private String strategyId;

    /** 告警级别: INFO / WARN / CRITICAL / FATAL */
    private String alertLevel;

    /** 告警类型 */
    private String alertType;

    /** 告警标题 */
    private String title;

    /** 告警详细信息 */
    private String message;

    /** 触发指标值 */
    private BigDecimal metricValue;

    /** 阈值 */
    private BigDecimal thresholdValue;

    /** 是否已确认 */
    private Boolean acknowledged;

    /** 确认时间 */
    private Date acknowledgedAt;

    /** 是否已恢复 */
    private Boolean resolved;

    /** 恢复时间 */
    private Date resolvedAt;

    /** 创建时间 */
    private Date createdAt;
}
