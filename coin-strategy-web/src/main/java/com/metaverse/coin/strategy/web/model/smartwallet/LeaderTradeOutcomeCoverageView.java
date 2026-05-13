package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;

/**
 * Leader 跟单覆盖率视图：对比 leader 真实闭环 vs FOLLOWED/SKIPPED 子集的胜率与 PnL。
 * <p>从 job 的同名 view 下沉到 web，签名一致以兼容前端。</p>
 */
public record LeaderTradeOutcomeCoverageView(
        String walletAddress,
        long leaderOutcomes,
        long closedOutcomes,
        long followedOutcomes,
        long skippedOutcomes,
        long notMatchedOutcomes,
        BigDecimal leaderWinRate,
        BigDecimal followedWinRate,
        BigDecimal skippedWinRate,
        BigDecimal leaderPnlUsdc,
        BigDecimal followedPnlUsdc,
        BigDecimal skippedPnlUsdc,
        BigDecimal eventFollowRate,
        String recommendation
) {
}
