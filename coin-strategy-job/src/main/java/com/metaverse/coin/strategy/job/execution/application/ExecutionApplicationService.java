package com.metaverse.coin.strategy.job.execution.application;

import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionWalletStatus;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketOrderDryRunRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ExecutionApplicationService {

    private final SmartWalletFollowService smartWalletFollowService;
    private final PolymarketClobExecutionService polymarketClobExecutionService;

    public ExecutionApplicationService(SmartWalletFollowService smartWalletFollowService,
                                       PolymarketClobExecutionService polymarketClobExecutionService) {
        this.smartWalletFollowService = smartWalletFollowService;
        this.polymarketClobExecutionService = polymarketClobExecutionService;
    }

    public ExecutionWalletStatus executionWalletStatus() {
        return smartWalletFollowService.executionWalletStatus();
    }

    public Map<String, Object> deriveApiCreds() {
        return polymarketClobExecutionService.deriveApiCreds();
    }

    public Map<String, Object> dryRunOrder(PolymarketOrderDryRunRequest request) {
        return polymarketClobExecutionService.dryRunOrder(request);
    }

    public SmartWalletExecutionResult executeFollowTask(Long followTaskId) {
        return polymarketClobExecutionService.executeFollowTask(followTaskId);
    }
}
