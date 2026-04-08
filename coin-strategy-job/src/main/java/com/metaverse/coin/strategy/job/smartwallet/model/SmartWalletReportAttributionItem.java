package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SmartWalletReportAttributionItem {
    private String key;
    private String label;
    private Integer orderCount;
    private BigDecimal tradedVolumeUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal pendingSettlementPnlUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal netPnlUsdc;
}
