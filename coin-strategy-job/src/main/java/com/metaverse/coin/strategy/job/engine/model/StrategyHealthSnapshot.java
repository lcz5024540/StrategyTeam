package com.metaverse.coin.strategy.job.engine.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 策略健康快照。
 */
@Data
@Builder
public class StrategyHealthSnapshot {

    /** 策略标识 */
    private String strategyId;

    /** 检查时间 */
    private Instant checkedAt;

    /** 策略是否健康 */
    private boolean healthy;

    /** 当前持仓数 */
    private int openPositions;

    /** 当前未完成订单数 */
    private int pendingOrders;

    /** 钱包 USDC 余额 */
    private BigDecimal walletBalanceUsdc;

    /** 总持仓市值（USDC） */
    private BigDecimal totalExposureUsdc;

    /** 今日已实现 PNL */
    private BigDecimal todayRealizedPnlUsdc;

    /** 未实现 PNL */
    private BigDecimal unrealizedPnlUsdc;

    /** 最近一次信号扫描时间 */
    private Instant lastCycleAt;

    /** 最近一次退出扫描时间 */
    private Instant lastExitScanAt;

    /** 健康检查备注 */
    private String notes;
}
