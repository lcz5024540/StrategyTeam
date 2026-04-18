package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SmartWalletPerformanceView {
    private Integer totalOrders;
    private Integer filledOrders;
    private Integer closedPositions;
    private Integer openPositions;
    private Integer winningPositions;
    private Integer losingPositions;
    private BigDecimal winRate;
    private BigDecimal tradedVolumeUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal pendingSettlementPnlUsdc;
    private BigDecimal compositePnlUsdc;
    private BigDecimal netPnlUsdc;
    private BigDecimal averageWinUsdc;
    private BigDecimal averageLossUsdc;
    private BigDecimal profitFactor;
    private BigDecimal maxDrawdownUsdc;
    private Integer conflictingMarkets;
    private Integer conflictingPositions;
    private Integer openConflictingMarkets;
    private Integer openConflictingPositions;
    private BigDecimal conflictNetPnlUsdc;
    private Integer earlyExitOrderCount;
    private BigDecimal earlyExitRealizedPnlUsdc;
    private Integer takeProfitOrderCount;
    private BigDecimal takeProfitRealizedPnlUsdc;
    private Integer stopLossOrderCount;
    private BigDecimal stopLossRealizedPnlUsdc;
}
