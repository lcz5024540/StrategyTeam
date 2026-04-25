package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

@Builder
public record WalletOrderStatsView(
        String walletAddress,
        String window,
        Date generatedAt,
        Integer closedOrderCount,
        Integer winCount,
        Integer lossCount,
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
