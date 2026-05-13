package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;

public record SmartWalletPerformanceView(
    Integer totalOrders, Integer filledOrders,
    Integer closedPositions, Integer openPositions, Integer pendingPositions,
    Integer winningPositions, Integer losingPositions, BigDecimal winRate,
    BigDecimal tradedVolumeUsdc, BigDecimal feeUsdc,
    BigDecimal realizedPnlUsdc, BigDecimal unrealizedPnlUsdc,
    BigDecimal pendingSettlementPnlUsdc, BigDecimal compositePnlUsdc,
    BigDecimal netPnlUsdc,
    BigDecimal averageWinUsdc, BigDecimal averageLossUsdc, BigDecimal profitFactor,
    BigDecimal maxDrawdownUsdc,
    Integer conflictingMarkets, Integer conflictingPositions,
    Integer openConflictingMarkets, Integer openConflictingPositions,
    BigDecimal conflictNetPnlUsdc,
    Integer earlyExitOrderCount, BigDecimal earlyExitRealizedPnlUsdc,
    Integer takeProfitOrderCount, BigDecimal takeProfitRealizedPnlUsdc,
    Integer stopLossOrderCount, BigDecimal stopLossRealizedPnlUsdc) {

    public Integer getTotalOrders() { return totalOrders; }
    public Integer getFilledOrders() { return filledOrders; }
    public Integer getClosedPositions() { return closedPositions; }
    public Integer getOpenPositions() { return openPositions; }
    public Integer getPendingPositions() { return pendingPositions; }
    public Integer getWinningPositions() { return winningPositions; }
    public Integer getLosingPositions() { return losingPositions; }
    public BigDecimal getWinRate() { return winRate; }
    public BigDecimal getRealizedPnlUsdc() { return realizedPnlUsdc; }
    public BigDecimal getUnrealizedPnlUsdc() { return unrealizedPnlUsdc; }
    public BigDecimal getPendingSettlementPnlUsdc() { return pendingSettlementPnlUsdc; }
    public BigDecimal getCompositePnlUsdc() { return compositePnlUsdc; }
    public BigDecimal getNetPnlUsdc() { return netPnlUsdc; }
    public BigDecimal getProfitFactor() { return profitFactor; }
    public BigDecimal getMaxDrawdownUsdc() { return maxDrawdownUsdc; }
    public Integer getConflictingMarkets() { return conflictingMarkets; }
    public Integer getOpenConflictingMarkets() { return openConflictingMarkets; }
    public BigDecimal getConflictNetPnlUsdc() { return conflictNetPnlUsdc; }
    public Integer getEarlyExitOrderCount() { return earlyExitOrderCount; }
    public BigDecimal getEarlyExitRealizedPnlUsdc() { return earlyExitRealizedPnlUsdc; }
    public Integer getTakeProfitOrderCount() { return takeProfitOrderCount; }
    public BigDecimal getTakeProfitRealizedPnlUsdc() { return takeProfitRealizedPnlUsdc; }
    public Integer getStopLossOrderCount() { return stopLossOrderCount; }
    public BigDecimal getStopLossRealizedPnlUsdc() { return stopLossRealizedPnlUsdc; }
}
