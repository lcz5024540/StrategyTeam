package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletReplayBacktestResult {
    private Date generatedAt;
    private SmartWalletReplayBacktestRequest applied;
    @Builder.Default
    private List<SmartWalletReplayWindowResult> windows = new ArrayList<>();
}
