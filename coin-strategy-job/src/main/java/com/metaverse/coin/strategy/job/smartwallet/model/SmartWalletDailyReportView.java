package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class SmartWalletDailyReportView {
    private Long id;
    private Date reportDate;
    private String executionAddress;
    private Integer totalOrders;
    private Integer filledOrders;
    private BigDecimal totalVolumeUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal profitPnlUsdc;
    private BigDecimal lossPnlUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal netPnlUsdc;
    private Integer winningPositions;
    private Integer losingPositions;
    private BigDecimal winRate;
    private Integer conflictingMarkets;
    private Integer conflictingPositions;
    private BigDecimal conflictNetPnlUsdc;
}
