package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketOrderDryRunRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PolymarketClobExecutionService {

    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketClobClientRouter clobClientRouter;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final PolymarketOrderRequestFactory orderRequestFactory;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletFollowTaskService followTaskService;

    public Map<String, Object> deriveApiCreds() {
        return clobClientRouter.deriveApiCreds(walletContextLoader.requireLiveContext());
    }

    public Map<String, Object> getOrder(String orderId) {
        return clobClientRouter.getOrder(walletContextLoader.requireLiveContext(), orderId);
    }

    public Map<String, Object> cancelOrder(String orderId) {
        return clobClientRouter.cancelOrder(walletContextLoader.requireLiveContext(), orderId);
    }

    public PolymarketApiClient.MarketMetadata marketMetadata(String conditionId) {
        return marketMetadataService.getByConditionId(conditionId);
    }

    public Map<String, Object> dryRunOrder(PolymarketOrderDryRunRequest request) {
        ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.requireLiveContext();
        if (request == null || request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("price must be provided and positive for dry-run.");
        }
        String outcome = request.getOutcome() == null ? "YES" : request.getOutcome().trim().toUpperCase();
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(request.getConditionId());
        SmartWalletSignal signal = SmartWalletSignal.builder()
                .signalId("dry-run-" + request.getConditionId() + "-" + outcome)
                .followOutcome(outcome)
                .recommendedStakeUsdc(request.getStakeUsdc() == null ? wallet.getMinOrderUsdc() : request.getStakeUsdc())
                .market(PredictionMarketOpportunity.builder()
                        .marketId(request.getConditionId())
                        .question(request.getQuestion() != null ? request.getQuestion() : metadata.getQuestion())
                        .outcome(outcome)
                        .price(request.getPrice())
                        .conviction(BigDecimal.ONE)
                        .liquidityUsdc(BigDecimal.ZERO)
                        .category(metadata.getCategory())
                        .build())
                .build();
        PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared = orderRequestFactory.prepare(signal, wallet);
        return Map.of(
                "success", true,
                "dryRun", true,
                "walletReady", wallet.isLiveReady(),
                "preparedOrder", prepared,
                "marketMetadata", metadata
        );
    }

    public Map<String, Object> dryRunFollowTask(Long followTaskId) {
        if (followTaskId == null) {
            throw new IllegalArgumentException("followTaskId must be provided.");
        }
        SmartWalletFollowTaskEntity task = followTaskMapper.selectById(followTaskId);
        if (task == null) {
            throw new IllegalArgumentException("Smart-wallet follow task not found: " + followTaskId);
        }
        if (!StringUtils.hasText(task.getMarketId())) {
            throw new IllegalStateException("Follow task is missing marketId.");
        }
        if (task.getTriggerPrice() == null || task.getTriggerPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Follow task is missing triggerPrice.");
        }
        if (!StringUtils.hasText(task.getOutcome())) {
            throw new IllegalStateException("Follow task is missing outcome.");
        }

        ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.requireLiveContext();
        SignalRiskDecision decision = followTaskService.evaluateTask(followTaskId);
        String outcome = task.getOutcome().trim().toUpperCase();
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(task.getMarketId());
        SmartWalletSignal signal = SmartWalletSignal.builder()
                .signalId("follow-task-" + task.getId())
                .followOutcome(outcome)
                .recommendedStakeUsdc(resolveStake(task, wallet))
                .market(PredictionMarketOpportunity.builder()
                        .marketId(task.getMarketId())
                        .question(StringUtils.hasText(task.getQuestion()) ? task.getQuestion() : metadata.getQuestion())
                        .outcome(outcome)
                        .price(task.getTriggerPrice())
                        .conviction(BigDecimal.ONE)
                        .liquidityUsdc(BigDecimal.ZERO)
                        .category(metadata.getCategory())
                        .build())
                .build();
        PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared = orderRequestFactory.prepare(signal, wallet);
        Map<String, Object> followTask = new LinkedHashMap<>();
        followTask.put("id", task.getId());
        followTask.put("status", task.getStatus());
        followTask.put("executionMode", task.getExecutionMode());
        followTask.put("walletAddress", task.getWalletAddress());
        followTask.put("marketId", task.getMarketId());
        followTask.put("question", task.getQuestion());
        followTask.put("outcome", task.getOutcome());
        followTask.put("side", task.getSide());
        followTask.put("triggerPrice", task.getTriggerPrice());
        followTask.put("recommendedStakeUsdc", task.getRecommendedStakeUsdc());
        followTask.put("externalOrderId", task.getExternalOrderId());
        followTask.put("errorMessage", task.getErrorMessage());
        return Map.of(
                "success", true,
                "dryRun", true,
                "walletReady", wallet.isLiveReady(),
                "taskExecutable", decision.isAccepted(),
                "riskDecision", decision,
                "followTask", followTask,
                "preparedOrder", prepared,
                "marketMetadata", metadata
        );
    }

    public SmartWalletExecutionResult executeFollowTask(Long followTaskId) {
        return followTaskService.executeTaskNow(followTaskId, com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode.LIVE);
    }

    private BigDecimal resolveStake(SmartWalletFollowTaskEntity task,
                                    ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        if (task.getRecommendedStakeUsdc() != null && task.getRecommendedStakeUsdc().compareTo(BigDecimal.ZERO) > 0) {
            return task.getRecommendedStakeUsdc();
        }
        if (task.getTriggerSizeUsdc() != null && task.getTriggerSizeUsdc().compareTo(BigDecimal.ZERO) > 0) {
            return task.getTriggerSizeUsdc();
        }
        return wallet.getMinOrderUsdc();
    }
}
