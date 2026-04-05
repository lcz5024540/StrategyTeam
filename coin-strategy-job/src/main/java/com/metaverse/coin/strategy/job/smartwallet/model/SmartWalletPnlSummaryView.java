package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SmartWalletPnlSummaryView {
    private Integer totalOrders;
    private Integer filledOrders;
    private Integer openPositions;
    private BigDecimal tradedVolumeUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal netPnlUsdc;
    private BigDecimal winRate;
}
