package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;

/**
 * Leader 真实闭环胜率/PnL 汇总（按钱包地址 + 窗口）。
 * <p>从 job 的同名 view 下沉到 web，签名一致以兼容前端。</p>
 */
public record LeaderTradeOutcomeSummaryView(
        String walletAddress,
        String window,
        long outcomes,
        long closedOutcomes,
        long wins,
        long losses,
        long breakeven,
        long openOutcomes,
        long unresolvedOutcomes,
        long lowConfidenceOutcomes,
        BigDecimal winRate,
        BigDecimal buyCostUsdc,
        BigDecimal estimatedPnlUsdc,
        BigDecimal roiRate,
        String recommendation
) {
}
