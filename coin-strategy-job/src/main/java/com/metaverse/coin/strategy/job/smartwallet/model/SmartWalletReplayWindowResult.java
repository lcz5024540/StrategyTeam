package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SmartWalletReplayWindowResult {
    private Integer windowDays;
    private Integer scannedEvents;
    private Integer missingPointInTimeSnapshotEvents;
    private BigDecimal pointInTimeSnapshotCoverageRate;
    private Boolean strictPointInTime;
    private SmartWalletReplayStrategyMetrics baseline;
    private SmartWalletReplayStrategyMetrics candidate;
}
