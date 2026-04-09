package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletBlockerStatView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationResult;
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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletFollowTaskService {

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletProfileStoreService profileStoreService;
    private final PolymarketExecutionGateway executionGateway;
    private final SmartWalletSignalRiskService signalRiskService;
    private final PolymarketConsensusSignalService consensusSignalService;
    private final SmartWalletAccountingService accountingService;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final SmartWalletTimeFormatter timeFormatter;

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
        return listFollowTasks(100, false);
    }

    public List<SmartWalletFollowTaskView> listFollowTasks(Integer limit, boolean includeRiskEvaluation) {
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + normalizeLimit(limit, 100)));
        List<SmartWalletFollowTaskView> result = new ArrayList<>();
        for (SmartWalletFollowTaskEntity task : tasks) {
            result.add(toView(task, includeRiskEvaluation ? evaluateTask(task.getId()) : null));
        }
        return result;
    }

    public List<SmartWalletFollowTaskView> listExecutableTasks(Integer limit) {
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "SKIPPED"))
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + normalizeLimit(limit, 50)));
        List<SmartWalletFollowTaskView> result = new ArrayList<>();
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTask(task.getId());
            if (!decision.isAccepted()) {
                continue;
            }
            result.add(toView(task, decision));
        }
        result.sort(this::compareTaskPriorityByEv);
        return result;
    }

    public List<SmartWalletFollowTaskView> listSingleWalletOverrideTasks(Integer limit) {
        return listSingleWalletOverrideTasks(limit, null, null, null);
    }

    public List<SmartWalletFollowTaskView> listSingleWalletOverrideTasks(Integer limit,
                                                                         SmartWalletProperties.SignalRisk overrideRisk,
                                                                         Integer taskScanLimit,
                                                                         String appliedPreset) {
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "SKIPPED"))
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + normalizeLimit(taskScanLimit == null ? (limit == null ? 100 : Math.max(limit * 4, 50)) : taskScanLimit, 100)));
        List<SmartWalletFollowTaskView> result = new ArrayList<>();
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTask(task.getId(), overrideRisk);
            if (!decision.isAccepted() || !decision.isOverrideApplied()) {
                continue;
            }
            result.add(toView(task, decision, appliedPreset));
        }
        result.sort(this::compareTaskPriorityByEv);
        return truncate(result, normalizeLimit(limit, 20));
    }

    public List<SmartWalletFollowTaskView> listOpportunityTasks(Integer limit, Integer maxBlockers, boolean includeExecutable) {
        return listOpportunityTasks(limit, maxBlockers, includeExecutable, null, null);
    }

    public List<SmartWalletFollowTaskView> listOpportunityTasks(Integer limit,
                                                                Integer maxBlockers,
                                                                boolean includeExecutable,
                                                                SmartWalletProperties.SignalRisk overrideRisk,
                                                                Integer taskScanLimit) {
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "SKIPPED"))
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + normalizeLimit(taskScanLimit == null ? (limit == null ? 100 : Math.max(limit * 4, 50)) : taskScanLimit, 100)));
        List<SmartWalletFollowTaskView> candidates = new ArrayList<>();
        int blockerThreshold = maxBlockers == null || maxBlockers < 0 ? 3 : maxBlockers;
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTask(task.getId(), overrideRisk);
            if (!includeExecutable && decision.isAccepted()) {
                continue;
            }
            int blockerCount = decision.getReasons() == null ? 0 : decision.getReasons().size();
            if (!decision.isAccepted() && blockerCount > blockerThreshold) {
                continue;
            }
            candidates.add(toView(task, decision));
        }
        candidates.sort(this::compareTaskPriorityByEv);
        return candidates.size() <= normalizeLimit(limit, 20)
                ? candidates
                : new ArrayList<>(candidates.subList(0, normalizeLimit(limit, 20)));
    }

    public List<SmartWalletBlockerStatView> blockerStats(Integer taskLimit) {
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "SKIPPED"))
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + normalizeLimit(taskLimit, 200)));
        Map<String, MutableBlockerStat> grouped = new LinkedHashMap<>();
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTask(task.getId());
            List<String> categories = classifyBlockerCategories(decision);
            if (categories.isEmpty()) {
                categories = List.of(decision != null && decision.isAccepted() ? "EXECUTABLE" : "OTHER");
            }
            for (String category : categories) {
                MutableBlockerStat stat = grouped.computeIfAbsent(category, key -> new MutableBlockerStat());
                stat.taskCount += 1;
                if (decision != null && decision.isAccepted()) {
                    stat.executableCount += 1;
                } else {
                    stat.blockedCount += 1;
                }
            }
        }
        List<SmartWalletBlockerStatView> result = new ArrayList<>();
        grouped.forEach((category, stat) -> result.add(SmartWalletBlockerStatView.builder()
                .blockerCategory(category)
                .taskCount(stat.taskCount)
                .executableCount(stat.executableCount)
                .blockedCount(stat.blockedCount)
                .build()));
        result.sort((left, right) -> Integer.compare(
                right.getTaskCount() == null ? 0 : right.getTaskCount(),
                left.getTaskCount() == null ? 0 : left.getTaskCount()
        ));
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

    public SmartWalletFollowTaskEntity getTask(Long taskId) {
        return taskId == null ? null : followTaskMapper.selectById(taskId);
    }

    public SmartWalletTradeEventEntity getSourceEvent(SmartWalletFollowTaskEntity task) {
        if (task == null || task.getSourceEventId() == null) {
            return null;
        }
        return tradeEventMapper.selectById(task.getSourceEventId());
    }

    public SmartWalletProfileEntity getWalletProfile(SmartWalletFollowTaskEntity task) {
        if (task == null || task.getWalletAddress() == null) {
            return null;
        }
        return profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, task.getWalletAddress())
                .eq(SmartWalletProfileEntity::getSource, "POLYMARKET")
                .last("limit 1"));
    }

    public SignalRiskDecision evaluateTask(Long taskId) {
        return evaluateTask(taskId, null);
    }

    public SignalRiskDecision evaluateTask(Long taskId, SmartWalletProperties.SignalRisk overrideRisk) {
        SmartWalletFollowTaskEntity task = requireTask(taskId);
        SmartWalletTradeEventEntity event = getSourceEvent(task);
        SmartWalletProfileEntity profile = getWalletProfile(task);
        if (event == null) {
            return SignalRiskDecision.builder()
                    .accepted(false)
                    .recommendedStakeUsdc(task.getRecommendedStakeUsdc())
                    .reasons(List.of("Source trade event is missing for this follow task."))
                    .build();
        }
        return signalRiskService.evaluate(profile, event, overrideRisk, task.getId());
    }

    public SmartWalletProperties.SignalRisk resolveSignalRiskPreset(String preset) {
        if (preset == null || preset.isBlank()) {
            return properties.getSignalRisk();
        }
        return properties.getSignalRiskPresets().getOrDefault(preset, properties.getSignalRisk());
    }

    public SmartWalletRiskSimulationResult simulateRisk(SmartWalletRiskSimulationRequest request) {
        SmartWalletRiskSimulationRequest applied = request == null ? new SmartWalletRiskSimulationRequest() : request;
        SmartWalletProperties.SignalRisk overrideRisk = buildSimulationRisk(applied);
        int scanLimit = normalizeLimit(applied.getTaskLimit(), 100);
        int resultLimit = normalizeLimit(applied.getResultLimit(), 20);
        int blockerThreshold = applied.getMaxBlockers() == null || applied.getMaxBlockers() < 0 ? 3 : applied.getMaxBlockers();
        boolean includeExecutable = Boolean.TRUE.equals(applied.getIncludeExecutable());

        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "SKIPPED"))
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + scanLimit));

        List<SmartWalletFollowTaskView> executable = new ArrayList<>();
        List<SmartWalletFollowTaskView> opportunities = new ArrayList<>();
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTask(task.getId(), overrideRisk);
            SmartWalletFollowTaskView view = toView(task, decision);
            if (decision.isAccepted()) {
                executable.add(view);
                if (includeExecutable) {
                    opportunities.add(view);
                }
                continue;
            }
            int blockerCount = decision.getReasons() == null ? 0 : decision.getReasons().size();
            if (blockerCount <= blockerThreshold) {
                opportunities.add(view);
            }
        }
        executable.sort(this::compareTaskPriorityByEv);
        opportunities.sort(this::compareTaskPriorityByEv);

        return SmartWalletRiskSimulationResult.builder()
                .applied(applied)
                .scannedTasks(tasks.size())
                .executableCount(executable.size())
                .opportunityCount(opportunities.size())
                .executableTasks(truncate(executable, resultLimit))
                .opportunityTasks(truncate(opportunities, resultLimit))
                .build();
    }

    public SmartWalletExecutionResult executeTaskNow(Long taskId, ExecutionMode requestedMode) {
        SmartWalletFollowTaskEntity task = requireTask(taskId);
        ExecutionMode mode = requestedMode == null ? ExecutionMode.PAPER : requestedMode;
        if (mode == ExecutionMode.LIVE) {
            if (!"PENDING".equalsIgnoreCase(task.getStatus())) {
                throw new IllegalStateException("Only PENDING follow tasks can be sent to LIVE execution.");
            }
            SignalRiskDecision decision = evaluateTask(taskId);
            if (!decision.isAccepted()) {
                throw new IllegalStateException("Follow task is not executable: " + String.join(" | ", decision.getReasons()));
            }
            task.setExecutionMode("LIVE");
            if (decision.getRecommendedStakeUsdc() != null && decision.getRecommendedStakeUsdc().compareTo(BigDecimal.ZERO) > 0) {
                task.setRecommendedStakeUsdc(decision.getRecommendedStakeUsdc().setScale(2, RoundingMode.HALF_UP));
            }
            task.setErrorMessage(null);
            task.setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
        }
        return executeTask(task);
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
        Long marketEndAt = resolveMarketEndAt(event.getMarketId(), event.getQuestion());

        SmartWalletFollowTaskEntity task = new SmartWalletFollowTaskEntity()
                .setWalletAddress(event.getWalletAddress())
                .setSourceEventId(event.getId())
                .setMarketId(event.getMarketId())
                .setQuestion(event.getQuestion())
                .setOutcome(event.getOutcome())
                .setMarketEndAt(marketEndAt)
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

    private SmartWalletExecutionResult executeTask(SmartWalletFollowTaskEntity task) {
        try {
            SignalRiskDecision decision = evaluateTask(task.getId());
            if (!decision.isAccepted()) {
                task.setStatus("SKIPPED");
                task.setErrorMessage(String.join(" | ", decision.getReasons()));
                task.setUpdatedAt(new Date());
                followTaskMapper.updateById(task);
                return SmartWalletExecutionResult.builder()
                        .signalId(String.valueOf(task.getId()))
                        .mode("LIVE".equalsIgnoreCase(task.getExecutionMode()) ? ExecutionMode.LIVE : ExecutionMode.PAPER)
                        .success(false)
                        .message(task.getErrorMessage())
                        .marketId(task.getMarketId())
                        .outcome(task.getOutcome())
                        .side(task.getSide())
                        .status("SKIPPED")
                        .price(task.getTriggerPrice())
                        .notionalUsdc(task.getRecommendedStakeUsdc())
                        .feeUsdc(BigDecimal.ZERO)
                        .build();
            }
            if (decision.getRecommendedStakeUsdc() != null && decision.getRecommendedStakeUsdc().compareTo(BigDecimal.ZERO) > 0) {
                task.setRecommendedStakeUsdc(decision.getRecommendedStakeUsdc().setScale(2, RoundingMode.HALF_UP));
            }
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
            accountingService.recordExecution(task, result);
            if (result.isSuccess() && mode == ExecutionMode.PAPER) {
                log.info(
                        "PAPER execution success followTaskId={} marketId={} outcome={} stakeUsdc={} executionMode=PAPER",
                        task.getId(),
                        task.getMarketId(),
                        task.getOutcome(),
                        task.getRecommendedStakeUsdc()
                );
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to execute smart wallet follow task {}", task.getId(), e);
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            task.setExecutedAt(new Date());
            task.setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
            SmartWalletExecutionResult failedResult = SmartWalletExecutionResult.builder()
                    .signalId(String.valueOf(task.getId()))
                    .mode("LIVE".equalsIgnoreCase(task.getExecutionMode()) ? ExecutionMode.LIVE : ExecutionMode.PAPER)
                    .success(false)
                    .message(e.getMessage())
                    .marketId(task.getMarketId())
                    .outcome(task.getOutcome())
                    .side(task.getSide())
                    .status("FAILED")
                    .price(task.getTriggerPrice())
                    .notionalUsdc(task.getRecommendedStakeUsdc())
                    .feeUsdc(BigDecimal.ZERO)
                    .build();
            accountingService.recordExecution(task, failedResult);
            return failedResult;
        }
    }

    private SmartWalletFollowTaskEntity requireTask(Long taskId) {
        SmartWalletFollowTaskEntity task = getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Smart-wallet follow task not found: " + taskId);
        }
        return task;
    }

    private SmartWalletFollowTaskView toView(SmartWalletFollowTaskEntity task, SignalRiskDecision decision) {
        return toView(task, decision, null);
    }

    private SmartWalletFollowTaskView toView(SmartWalletFollowTaskEntity task, SignalRiskDecision decision, String appliedPreset) {
        ConsensusSignalView consensus = findConsensus(task);
        Long marketEndAt = resolveDisplayMarketEndAt(task);
        List<String> blockerCategories = classifyBlockerCategories(decision);
        return SmartWalletFollowTaskView.builder()
                .id(task.getId())
                .walletAddress(task.getWalletAddress())
                .marketId(task.getMarketId())
                .question(task.getQuestion())
                .outcome(task.getOutcome())
                .marketEndAt(marketEndAt)
                .marketEndAtBjt(timeFormatter.formatEpochMillisToBjt(marketEndAt))
                .side(task.getSide())
                .triggerPrice(task.getTriggerPrice())
                .triggerSizeUsdc(task.getTriggerSizeUsdc())
                .recommendedStakeUsdc(task.getRecommendedStakeUsdc())
                .status(task.getStatus())
                .executionMode(task.getExecutionMode())
                .externalOrderId(task.getExternalOrderId())
                .errorMessage(task.getErrorMessage())
                .consensusWallets(decision == null
                        ? (consensus == null ? null : consensus.getDistinctWallets())
                        : decision.getConsensusWallets())
                .consensusScore(decision == null
                        ? (consensus == null ? null : consensus.getConsensusScore())
                        : decision.getConsensusScore())
                .expectedNetEvUsdc(decision == null ? null : decision.getExpectedNetEvUsdc())
                .estimatedFeeUsdc(decision == null ? null : decision.getEstimatedFeeUsdc())
                .estimatedSlippageUsdc(decision == null ? null : decision.getEstimatedSlippageUsdc())
                .evSampleSize(decision == null ? null : decision.getEvSampleSize())
                .taskExecutable(decision == null ? null : decision.isAccepted())
                .overrideApplied(decision == null ? null : decision.isOverrideApplied())
                .appliedPreset(appliedPreset)
                .blockerCount(decision == null || decision.getReasons() == null ? null : decision.getReasons().size())
                .primaryBlockerCategory(blockerCategories.isEmpty() ? null : blockerCategories.get(0))
                .blockerCategories(blockerCategories.isEmpty() ? null : blockerCategories)
                .riskReasons(decision == null ? null : decision.getReasons())
                .scheduledAt(task.getScheduledAt())
                .executedAt(task.getExecutedAt())
                .build();
    }

    private Long resolveDisplayMarketEndAt(SmartWalletFollowTaskEntity task) {
        if (task == null) {
            return null;
        }
        if (task.getMarketEndAt() != null && task.getMarketEndAt() > 0) {
            return task.getMarketEndAt();
        }
        Long marketEndAt = resolveMarketEndAt(task.getMarketId(), task.getQuestion());
        if (marketEndAt != null && marketEndAt > 0 && task.getId() != null) {
            task.setMarketEndAt(marketEndAt);
            task.setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
        }
        return marketEndAt;
    }

    private Long resolveMarketEndAt(String marketId, String questionHint) {
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(marketId, false, questionHint);
            return metadata == null ? null : metadata.getEndAt();
        } catch (Exception e) {
            log.debug("Failed to resolve market end time for follow task marketId={}: {}", marketId, e.getMessage());
            return null;
        }
    }

    private List<String> classifyBlockerCategories(SignalRiskDecision decision) {
        if (decision == null || decision.getReasons() == null || decision.getReasons().isEmpty()) {
            return List.of();
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String reason : decision.getReasons()) {
            String category = classifyReason(reason);
            if (category != null) {
                categories.add(category);
            }
        }
        return new ArrayList<>(categories);
    }

    private int compareTaskPriorityByEv(SmartWalletFollowTaskView left, SmartWalletFollowTaskView right) {
        int blockerCompare = Integer.compare(
                left == null || left.getBlockerCount() == null ? Integer.MAX_VALUE : left.getBlockerCount(),
                right == null || right.getBlockerCount() == null ? Integer.MAX_VALUE : right.getBlockerCount());
        if (blockerCompare != 0) {
            return blockerCompare;
        }

        BigDecimal leftEv = left == null || left.getExpectedNetEvUsdc() == null ? BigDecimal.valueOf(-999999) : left.getExpectedNetEvUsdc();
        BigDecimal rightEv = right == null || right.getExpectedNetEvUsdc() == null ? BigDecimal.valueOf(-999999) : right.getExpectedNetEvUsdc();
        int evCompare = rightEv.compareTo(leftEv);
        if (evCompare != 0) {
            return evCompare;
        }

        BigDecimal leftStake = left == null || left.getRecommendedStakeUsdc() == null ? BigDecimal.ZERO : left.getRecommendedStakeUsdc();
        BigDecimal rightStake = right == null || right.getRecommendedStakeUsdc() == null ? BigDecimal.ZERO : right.getRecommendedStakeUsdc();
        return rightStake.compareTo(leftStake);
    }

    private String classifyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String text = reason.toLowerCase(Locale.ROOT);
        if (text.contains("open position count") || text.contains("portfolio limit")) {
            return "POSITION_LIMIT";
        }
        if (text.contains("total open position cost") || text.contains("portfolio budget")) {
            return "CAPITAL_LIMIT";
        }
        if (text.contains("market end time is too far away") || text.contains("past expected end time")) {
            return "MARKET_TIMING";
        }
        if (text.contains("open position already exists")) {
            if (text.contains("different outcome")) {
                return "OPPOSITE_MARKET_POSITION";
            }
            return "DUPLICATE_POSITION";
        }
        if (text.contains("pending follow task already exists")) {
            if (text.contains("different outcome")) {
                return "OPPOSITE_MARKET_TASK";
            }
            return "DUPLICATE_TASK";
        }
        if (text.contains("signal is stale")) {
            return "SIGNAL_FRESHNESS";
        }
        if (text.contains("consensus wallet count")) {
            return "CONSENSUS_WALLETS";
        }
        if (text.contains("consensus score")) {
            return "CONSENSUS_SCORE";
        }
        if (text.contains("trigger size")) {
            return "TRIGGER_SIZE";
        }
        if (text.contains("trigger price")) {
            return "PRICE_RANGE";
        }
        if (text.contains("minimum order")) {
            return "MIN_ORDER";
        }
        if (text.contains("wallet is not qualified")) {
            return "WATCHLIST";
        }
        if (text.contains("sell signals are disabled")) {
            return "SELL_DISABLED";
        }
        if (text.contains("source trade event is missing")) {
            return "SOURCE_EVENT";
        }
        return "OTHER";
    }

    private static class MutableBlockerStat {
        private int taskCount;
        private int executableCount;
        private int blockedCount;
    }

    private ConsensusSignalView findConsensus(SmartWalletFollowTaskEntity task) {
        return consensusSignalService.listRecentConsensusSignals().stream()
                .filter(item -> item.getMarketId() != null && item.getMarketId().equalsIgnoreCase(task.getMarketId()))
                .filter(item -> item.getOutcome() != null && item.getOutcome().equalsIgnoreCase(task.getOutcome()))
                .filter(item -> item.getSide() != null && item.getSide().equalsIgnoreCase(task.getSide()))
                .findFirst()
                .orElse(null);
    }

    private int normalizeLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, 200);
    }

    public SmartWalletProperties.SignalRisk buildSimulationRisk(SmartWalletRiskSimulationRequest request) {
        SmartWalletProperties.SignalRisk source = properties.getSignalRisk();
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setAllowSellSignal(request.getAllowSellSignal() == null ? source.isAllowSellSignal() : request.getAllowSellSignal());
        risk.setMaxSignalAgeSeconds(request.getMaxSignalAgeSeconds() == null ? source.getMaxSignalAgeSeconds() : request.getMaxSignalAgeSeconds());
        risk.setMinTriggerSizeUsdc(request.getMinTriggerSizeUsdc() == null ? source.getMinTriggerSizeUsdc() : request.getMinTriggerSizeUsdc());
        risk.setMinFollowPrice(request.getMinFollowPrice() == null ? source.getMinFollowPrice() : request.getMinFollowPrice());
        risk.setMaxFollowPrice(request.getMaxFollowPrice() == null ? source.getMaxFollowPrice() : request.getMaxFollowPrice());
        risk.setEnableConsensusGate(request.getEnableConsensusGate() == null ? source.isEnableConsensusGate() : request.getEnableConsensusGate());
        risk.setMinDistinctWallets(request.getMinDistinctWallets() == null ? source.getMinDistinctWallets() : request.getMinDistinctWallets());
        risk.setMinConsensusScore(request.getMinConsensusScore() == null ? source.getMinConsensusScore() : request.getMinConsensusScore());
        risk.setEnableSingleWalletOverride(request.getEnableSingleWalletOverride() == null ? source.isEnableSingleWalletOverride() : request.getEnableSingleWalletOverride());
        risk.setSingleWalletOverrideMinWalletScore(request.getSingleWalletOverrideMinWalletScore() == null ? source.getSingleWalletOverrideMinWalletScore() : request.getSingleWalletOverrideMinWalletScore());
        risk.setSingleWalletOverrideMinWinRate(request.getSingleWalletOverrideMinWinRate() == null ? source.getSingleWalletOverrideMinWinRate() : request.getSingleWalletOverrideMinWinRate());
        risk.setSingleWalletOverrideMinTrades(request.getSingleWalletOverrideMinTrades() == null ? source.getSingleWalletOverrideMinTrades() : request.getSingleWalletOverrideMinTrades());
        risk.setSingleWalletOverrideMinTriggerSizeUsdc(request.getSingleWalletOverrideMinTriggerSizeUsdc() == null ? source.getSingleWalletOverrideMinTriggerSizeUsdc() : request.getSingleWalletOverrideMinTriggerSizeUsdc());
        risk.setSingleWalletOverrideMinRecommendedStakeUsdc(request.getSingleWalletOverrideMinRecommendedStakeUsdc() == null ? source.getSingleWalletOverrideMinRecommendedStakeUsdc() : request.getSingleWalletOverrideMinRecommendedStakeUsdc());
        return risk;
    }

    private List<SmartWalletFollowTaskView> truncate(List<SmartWalletFollowTaskView> items, int limit) {
        return items.size() <= limit ? items : new ArrayList<>(items.subList(0, limit));
    }
}
