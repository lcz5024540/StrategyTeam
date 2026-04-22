package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class SmartWalletPnlTimelinePoint {
    private Date bucketAt;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal netPnlUsdc;
    private BigDecimal cumulativeRealizedPnlUsdc;
    private BigDecimal cumulativeFeeUsdc;
    private BigDecimal cumulativeNetPnlUsdc;
}
