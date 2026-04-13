package com.metaverse.coin.strategy.job.hedge.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 套利机会快照（策略引擎使用，不落库）
 *
 * 对应一个 Polymarket 二元市场，在某个时刻满足了套利条件：
 *   P_yes + P_no < 1 → 以 yes_ask + no_ask 买入双边，锁定无风险收益
 */
@Data
@Builder
public class HedgeOpportunity {

    private String marketId;
    private String question;
    private String yesTokenId;
    private String noTokenId;

    /** YES 最优卖出价（我们需要以此价格买入）*/
    private BigDecimal yesBestAsk;
    /** NO 最优卖出价（我们需要以此价格买入）*/
    private BigDecimal noBestAsk;

    /** YES 端可用流动性（USDC）*/
    private BigDecimal yesLiquidityUsdc;
    /** NO 端可用流动性（USDC）*/
    private BigDecimal noLiquidityUsdc;

    /** P_yes + P_no */
    private BigDecimal totalImpliedProb;
    /** 1 - totalImpliedProb（正值 = 套利空间）*/
    private BigDecimal probDeviation;
    /** 毛利率（扣费前）= probDeviation */
    private BigDecimal grossEdgeRate;
    /** 净利率（扣除双边手续费后）*/
    private BigDecimal netEdgeRate;

    /** 市场到期时间戳（ms）*/
    private Long marketEndAt;
}
