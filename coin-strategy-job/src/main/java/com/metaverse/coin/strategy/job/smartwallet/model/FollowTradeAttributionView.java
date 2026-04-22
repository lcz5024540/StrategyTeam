package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.Map;

@Value
@Builder
public class FollowTradeAttributionView {

    /** 分组维度描述（如 "walletScore:HIGH" 或 "exitReason:TAKE_PROFIT_AUTO_EXIT"） */
    String groupKey;

    /** 本组交易笔数 */
    int tradeCount;

    /** 盈利笔数 */
    int winCount;

    /** 胜率 = winCount / tradeCount */
    BigDecimal winRate;

    /** 总已实现盈亏（USDC） */
    BigDecimal totalRealizedPnlUsdc;

    /** 平均已实现盈亏（USDC） */
    BigDecimal avgRealizedPnlUsdc;

    /** 平均成本（USDC） */
    BigDecimal avgCostBasisUsdc;

    /** 平均持仓时长（小时） */
    BigDecimal avgHoldingHours;

    /** 各退出原因笔数，key=exitReason, value=count */
    Map<String, Integer> exitTypeBreakdown;
}
