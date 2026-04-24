package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.util.List;

@Data
public class SmartWalletProfileBatchOverrideRequest {

    private List<String> walletAddresses;
    private Boolean manualWatchEnabled;
    private Boolean clearManualWatchEnabled = false;
    private String manualRecommendedExitLane;
    private Boolean clearManualRecommendedExitLane = false;
    private String note;

    public SmartWalletProfileOverrideRequest toSingleOverrideRequest() {
        SmartWalletProfileOverrideRequest request = new SmartWalletProfileOverrideRequest();
        request.setManualWatchEnabled(manualWatchEnabled);
        request.setClearManualWatchEnabled(clearManualWatchEnabled);
        request.setManualRecommendedExitLane(manualRecommendedExitLane);
        request.setClearManualRecommendedExitLane(clearManualRecommendedExitLane);
        request.setNote(note);
        return request;
    }
}
