package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletReplayConcentrationSummaryResult {
    private Date generatedAt;
    private Date sourceReplayGeneratedAt;
    private SmartWalletReplayBacktestRequest applied;
    private String summaryScope;
    @Builder.Default
    private List<SmartWalletReplayConcentrationWindowSummary> windows = new ArrayList<>();
}
