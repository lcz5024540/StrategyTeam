package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 统一策略运行日志实体。
 * 记录每次策略运行的信号发现数、下单数、错误数等。
 */
@Data
@Accessors(chain = true)
@TableName("strategy_run_log")
public class StrategyRunLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略 ID */
    private String strategyId;

    /** 钱包 ID */
    private String walletId;

    /** 运行类型: SIGNAL_SCAN / EXIT_SCAN / HEALTH_CHECK */
    private String runType;

    /** 运行模式: SHADOW / PAPER / LIVE */
    private String runMode;

    /** 开始时间 */
    private Date startedAt;

    /** 结束时间 */
    private Date finishedAt;

    /** 发现信号数 */
    private Integer signalsFound;

    /** 下单数 */
    private Integer ordersPlaced;

    /** 错误数 */
    private Integer errors;

    /** 策略特有的运行摘要（JSON） */
    private String summaryJson;
}
