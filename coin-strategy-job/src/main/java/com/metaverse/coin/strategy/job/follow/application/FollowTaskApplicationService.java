package com.metaverse.coin.strategy.job.follow.application;

import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletBlockerStatView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationResult;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FollowTaskApplicationService {

    private final SmartWalletFollowTaskService smartWalletFollowTaskService;

    public FollowTaskApplicationService(SmartWalletFollowTaskService smartWalletFollowTaskService) {
        this.smartWalletFollowTaskService = smartWalletFollowTaskService;
    }

    public List<SmartWalletFollowTaskView> listTasks(int limit, boolean includeRiskEvaluation) {
        return smartWalletFollowTaskService.listFollowTasks(limit, includeRiskEvaluation);
    }

    public List<SmartWalletFollowTaskView> listExecutableTasks(int limit) {
        return smartWalletFollowTaskService.listExecutableTasks(limit);
    }

    public SmartWalletRiskSimulationResult simulateRisk(SmartWalletRiskSimulationRequest request) {
        return smartWalletFollowTaskService.simulateRisk(request);
    }

    public List<SmartWalletBlockerStatView> blockerStats(int taskLimit) {
        return smartWalletFollowTaskService.blockerStats(taskLimit);
    }

    public int processPendingTasksNow() {
        return smartWalletFollowTaskService.processPendingTasksNow();
    }
}
