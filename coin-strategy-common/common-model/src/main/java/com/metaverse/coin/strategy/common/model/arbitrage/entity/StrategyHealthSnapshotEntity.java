package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 策略健康快照实体。
 * 定时采集各策略的运行状态，用于历史趋势分析。
 */
@Data
@Accessors(chain = true)
@TableName("strategy_health_snapshot")
public class StrategyHealthSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略 ID */
    private String strategyId;

    /** 快照时间 */
    private Date snapshotTime;

    /** 是否健康 */
    private Boolean healthy;

    /** 运行模式 */
    private String mode;

    /** 持仓数 */
    private Integer openPositions;

    /** 挂单数 */
    private Integer pendingOrders;

    /** 钱包余额(USDC) */
    private BigDecimal walletBalanceUsdc;

    /** 总曝险(USDC) */
    private BigDecimal totalExposureUsdc;

    /** 今日已实现PnL */
    private BigDecimal todayRealizedPnl;

    /** 未实现PnL */
    private BigDecimal unrealizedPnl;

    /** 备注 */
    private String notes;

    /** 创建时间 */
    private Date createdAt;
}
