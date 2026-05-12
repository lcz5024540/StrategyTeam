package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletFollowAddressDailyReportView(
    Date reportDate, String walletAddress,
    Integer totalOrders, Integer filledOrders,
    Integer openPositions, Integer closedPositions,
    Integer winningPositions, Integer losingPositions, Integer breakevenPositions,
    Integer distinctMarkets,
    BigDecimal tradedVolumeUsdc, BigDecimal grossExposureUsdc, BigDecimal feeUsdc,
    BigDecimal realizedPnlUsdc, BigDecimal unrealizedPnlUsdc, BigDecimal netPnlUsdc,
    BigDecimal winRate,
    BigDecimal averageWinUsdc, BigDecimal averageLossUsdc,
    BigDecimal largestWinUsdc, BigDecimal largestLossUsdc) {}
