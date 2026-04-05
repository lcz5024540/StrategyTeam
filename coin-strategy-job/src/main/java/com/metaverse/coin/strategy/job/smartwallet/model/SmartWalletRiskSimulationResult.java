package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SmartWalletRiskSimulationResult {
    private SmartWalletRiskSimulationRequest applied;
    private Integer scannedTasks;
    private Integer executableCount;
    private Integer opportunityCount;
    private List<SmartWalletFollowTaskView> executableTasks;
    private List<SmartWalletFollowTaskView> opportunityTasks;
}
