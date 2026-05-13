package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 单地址订单窗口统计视图（30d 默认）。
 * <p>原为 {@code com.metaverse.coin.strategy.job.smartwallet.model.WalletOrderStatsView}。
 * Phase 4.4 下沉。</p>
 */
@Builder
public record WalletOrderStatsView(
        String walletAddress,
        String window,
        Date generatedAt,
        Integer closedOrderCount,
        Integer winCount,
        Integer lossCount,
        Integer drawCount,
        BigDecimal closedWinRate,
        BigDecimal closedRealizedPnlUsdc,
        BigDecimal activeUnrealizedPnlUsdc,
        BigDecimal netPnlUsdc,
        BigDecimal averageRoiRate,
        Integer activePositionCount,
        Integer pendingSettlementCount,
        BigDecimal earlySettlementRate,
        BigDecimal settlementWinRate
) {
}
