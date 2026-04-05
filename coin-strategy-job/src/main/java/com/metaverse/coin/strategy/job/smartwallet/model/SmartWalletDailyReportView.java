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
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal netPnlUsdc;
    private BigDecimal winRate;
}
