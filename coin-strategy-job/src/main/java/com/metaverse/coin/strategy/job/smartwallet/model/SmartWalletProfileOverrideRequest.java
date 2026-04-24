package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

@Data
public class SmartWalletProfileOverrideRequest {

    private Boolean manualWatchEnabled;
    private Boolean clearManualWatchEnabled = false;
    private String manualRecommendedExitLane;
    private Boolean clearManualRecommendedExitLane = false;
    private String note;
}
