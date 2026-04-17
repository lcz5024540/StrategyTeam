package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class SmartWalletFollowAddressDailyReportView {
    private Date reportDate;
    private String walletAddress;
    private Integer totalOrders;
    private Integer filledOrders;
    private Integer openPositions;
    private Integer closedPositions;
    private Integer winningPositions;
    private Integer losingPositions;
    private Integer breakevenPositions;
    private Integer distinctMarkets;
    private BigDecimal tradedVolumeUsdc;
    private BigDecimal grossExposureUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal netPnlUsdc;
    private BigDecimal winRate;
    private BigDecimal averageWinUsdc;
    private BigDecimal averageLossUsdc;
    private BigDecimal largestWinUsdc;
    private BigDecimal largestLossUsdc;
}
