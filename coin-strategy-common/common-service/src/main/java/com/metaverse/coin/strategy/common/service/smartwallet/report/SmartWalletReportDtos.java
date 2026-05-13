package com.metaverse.coin.strategy.common.service.smartwallet.report;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public final class SmartWalletReportDtos {

    private SmartWalletReportDtos() {
    }

    public record AttributionRow(
            String key,
            String label,
            Integer orderCount,
            BigDecimal tradedVolumeUsdc,
            BigDecimal realizedPnlUsdc,
            BigDecimal pendingSettlementPnlUsdc,
            BigDecimal feeUsdc,
            BigDecimal netPnlUsdc
    ) {
    }

    public record DailyReportRow(
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

    public record TimelinePoint(
            Date bucketAt,
            BigDecimal profitPnlUsdc,
            BigDecimal lossPnlUsdc,
            BigDecimal realizedPnlUsdc,
            BigDecimal feeUsdc,
            BigDecimal netPnlUsdc,
            BigDecimal cumulativeLossPnlUsdc,
            BigDecimal cumulativeRealizedPnlUsdc,
            BigDecimal cumulativeFeeUsdc,
            BigDecimal cumulativeNetPnlUsdc
    ) {
    }

    public record ConflictMarketRow(
            String marketId,
            String question,
            Integer outcomeCount,
            Integer positionCount,
            Integer openPositionCount,
            List<String> outcomes,
            BigDecimal realizedPnlUsdc,
            BigDecimal unrealizedPnlUsdc,
            BigDecimal netPnlUsdc,
            Long marketEndAt,
            String marketEndAtBjt,
            Date latestClosedAt
    ) {
    }

    public record FollowAddressDailyReportRow(
            Date reportDate,
            String walletAddress,
            Integer totalOrders,
            Integer filledOrders,
            Integer openPositions,
            Integer closedPositions,
            Integer winningPositions,
            Integer losingPositions,
            Integer breakevenPositions,
            Integer distinctMarkets,
            BigDecimal tradedVolumeUsdc,
            BigDecimal grossExposureUsdc,
            BigDecimal feeUsdc,
            BigDecimal realizedPnlUsdc,
            BigDecimal unrealizedPnlUsdc,
            BigDecimal netPnlUsdc,
            BigDecimal winRate,
            BigDecimal averageWinUsdc,
            BigDecimal averageLossUsdc,
            BigDecimal largestWinUsdc,
            BigDecimal largestLossUsdc
    ) {
    }
}
