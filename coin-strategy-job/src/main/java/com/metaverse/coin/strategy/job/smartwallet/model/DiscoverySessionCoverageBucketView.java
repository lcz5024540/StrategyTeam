package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class DiscoverySessionCoverageBucketView {
    private String dominantSession;
    private int walletCount;
    private BigDecimal avgWinRate;
    private BigDecimal avgRecentWinRate30d;
    private BigDecimal avgWalletScore;
    private BigDecimal avgAsiaTradeRatio30d;
    private BigDecimal avgEuTradeRatio30d;
    private BigDecimal avgUsTradeRatio30d;
}
