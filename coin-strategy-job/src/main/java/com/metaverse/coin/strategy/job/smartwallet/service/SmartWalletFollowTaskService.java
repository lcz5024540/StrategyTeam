package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletFollowTaskService {

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletProfileStoreService profileStoreService;
    private final PolymarketExecutionGateway executionGateway;
    private final SmartWalletSignalRiskService signalRiskService;
    private final PolymarketConsensusSignalService consensusSignalService;

    public SmartWalletTradeEventEntity recordTradeEvent(SmartWalletTradeEventEntity event) {
        SmartWalletTradeEventEntity existing = tradeEventMapper.selectOne(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getSource, event.getSource())
                .eq(SmartWalletTradeEventEntity::getExternalTradeId, event.getExternalTradeId())
                .last("limit 1"));

        if (existing != null) {
            return existing;
        }

        event.setProcessed(Boolean.FALSE);
        event.setCreatedAt(new Date());
        event.setUpdatedAt(new Date());
        tradeEventMapper.insert(event);
        createFollowTaskIfQualified(event);
        return event;
    }

    public List<SmartWalletFollowTaskView> listFollowTasks() {
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit 100"));
        List<SmartWalletFollowTaskView> result = new ArrayList<>();
        for (SmartWalletFollowTaskEntity task : tasks) {
            result.add(toView(task));
        }
        return result;
    }

    @Scheduled(fixedDelayString = "${smart-wallet.poll-fixed-delay-ms:5000}")
    public void processPendingTasks() {
        if (!properties.isEnabled() || !properties.isScanEnabled()) {
            return;
        }
        List<SmartWalletFollowTaskEntity> pending = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getStatus, "PENDING")
                .le(SmartWalletFollowTaskEntity::getScheduledAt, new Date())
                .orderByAsc(SmartWalletFollowTaskEntity::getScheduledAt)
                .last("limit 20"));
        for (SmartWalletFollowTaskEntity task : pending) {
            executeTask(task);
        }
    }

    public int processPendingTasksNow() {
        List<SmartWalletFollowTaskEntity> pending = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getStatus, "PENDING")
                .le(SmartWalletFollowTaskEntity::getScheduledAt, new Date())
                .orderByAsc(SmartWalletFollowTaskEntity::getScheduledAt)
                .last("limit 50"));
        for (SmartWalletFollowTaskEntity task : pending) {
            executeTask(task);
        }
        return pending.size();
    }

    private void createFollowTaskIfQualified(SmartWalletTradeEventEntity event) {
        SmartWalletProfileEntity profile = profileStoreService.listWatchEnabled().stream()
                .filter(item -> item.getWalletAddress().equalsIgnoreCase(event.getWalletAddress()))
                .findFirst()
                .orElse(null);
        if (profile == null) {
            return;
        }

        SmartWalletFollowTaskEntity existingTask = followTaskMapper.selectOne(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getSourceEventId, event.getId())
                .last("limit 1"));
        if (existingTask != null) {
            return;
        }

        SignalRiskDecision decision = signalRiskService.evaluate(profile, event);
        BigDecimal recommendedStake = decision.getRecommendedStakeUsdc() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : decision.getRecommendedStakeUsdc().setScale(2, RoundingMode.HALF_UP);

        SmartWalletFollowTaskEntity task = new SmartWalletFollowTaskEntity()
                .setWalletAddress(event.getWalletAddress())
                .setSourceEventId(event.getId())
                .setMarketId(event.getMarketId())
                .setQuestion(event.getQuestion())
                .setOutcome(event.getOutcome())
                .setSide(event.getSide())
                .setTriggerPrice(event.getPrice())
                .setTriggerSizeUsdc(event.getSizeUsdc())
                .setRecommendedStakeUsdc(recommendedStake)
                .setStatus(decision.isAccepted() ? "PENDING" : "SKIPPED")
                .setExecutionMode(properties.isAutoExecutionEnabled() ? "LIVE" : "PAPER")
                .setErrorMessage(String.join(" | ", decision.getReasons()))
                .setScheduledAt(new Date(System.currentTimeMillis() + properties.getFollowDelaySeconds() * 1000L))
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());
        followTaskMapper.insert(task);

        event.setProcessed(Boolean.TRUE);
        event.setUpdatedAt(new Date());
        tradeEventMapper.updateById(event);
    }

    private void executeTask(SmartWalletFollowTaskEntity task) {
        try {
            SmartWalletSignal signal = SmartWalletSignal.builder()
                    .signalId(String.valueOf(task.getId()))
                    .market(PredictionMarketOpportunity.builder()
                            .marketId(task.getMarketId())
                            .question(task.getQuestion())
                            .outcome(task.getOutcome())
                            .price(task.getTriggerPrice())
                            .build())
                    .followOutcome(task.getOutcome())
                    .recommendedStakeUsdc(task.getRecommendedStakeUsdc())
                    .build();
            ExecutionMode mode = "LIVE".equalsIgnoreCase(task.getExecutionMode()) ? ExecutionMode.LIVE : ExecutionMode.PAPER;
            SmartWalletExecutionResult result = executionGateway.execute(signal, mode);
            task.setStatus(result.isSuccess() ? "FOLLOWED" : "FAILED");
            task.setExternalOrderId(result.getExternalOrderId());
            task.setErrorMessage(result.isSuccess() ? null : result.getMessage());
            task.setExecutedAt(new Date());
            task.setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
        } catch (Exception e) {
            log.error("Failed to execute smart wallet follow task {}", task.getId(), e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            task.setExecutedAt(new Date());
            task.setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
        }
    }

    private SmartWalletFollowTaskView toView(SmartWalletFollowTaskEntity task) {
        ConsensusSignalView consensus = consensusSignalService.listRecentConsensusSignals().stream()
                .filter(item -> item.getMarketId() != null && item.getMarketId().equalsIgnoreCase(task.getMarketId()))
                .filter(item -> item.getOutcome() != null && item.getOutcome().equalsIgnoreCase(task.getOutcome()))
                .filter(item -> item.getSide() != null && item.getSide().equalsIgnoreCase(task.getSide()))
                .findFirst()
                .orElse(null);
        return SmartWalletFollowTaskView.builder()
                .id(task.getId())
                .walletAddress(task.getWalletAddress())
                .marketId(task.getMarketId())
                .question(task.getQuestion())
                .outcome(task.getOutcome())
                .side(task.getSide())
                .triggerPrice(task.getTriggerPrice())
                .triggerSizeUsdc(task.getTriggerSizeUsdc())
                .recommendedStakeUsdc(task.getRecommendedStakeUsdc())
                .status(task.getStatus())
                .executionMode(task.getExecutionMode())
                .externalOrderId(task.getExternalOrderId())
                .errorMessage(task.getErrorMessage())
                .consensusWallets(consensus == null ? null : consensus.getDistinctWallets())
                .consensusScore(consensus == null ? null : consensus.getConsensusScore())
                .scheduledAt(task.getScheduledAt())
                .executedAt(task.getExecutedAt())
                .build();
    }
}
