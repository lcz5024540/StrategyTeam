package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletDailyReportView(
        Long id,
        Date reportDate,
        String executionAddress,
        Integer totalOrders,
        Integer filledOrders,
        BigDecimal totalVolumeUsdc,
        BigDecimal feeUsdc,
        BigDecimal profitPnlUsdc,
        BigDecimal lossPnlUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal unrealizedPnlUsdc,
        BigDecimal netPnlUsdc,
        Integer winningPositions,
        Integer losingPositions,
        BigDecimal winRate,
        Integer conflictingMarkets,
        Integer conflictingPositions,
        BigDecimal conflictNetPnlUsdc
) {
}
