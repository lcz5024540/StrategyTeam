package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class SmartWalletFollowCoverageReportView {
    private String walletAddress;
    private long totalEvents;
    private long followedEvents;
    private long skippedEvents;
    private long shadowEvents;
    private long tradableBuyEvents;
    private BigDecimal followCoverageRate;
    private BigDecimal shadowCoverageRate;
    private BigDecimal followedPnlUsdc;
    private BigDecimal followedWinRate;
    private BigDecimal shadowTruthPnlUsdc;
    private BigDecimal shadowTruthWinRate;
    private String recommendation;
    private List<SmartWalletFollowCoverageReasonView> skippedPrimaryReasons;
}
