package com.metaverse.coin.strategy.job.engine.position;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 跨策略统一仓位视图。
 * 将不同策略的持仓归一化为统一格式。
 */
@Data
@Builder
public class UnifiedPosition {

    /** 策略标识 */
    private String strategyId;

    /** 钱包标识 */
    private String walletId;

    /** Polymarket conditionId */
    private String marketId;

    /** 市场标题 */
    private String marketTitle;

    /** YES / NO */
    private String outcome;

    /** 持仓方向: LONG / SHORT */
    private String direction;

    /** 入场价格 */
    private BigDecimal entryPrice;

    /** 当前市价 */
    private BigDecimal currentPrice;

    /** 持仓数量（shares） */
    private BigDecimal quantity;

    /** 投入金额（USDC） */
    private BigDecimal stakeUsdc;

    /** 当前市值（USDC） */
    private BigDecimal currentValueUsdc;

    /** 未实现 PNL（USDC） */
    private BigDecimal unrealizedPnlUsdc;

    /** 开仓时间 */
    private Instant openedAt;

    /** 市场到期时间 */
    private Instant marketEndAt;
}
