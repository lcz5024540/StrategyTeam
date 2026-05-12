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
    Integer stopLossOrderCount, BigDecimal stopLossRealizedPnlUsdc) {}
