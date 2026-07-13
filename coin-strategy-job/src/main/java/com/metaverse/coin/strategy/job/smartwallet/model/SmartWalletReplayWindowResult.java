package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SmartWalletReplayWindowResult {
    private Integer windowDays;
    private Integer scannedEvents;
    private SmartWalletReplayStrategyMetrics baseline;
    private SmartWalletReplayStrategyMetrics candidate;
}
