package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletFollowCandidateMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletFollowCandidateEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityResolutionResult;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.service.WalletEntityResolutionService;
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
import org.springframework.util.StringUtils;

import com.metaverse.coin.strategy.job.smartwallet.model.SignalHealthSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCapacitySummaryView;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

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
import java.util.TreeMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletFollowTaskService {

    private static final String LOG_PREFIX = "[POLY_FOLLOW]";

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
    // ---- Entity Intelligence 层（Phase 1 新增）----
    private final WalletEntityResolutionService entityResolutionService;
    private final WalletFollowCandidateMapper followCandidateMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    private final SmartWalletMarketStateService marketStateService;

    public SmartWalletTradeEventEntity recordTradeEvent(SmartWalletTradeEventEntity event) {
        log.info("{} action=记录Polymarket钱包交易事件 result=开始 wallet={} marketId={} outcome={} side={} externalTradeId={} price={} sizeUsdc={}",
                LOG_PREFIX, event.getWalletAddress(), event.getMarketId(), event.getOutcome(), event.getSide(),
                event.getExternalTradeId(), event.getPrice(), event.getSizeUsdc());
        SmartWalletTradeEventEntity existing = tradeEventMapper.selectOne(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getSource, event.getSource())
                .eq(SmartWalletTradeEventEntity::getExternalTradeId, event.getExternalTradeId())
                .last("limit 1"));

        if (existing != null) {
            log.info("{} action=记录Polymarket钱包交易事件 result=跳过重复事件 eventId={} wallet={} marketId={} outcome={} externalTradeId={}",
                    LOG_PREFIX, existing.getId(), existing.getWalletAddress(), existing.getMarketId(), existing.getOutcome(),
                    existing.getExternalTradeId());
            return existing;
        }

        event.setProcessed(Boolean.FALSE);
        event.setCreatedAt(new Date());
        event.setUpdatedAt(new Date());
        tradeEventMapper.insert(event);
        log.info("{} action=记录Polymarket钱包交易事件 result=已入库 eventId={} wallet={} marketId={} outcome={} side={}",
                LOG_PREFIX, event.getId(), event.getWalletAddress(), event.getMarketId(), event.getOutcome(), event.getSide());
        profileStoreService.recordObservedTradeActivity(event.getWalletAddress(), event.getOpenedAt());
        ensureMarketInfoForEvent(event);
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
        marketInfoService.populateFollowTasks(tasks);
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
        result.sort(this::compareTaskPriority);
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
        result.sort(this::compareTaskPriority);
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
        candidates.sort(this::compareTaskPriority);
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
            log.debug("{} action=扫描待执行跟单任务 result=跳过 reason=smart-wallet或scan未开启 enabled={} scanEnabled={}",
                    LOG_PREFIX, properties.isEnabled(), properties.isScanEnabled());
            return;
        }
        List<SmartWalletFollowTaskEntity> pending = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getStatus, "PENDING")
                .le(SmartWalletFollowTaskEntity::getScheduledAt, new Date())
                .orderByAsc(SmartWalletFollowTaskEntity::getScheduledAt)
                .last("limit 20"));
        log.info("{} action=扫描待执行跟单任务 result=查到批次 pendingCount={} limit={}",
                LOG_PREFIX, pending.size(), 20);
        for (SmartWalletFollowTaskEntity task : pending) {
            if (!claimTaskForExecution(task)) {
                log.debug("{} action=抢占跟单任务执行权 result=已被其他线程抢占 taskId={} statusBefore={}",
                        LOG_PREFIX, task.getId(), task.getStatus());
                continue;
            }
            executeTask(task);
        }
    }

    public int processPendingTasksNow() {
        List<SmartWalletFollowTaskEntity> pending = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getStatus, "PENDING")
                .le(SmartWalletFollowTaskEntity::getScheduledAt, new Date())
                .orderByAsc(SmartWalletFollowTaskEntity::getScheduledAt)
                .last("limit 50"));
        log.info("{} action=手动扫描待执行跟单任务 result=查到批次 pendingCount={} limit={}",
                LOG_PREFIX, pending.size(), 50);
        int executed = 0;
        for (SmartWalletFollowTaskEntity task : pending) {
            if (!claimTaskForExecution(task)) {
                log.debug("{} action=抢占跟单任务执行权 result=已被其他线程抢占 taskId={} statusBefore={}",
                        LOG_PREFIX, task.getId(), task.getStatus());
                continue;
            }
            executeTask(task);
            executed++;
        }
        return executed;
    }

    /**
     * CAS 乐观锁：原子性地将任务从 PENDING → EXECUTING，防止重复下单。
     * 使用 UPDATE ... WHERE id=? AND status='PENDING'，只有一个线程能成功（affected=1）。
     *
     * @return true 如果当前线程成功获取了任务执行权
     */
    private boolean claimTaskForExecution(SmartWalletFollowTaskEntity task) {
        int affected = followTaskMapper.update(null,
                new LambdaUpdateWrapper<SmartWalletFollowTaskEntity>()
                        .set(SmartWalletFollowTaskEntity::getStatus, "EXECUTING")
                        .set(SmartWalletFollowTaskEntity::getUpdatedAt, new Date())
                        .eq(SmartWalletFollowTaskEntity::getId, task.getId())
                        .eq(SmartWalletFollowTaskEntity::getStatus, "PENDING"));
        if (affected == 1) {
            task.setStatus("EXECUTING");
            log.info("{} action=抢占跟单任务执行权 result=成功 taskId={} wallet={} marketId={} outcome={} scheduledAt={}",
                    LOG_PREFIX, task.getId(), task.getWalletAddress(), task.getMarketId(), task.getOutcome(), task.getScheduledAt());
        } else {
            log.debug("{} action=抢占跟单任务执行权 result=失败 taskId={} expectedStatus=PENDING affectedRows={}",
                    LOG_PREFIX, task.getId(), affected);
        }
        return affected == 1;
    }

    @Scheduled(fixedDelayString = "${smart-wallet.intelligence.candidate-reevaluation-fixed-delay-ms:120000}")
    public void scheduledReevaluateDeferredCandidates() {
        try {
            reevaluateDeferredCandidates(properties.getIntelligence().getCandidateReevaluationBatchSize());
        } catch (Exception ex) {
            log.warn("{} action=定时重评延迟智能候选 result=失败 reason={}", LOG_PREFIX, ex.getMessage());
        }
    }

    public int reevaluateDeferredCandidates(Integer limit) {
        int batchSize = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        List<WalletFollowCandidateEntity> candidates = followCandidateMapper.selectList(
                new LambdaQueryWrapper<WalletFollowCandidateEntity>()
                        .in(WalletFollowCandidateEntity::getEnrichmentStatus, List.of("PENDING", "LOW_CONFIDENCE_LINK"))
                        .orderByAsc(WalletFollowCandidateEntity::getUpdatedAt)
                        .last("limit " + batchSize));
        log.info("{} action=重评延迟智能候选 result=开始 candidateCount={} batchSize={}",
                LOG_PREFIX, candidates.size(), batchSize);
        int updated = 0;
        for (WalletFollowCandidateEntity candidate : candidates) {
            if (candidate == null || candidate.getSourceEventId() == null) {
                continue;
            }
            SmartWalletTradeEventEntity event = tradeEventMapper.selectById(candidate.getSourceEventId());
            if (event == null) {
                continue;
            }
            if (refreshCandidateAndMaybeTask(candidate, event)) {
                updated++;
            }
        }
        log.info("{} action=重评延迟智能候选 result=完成 candidateCount={} updatedCount={}",
                LOG_PREFIX, candidates.size(), updated);
        return updated;
    }

    public IntelligenceReplaySummary replayRecentIntelligenceCandidates(Integer limit, boolean includeProcessed) {
        int batchSize = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                        .orderByDesc(SmartWalletTradeEventEntity::getId)
                        .last("limit " + batchSize));
        int scanned = 0;
        int changed = 0;
        int skipped = 0;
        for (SmartWalletTradeEventEntity event : events) {
            if (event == null || event.getId() == null) {
                continue;
            }
            scanned++;
            WalletFollowCandidateEntity beforeCandidate = findCandidateBySourceEventId(event.getId());
            SmartWalletFollowTaskEntity beforeTask = findTaskBySourceEventId(event.getId());
            boolean hadCandidate = beforeCandidate != null;
            boolean hadTask = beforeTask != null;
            boolean processed = Boolean.TRUE.equals(event.getProcessed());
            if (!includeProcessed && processed && hadCandidate && hadTask) {
                skipped++;
                continue;
            }
            createFollowTaskIfQualified(event);
            WalletFollowCandidateEntity afterCandidate = findCandidateBySourceEventId(event.getId());
            SmartWalletFollowTaskEntity afterTask = findTaskBySourceEventId(event.getId());
            SmartWalletTradeEventEntity refreshedEvent = tradeEventMapper.selectById(event.getId());
            boolean eventProcessed = refreshedEvent != null && Boolean.TRUE.equals(refreshedEvent.getProcessed());
            if ((!hadCandidate && afterCandidate != null) || (!hadTask && afterTask != null) || (!processed && eventProcessed)) {
                changed++;
            } else {
                skipped++;
            }
        }
        return IntelligenceReplaySummary.builder()
                .scanned(scanned)
                .changed(changed)
                .skipped(skipped)
                .build();
    }

    public SmartWalletFollowTaskEntity getTask(Long taskId) {
        return taskId == null ? null : marketInfoService.populate(followTaskMapper.selectById(taskId));
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
        executable.sort(this::compareTaskPriority);
        opportunities.sort(this::compareTaskPriority);

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
        log.info("{} action=根据交易事件生成跟单任务 result=开始 eventId={} wallet={} marketId={} outcome={} side={}",
                LOG_PREFIX, event.getId(), event.getWalletAddress(), event.getMarketId(), event.getOutcome(), event.getSide());
        SmartWalletProfileEntity profile = profileStoreService.listWatchEnabled().stream()
                .filter(item -> item.getWalletAddress().equalsIgnoreCase(event.getWalletAddress()))
                .findFirst()
                .orElse(null);
        if (profile == null) {
            log.info("{} action=根据交易事件生成跟单任务 result=跳过 reason=钱包未在可执行观察名单 eventId={} wallet={} marketId={} outcome={}",
                    LOG_PREFIX, event.getId(), event.getWalletAddress(), event.getMarketId(), event.getOutcome());
            return;
        }

        SmartWalletFollowTaskEntity existingTask = followTaskMapper.selectOne(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getSourceEventId, event.getId())
                .last("limit 1"));
        if (existingTask != null) {
            log.info("{} action=根据交易事件生成跟单任务 result=跳过重复任务 eventId={} followTaskId={} status={} wallet={} marketId={} outcome={}",
                    LOG_PREFIX, event.getId(), existingTask.getId(), existingTask.getStatus(), event.getWalletAddress(),
                    event.getMarketId(), event.getOutcome());
            return;
        }

        // ---- Step 1: Intelligence Enrich ----
        // 将钱包地址解析为实体 ID + 聚类置信度，写入 wallet_follow_candidate 作为中间记录
        WalletFollowCandidateEntity candidate = enrichAndSaveCandidate(event);
        log.info("{} action=智能增强候选信号 result={} eventId={} candidateId={} wallet={} entityId={} confidence={} firstSignal={} consensusEntities={} reason={}",
                LOG_PREFIX, candidate.getEnrichmentStatus(), event.getId(), candidate.getId(), event.getWalletAddress(),
                candidate.getEntityId(), candidate.getClusterConfidence(), candidate.getIsEntityFirstSignal(),
                candidate.getEntityConsensusDedupCount(), candidate.getRejectionReason());

        SmartWalletMarketInfoEntity marketInfo = ensureMarketInfoForEvent(event);
        SmartWalletMarketStateService.MarketStateSnapshot marketState = refreshMarketState(event);
        if (!marketState.isActive()) {
            createLifecycleSkippedTask(event, candidate, marketInfo, marketState);
            markSourceEventProcessed(event);
            log.info("{} action=根据交易事件生成跟单任务 result=已创建生命周期拦截任务 eventId={} candidateId={} marketId={} outcome={} reason={}",
                    LOG_PREFIX, event.getId(), candidate.getId(), event.getMarketId(), event.getOutcome(),
                    marketState.blockingReasonMessage());
            return;
        }

        if ("PENDING".equalsIgnoreCase(candidate.getEnrichmentStatus())) {
            createSkippedTaskForPendingCandidate(event, candidate, marketInfo);
            markSourceEventProcessed(event);
            log.info("{} action=根据交易事件生成跟单任务 result=已创建智能增强等待任务 eventId={} candidateId={} marketId={} outcome={} reason={}",
                    LOG_PREFIX, event.getId(), candidate.getId(), event.getMarketId(), event.getOutcome(),
                    candidate.getRejectionReason());
            return;
        }

        // ---- Step 2: Risk Evaluation（沿用原有逻辑，现在共识内部已用实体去重）----
        SignalRiskDecision decision = signalRiskService.evaluate(profile, event);
        log.info("{} action=评估跟单风控 result={} eventId={} wallet={} marketId={} outcome={} recommendedStakeUsdc={} reasons={}",
                LOG_PREFIX, decision.isAccepted() ? "通过" : "拒绝", event.getId(), event.getWalletAddress(),
                event.getMarketId(), event.getOutcome(), decision.getRecommendedStakeUsdc(), String.join(" | ", decision.getReasons()));
        BigDecimal recommendedStake = decision.getRecommendedStakeUsdc() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : decision.getRecommendedStakeUsdc().setScale(2, RoundingMode.HALF_UP);
        // ---- Step 3: Create Follow Task（附带实体维度字段）----
        SmartWalletFollowTaskEntity task = new SmartWalletFollowTaskEntity()
                .setWalletAddress(event.getWalletAddress())
                .setEntityId(candidate.getEntityId())
                .setFollowCandidateId(candidate.getId())
                .setSourceEventId(event.getId())
                .setMarketInfoId(marketInfo.getId())
                .setMarketInfo(marketInfo)
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
        log.info("{} action=创建跟单任务 result={} followTaskId={} eventId={} candidateId={} wallet={} entityId={} marketId={} outcome={} mode={} stakeUsdc={} scheduledAt={} reason={}",
                LOG_PREFIX, task.getStatus(), task.getId(), event.getId(), candidate.getId(), task.getWalletAddress(),
                task.getEntityId(), task.getMarketId(), task.getOutcome(), task.getExecutionMode(),
                task.getRecommendedStakeUsdc(), task.getScheduledAt(), task.getErrorMessage());

        // 回填 candidate 的 follow_task_id
        candidate.setFollowTaskId(task.getId())
                .setUpdatedAt(new Date());
        followCandidateMapper.updateById(candidate);

        markSourceEventProcessed(event);
        log.info("{} action=标记源交易事件已处理 result=完成 eventId={} followTaskId={} processed=true",
                LOG_PREFIX, event.getId(), task.getId());
    }

    /**
     * Intelligence Enrich：解析钱包地址对应的实体，生成并持久化 WalletFollowCandidate
     */
    private WalletFollowCandidateEntity enrichAndSaveCandidate(SmartWalletTradeEventEntity event) {
        // 防重：同一 event 已有 candidate 则直接返回
        WalletFollowCandidateEntity existing = followCandidateMapper.selectOne(
                new LambdaQueryWrapper<WalletFollowCandidateEntity>()
                        .eq(WalletFollowCandidateEntity::getSourceEventId, event.getId())
                        .last("limit 1"));
        if (existing != null) {
            log.info("{} action=智能增强候选信号 result=复用已有候选 eventId={} candidateId={} status={} entityId={} reason={}",
                    LOG_PREFIX, event.getId(), existing.getId(), existing.getEnrichmentStatus(), existing.getEntityId(),
                    existing.getRejectionReason());
            return existing;
        }

        EntityResolutionResult resolution = entityResolutionService.resolveEntity(event.getWalletAddress());
        String effectiveEntityId = resolveEffectiveEntityId(event, resolution);
        ConsensusSignalView consensus = consensusSignalService.buildConsensusForEvent(event);
        boolean isEntityFirstSignal = isEntityFirstSignal(event, effectiveEntityId);
        String enrichmentStatus = determineEnrichmentStatus(resolution, effectiveEntityId);
        String rejectionReason = determineCandidateReason(resolution, effectiveEntityId);

        Date now = new Date();
        WalletFollowCandidateEntity candidate = new WalletFollowCandidateEntity()
                .setSourceEventId(event.getId())
                .setWalletAddress(event.getWalletAddress())
                .setEntityId(effectiveEntityId)
                .setClusterConfidence(resolution.getConfidenceScore())
                .setClusterMethod(resolution.getClusterMethod())
                .setEntityWalletCount(resolution.getEntityWalletCount())
                .setIsEntityFirstSignal(isEntityFirstSignal)
                .setEntityConsensusDedupCount(consensus.getDedupedEntityCount())
                .setEnrichmentStatus(enrichmentStatus)
                .setRejectionReason(rejectionReason)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        followCandidateMapper.insert(candidate);
        log.info("{} action=智能增强候选信号 result=已入库 eventId={} candidateId={} wallet={} entityId={} status={} confidence={} method={} entityWalletCount={} firstSignal={} consensusEntities={} reason={}",
                LOG_PREFIX, event.getId(), candidate.getId(), candidate.getWalletAddress(), candidate.getEntityId(),
                candidate.getEnrichmentStatus(), candidate.getClusterConfidence(), candidate.getClusterMethod(),
                candidate.getEntityWalletCount(), candidate.getIsEntityFirstSignal(),
                candidate.getEntityConsensusDedupCount(), candidate.getRejectionReason());
        return candidate;
    }

    private boolean refreshCandidateAndMaybeTask(WalletFollowCandidateEntity candidate, SmartWalletTradeEventEntity event) {
        String previousStatus = candidate.getEnrichmentStatus();
        String previousReason = candidate.getRejectionReason();
        populateCandidateFields(candidate, event);
        candidate.setUpdatedAt(new Date());
        followCandidateMapper.updateById(candidate);

        SmartWalletFollowTaskEntity task = resolveLinkedFollowTask(candidate, event);
        if (!wasBlockedByIntelligence(task, previousReason)) {
            log.info("{} action=重评延迟智能候选 result=候选已更新但无需恢复任务 eventId={} candidateId={} followTaskId={} oldStatus={} newStatus={} reason={}",
                    LOG_PREFIX, event.getId(), candidate.getId(), task == null ? null : task.getId(), previousStatus,
                    candidate.getEnrichmentStatus(), candidate.getRejectionReason());
            return true;
        }

        if ("PENDING".equalsIgnoreCase(candidate.getEnrichmentStatus())) {
            if (task != null) {
                task.setErrorMessage(candidate.getRejectionReason());
                task.setUpdatedAt(new Date());
                followTaskMapper.updateById(task);
            }
            log.info("{} action=重评延迟智能候选 result=仍需等待 eventId={} candidateId={} followTaskId={} oldStatus={} newStatus={} reason={}",
                    LOG_PREFIX, event.getId(), candidate.getId(), task == null ? null : task.getId(), previousStatus,
                    candidate.getEnrichmentStatus(), candidate.getRejectionReason());
            return true;
        }

        SmartWalletProfileEntity profile = profileStoreService.listWatchEnabled().stream()
                .filter(item -> item.getWalletAddress().equalsIgnoreCase(event.getWalletAddress()))
                .findFirst()
                .orElse(null);
        if (profile == null || task == null) {
            log.info("{} action=重评延迟智能候选 result=无法恢复任务 eventId={} candidateId={} followTaskId={} reason={}",
                    LOG_PREFIX, event.getId(), candidate.getId(), task == null ? null : task.getId(),
                    profile == null ? "钱包不在可执行观察名单" : "任务不存在");
            return true;
        }

        ensureMarketInfoForEvent(event);
        SmartWalletMarketStateService.MarketStateSnapshot marketState = refreshMarketState(event);
        if (!marketState.isActive()) {
            task.setEntityId(candidate.getEntityId())
                    .setFollowCandidateId(candidate.getId())
                    .setStatus("SKIPPED")
                    .setErrorMessage(marketState.blockingReasonMessage())
                    .setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
            log.info("{} action=重评延迟智能候选 result=市场生命周期拦截 eventId={} candidateId={} followTaskId={} marketId={} outcome={} reason={}",
                    LOG_PREFIX, event.getId(), candidate.getId(), task.getId(), event.getMarketId(), event.getOutcome(),
                    marketState.blockingReasonMessage());
            return true;
        }

        SignalRiskDecision decision = signalRiskService.evaluate(profile, event);
        log.info("{} action=重评后复核跟单风控 result={} eventId={} candidateId={} followTaskId={} marketId={} outcome={} recommendedStakeUsdc={} reasons={}",
                LOG_PREFIX, decision.isAccepted() ? "通过" : "拒绝", event.getId(), candidate.getId(), task.getId(),
                event.getMarketId(), event.getOutcome(), decision.getRecommendedStakeUsdc(), String.join(" | ", decision.getReasons()));
        BigDecimal recommendedStake = decision.getRecommendedStakeUsdc() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : decision.getRecommendedStakeUsdc().setScale(2, RoundingMode.HALF_UP);
        task.setEntityId(candidate.getEntityId())
                .setFollowCandidateId(candidate.getId())
                .setRecommendedStakeUsdc(recommendedStake)
                .setStatus(decision.isAccepted() ? "PENDING" : "SKIPPED")
                .setErrorMessage(String.join(" | ", decision.getReasons()))
                .setScheduledAt(new Date(System.currentTimeMillis() + properties.getFollowDelaySeconds() * 1000L))
                .setUpdatedAt(new Date());
        followTaskMapper.updateById(task);
        log.info("{} action=重评延迟智能候选 result=已更新任务 eventId={} candidateId={} oldStatus={} newStatus={} followTaskId={} taskStatus={} stakeUsdc={} reason={}",
                LOG_PREFIX, candidate.getSourceEventId(), candidate.getId(), previousStatus,
                candidate.getEnrichmentStatus(), task.getId(), task.getStatus(), task.getRecommendedStakeUsdc(),
                task.getErrorMessage());
        return true;
    }

    private void populateCandidateFields(WalletFollowCandidateEntity candidate, SmartWalletTradeEventEntity event) {
        EntityResolutionResult resolution = entityResolutionService.resolveEntity(event.getWalletAddress());
        String effectiveEntityId = resolveEffectiveEntityId(event, resolution);
        ConsensusSignalView consensus = consensusSignalService.buildConsensusForEvent(event);
        candidate.setWalletAddress(event.getWalletAddress())
                .setEntityId(effectiveEntityId)
                .setClusterConfidence(resolution.getConfidenceScore())
                .setClusterMethod(resolution.getClusterMethod())
                .setEntityWalletCount(resolution.getEntityWalletCount())
                .setIsEntityFirstSignal(isEntityFirstSignal(event, effectiveEntityId))
                .setEntityConsensusDedupCount(consensus.getDedupedEntityCount())
                .setEnrichmentStatus(determineEnrichmentStatus(resolution, effectiveEntityId))
                .setRejectionReason(determineCandidateReason(resolution, effectiveEntityId));
    }

    private String resolveEffectiveEntityId(SmartWalletTradeEventEntity event, EntityResolutionResult resolution) {
        if (resolution == null) {
            return normalizeWalletEntityId(event == null ? null : event.getWalletAddress());
        }
        if (resolution.isPendingClustering()) {
            return normalizeWalletEntityId(event == null ? null : event.getWalletAddress());
        }
        if (!shouldTrustResolvedEntity(resolution)) {
            return normalizeWalletEntityId(event == null ? null : event.getWalletAddress());
        }
        return normalizeWalletEntityId(resolution.getEntityId());
    }

    private boolean shouldTrustResolvedEntity(EntityResolutionResult resolution) {
        if (resolution == null || !resolution.isResolvedFromMap()) {
            return false;
        }
        if (!StringUtils.hasText(resolution.getEntityId())
                || !StringUtils.hasText(resolution.getClusterMethod())
                || "SELF".equalsIgnoreCase(resolution.getClusterMethod())) {
            return false;
        }
        BigDecimal threshold = properties.getIntelligence().getMinClusterConfidenceForDedup();
        BigDecimal confidence = resolution.getConfidenceScore() == null ? BigDecimal.ZERO : resolution.getConfidenceScore();
        return confidence.compareTo(threshold == null ? BigDecimal.ZERO : threshold) >= 0;
    }

    private String determineEnrichmentStatus(EntityResolutionResult resolution, String effectiveEntityId) {
        if (resolution == null || resolution.isPendingClustering()) {
            return "PENDING";
        }
        if (!normalizeWalletEntityId(resolution.getEntityId()).equalsIgnoreCase(normalizeWalletEntityId(effectiveEntityId))) {
            return "LOW_CONFIDENCE_LINK";
        }
        return "ENRICHED";
    }

    private String determineCandidateReason(EntityResolutionResult resolution, String effectiveEntityId) {
        if (resolution == null) {
            return "Entity resolution returned null. Fallback to wallet-level identity.";
        }
        if (resolution.isPendingClustering()) {
            return "Entity resolution is pending. Follow task generation is deferred for this signal.";
        }
        if (!normalizeWalletEntityId(resolution.getEntityId()).equalsIgnoreCase(normalizeWalletEntityId(effectiveEntityId))) {
            return "Resolved entity link is below trust threshold. Fallback to wallet-level identity for this signal.";
        }
        return null;
    }

    private boolean isEntityFirstSignal(SmartWalletTradeEventEntity event, String effectiveEntityId) {
        if (event == null || !StringUtils.hasText(effectiveEntityId)) {
            return true;
        }
        List<String> walletAddresses = resolveWalletAddressesForEntity(effectiveEntityId);
        if (walletAddresses.isEmpty()) {
            walletAddresses = List.of(normalizeWalletEntityId(event.getWalletAddress()));
        }
        LambdaQueryWrapper<SmartWalletTradeEventEntity> query = new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .in(SmartWalletTradeEventEntity::getWalletAddress, walletAddresses)
                .eq(SmartWalletTradeEventEntity::getMarketId, event.getMarketId())
                .eq(SmartWalletTradeEventEntity::getOutcome, event.getOutcome())
                .eq(SmartWalletTradeEventEntity::getSide, event.getSide())
                .ne(event.getId() != null, SmartWalletTradeEventEntity::getId, event.getId())
                .lt(event.getOpenedAt() != null, SmartWalletTradeEventEntity::getOpenedAt, event.getOpenedAt())
                .last("limit 1");
        return tradeEventMapper.selectOne(query) == null;
    }

    private List<String> resolveWalletAddressesForEntity(String effectiveEntityId) {
        if (!StringUtils.hasText(effectiveEntityId)) {
            return List.of();
        }
        List<String> entityWallets = entityResolutionService.getWalletsByEntityId(effectiveEntityId);
        if (entityWallets == null || entityWallets.isEmpty()) {
            return List.of();
        }
        return entityWallets.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeWalletEntityId)
                .toList();
    }

    private void createSkippedTaskForPendingCandidate(SmartWalletTradeEventEntity event,
                                                      WalletFollowCandidateEntity candidate,
                                                      SmartWalletMarketInfoEntity marketInfo) {
        SmartWalletFollowTaskEntity task = new SmartWalletFollowTaskEntity()
                .setWalletAddress(event.getWalletAddress())
                .setEntityId(candidate.getEntityId())
                .setFollowCandidateId(candidate.getId())
                .setSourceEventId(event.getId())
                .setMarketInfoId(marketInfo.getId())
                .setMarketInfo(marketInfo)
                .setSide(event.getSide())
                .setTriggerPrice(event.getPrice())
                .setTriggerSizeUsdc(event.getSizeUsdc())
                .setRecommendedStakeUsdc(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .setStatus("SKIPPED")
                .setExecutionMode(properties.isAutoExecutionEnabled() ? "LIVE" : "PAPER")
                .setErrorMessage(candidate.getRejectionReason())
                .setScheduledAt(new Date())
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());
        followTaskMapper.insert(task);
        log.info("{} action=创建智能增强等待任务 result=SKIPPED followTaskId={} eventId={} candidateId={} wallet={} entityId={} marketId={} outcome={} reason={}",
                LOG_PREFIX, task.getId(), event.getId(), candidate.getId(), task.getWalletAddress(), task.getEntityId(),
                task.getMarketId(), task.getOutcome(), task.getErrorMessage());
        candidate.setFollowTaskId(task.getId())
                .setUpdatedAt(new Date());
        followCandidateMapper.updateById(candidate);
    }

    private void createLifecycleSkippedTask(SmartWalletTradeEventEntity event,
                                            WalletFollowCandidateEntity candidate,
                                            SmartWalletMarketInfoEntity marketInfo,
                                            SmartWalletMarketStateService.MarketStateSnapshot marketState) {
        SmartWalletFollowTaskEntity task = new SmartWalletFollowTaskEntity()
                .setWalletAddress(event.getWalletAddress())
                .setEntityId(candidate == null ? null : candidate.getEntityId())
                .setFollowCandidateId(candidate == null ? null : candidate.getId())
                .setSourceEventId(event.getId())
                .setMarketInfoId(marketInfo == null ? null : marketInfo.getId())
                .setMarketInfo(marketInfo)
                .setSide(event.getSide())
                .setTriggerPrice(event.getPrice())
                .setTriggerSizeUsdc(event.getSizeUsdc())
                .setRecommendedStakeUsdc(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .setStatus("SKIPPED")
                .setExecutionMode(properties.isAutoExecutionEnabled() ? "LIVE" : "PAPER")
                .setErrorMessage(marketState.blockingReasonMessage())
                .setScheduledAt(new Date())
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());
        followTaskMapper.insert(task);
        log.info("{} action=创建生命周期拦截任务 result=SKIPPED followTaskId={} eventId={} candidateId={} wallet={} entityId={} marketId={} outcome={} reason={}",
                LOG_PREFIX, task.getId(), event.getId(), candidate == null ? null : candidate.getId(),
                task.getWalletAddress(), task.getEntityId(), task.getMarketId(), task.getOutcome(), task.getErrorMessage());
        if (candidate != null) {
            candidate.setFollowTaskId(task.getId())
                    .setUpdatedAt(new Date());
            followCandidateMapper.updateById(candidate);
        }
    }

    private WalletFollowCandidateEntity findCandidateBySourceEventId(Long sourceEventId) {
        if (sourceEventId == null) {
            return null;
        }
        return followCandidateMapper.selectOne(new LambdaQueryWrapper<WalletFollowCandidateEntity>()
                .eq(WalletFollowCandidateEntity::getSourceEventId, sourceEventId)
                .last("limit 1"));
    }

    private SmartWalletFollowTaskEntity findTaskBySourceEventId(Long sourceEventId) {
        if (sourceEventId == null) {
            return null;
        }
        return followTaskMapper.selectOne(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getSourceEventId, sourceEventId)
                .last("limit 1"));
    }

    private SmartWalletFollowTaskEntity resolveLinkedFollowTask(WalletFollowCandidateEntity candidate, SmartWalletTradeEventEntity event) {
        if (candidate != null && candidate.getFollowTaskId() != null) {
            SmartWalletFollowTaskEntity linked = followTaskMapper.selectById(candidate.getFollowTaskId());
            if (linked != null) {
                return linked;
            }
        }
        return event == null ? null : findTaskBySourceEventId(event.getId());
    }

    private boolean wasBlockedByIntelligence(SmartWalletFollowTaskEntity task, String previousReason) {
        if (task == null || !"SKIPPED".equalsIgnoreCase(task.getStatus())) {
            return false;
        }
        String errorMessage = task.getErrorMessage();
        if (!StringUtils.hasText(errorMessage)) {
            return false;
        }
        return errorMessage.contains("Entity resolution is pending")
                || errorMessage.contains("below trust threshold")
                || (StringUtils.hasText(previousReason) && errorMessage.contains(previousReason));
    }

    private String normalizeWalletEntityId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private SmartWalletExecutionResult executeTask(SmartWalletFollowTaskEntity task) {
        try {
            marketInfoService.populate(task);
            log.info("{} action=执行跟单任务 result=开始 followTaskId={} wallet={} marketId={} outcome={} mode={} status={} triggerPrice={} stakeUsdc={}",
                    LOG_PREFIX, task == null ? null : task.getId(), task == null ? null : task.getWalletAddress(),
                    task == null ? null : task.getMarketId(), task == null ? null : task.getOutcome(),
                    task == null ? null : task.getExecutionMode(), task == null ? null : task.getStatus(),
                    task == null ? null : task.getTriggerPrice(), task == null ? null : task.getRecommendedStakeUsdc());
            if (task == null || !StringUtils.hasText(task.getMarketId()) || !StringUtils.hasText(task.getOutcome())) {
                throw new IllegalStateException("Follow task market info is missing before execution. taskId="
                        + (task == null ? null : task.getId())
                        + ", marketInfoId=" + (task == null ? null : task.getMarketInfoId()));
            }
            SignalRiskDecision decision = evaluateTask(task.getId());
            if (!decision.isAccepted()) {
                task.setStatus("SKIPPED");
                task.setErrorMessage(String.join(" | ", decision.getReasons()));
                task.setUpdatedAt(new Date());
                followTaskMapper.updateById(task);
                log.info("{} action=执行前复核风控 result=拒绝并跳过 followTaskId={} wallet={} marketId={} outcome={} stakeUsdc={} reasons={}",
                        LOG_PREFIX, task.getId(), task.getWalletAddress(), task.getMarketId(), task.getOutcome(),
                        task.getRecommendedStakeUsdc(), task.getErrorMessage());
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
                        .tradingFeeUsdc(BigDecimal.ZERO)
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
            log.info("{} action=提交跟单执行网关 result=开始 followTaskId={} mode={} marketId={} outcome={} price={} stakeUsdc={}",
                    LOG_PREFIX, task.getId(), mode, task.getMarketId(), task.getOutcome(), task.getTriggerPrice(),
                    task.getRecommendedStakeUsdc());
            SmartWalletExecutionResult result = executionGateway.execute(signal, mode);
            log.info("{} action=提交跟单执行网关 result={} followTaskId={} mode={} marketId={} outcome={} externalOrderId={} orderStatus={} message={}",
                    LOG_PREFIX, result.isSuccess() ? "成功" : "失败", task.getId(), mode, task.getMarketId(),
                    task.getOutcome(), result.getExternalOrderId(), result.getStatus(), result.getMessage());
            task.setStatus(result.isSuccess() ? "FOLLOWED" : "FAILED");
            task.setExternalOrderId(result.getExternalOrderId());
            task.setErrorMessage(result.isSuccess() ? null : result.getMessage());
            task.setExecutedAt(new Date());
            task.setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
            accountingService.recordExecution(task, result);
            log.info("{} action=记录跟单执行结果到账本 result=完成 followTaskId={} finalStatus={} mode={} externalOrderId={} notionalUsdc={} feeUsdc={}",
                    LOG_PREFIX, task.getId(), task.getStatus(), mode, result.getExternalOrderId(),
                    result.getNotionalUsdc(), result.getFeeUsdc());
            if (result.isSuccess() && mode == ExecutionMode.PAPER) {
                log.info(
                        "{} action=PAPER模拟跟单 result=成功 followTaskId={} marketId={} outcome={} stakeUsdc={} executionMode=PAPER",
                        LOG_PREFIX,
                        task.getId(),
                        task.getMarketId(),
                        task.getOutcome(),
                        task.getRecommendedStakeUsdc()
                );
            }
            return result;
        } catch (Exception e) {
            log.error("{} action=执行跟单任务 result=异常 followTaskId={} wallet={} marketId={} outcome={} mode={} reason={}",
                    LOG_PREFIX, task == null ? null : task.getId(), task == null ? null : task.getWalletAddress(),
                    task == null ? null : task.getMarketId(), task == null ? null : task.getOutcome(),
                    task == null ? null : task.getExecutionMode(), e.getMessage(), e);
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
                    .tradingFeeUsdc(BigDecimal.ZERO)
                    .build();
            accountingService.recordExecution(task, failedResult);
            log.info("{} action=记录跟单异常结果到账本 result=完成 followTaskId={} finalStatus=FAILED reason={}",
                    LOG_PREFIX, task.getId(), e.getMessage());
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
        marketInfoService.populate(task);
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
                .estimatedFeeUsdc(decision == null ? null : decision.getEstimatedFeeUsdc())
                .estimatedSlippageUsdc(decision == null ? null : decision.getEstimatedSlippageUsdc())
                .followProbability(decision == null ? null : decision.getFollowProbability())
                .calibratedFollowProbability(decision == null ? null : decision.getCalibratedFollowProbability())
                .slippageSpikeProbability(decision == null ? null : decision.getSlippageSpikeProbability())
                .estimateSampleSize(decision == null ? null : decision.getEstimateSampleSize())
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
            // Update market_info table via marketInfoService instead of task entity
            if (task.getMarketInfoId() != null) {
                SmartWalletMarketInfoEntity marketInfo = marketInfoService.getById(task.getMarketInfoId());
                if (marketInfo != null) {
                    marketInfo.setMarketEndAt(marketEndAt);
                    marketInfo.setUpdatedAt(new Date());
                    // Note: SmartWalletMarketInfoService should have an update method
                    // For now, mark the task as updated to reflect the change
                }
            }
        }
        return marketEndAt;
    }

    private SmartWalletMarketInfoEntity ensureMarketInfoForEvent(SmartWalletTradeEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getMarketId()) || !StringUtils.hasText(event.getOutcome())) {
            return null;
        }
        Long marketEndAt = resolveMarketEndAt(event.getMarketId(), event.getQuestion());
        SmartWalletMarketInfoEntity marketInfo = marketInfoService.getOrCreate(
                event.getMarketId(),
                event.getOutcome(),
                event.getQuestion(),
                null,
                null,
                marketEndAt
        );
        refreshMarketState(event);
        return marketInfoService.getById(marketInfo.getId());
    }

    private SmartWalletMarketStateService.MarketStateSnapshot refreshMarketState(SmartWalletTradeEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getMarketId())) {
            return SmartWalletMarketStateService.MarketStateSnapshot.unknown(null);
        }
        return marketStateService.refreshMarket(
                event.getMarketId(),
                event.getQuestion(),
                StringUtils.hasText(event.getOutcome()) ? List.of(event.getOutcome()) : List.of()
        );
    }

    private void markSourceEventProcessed(SmartWalletTradeEventEntity event) {
        if (event == null || event.getId() == null) {
            return;
        }
        event.setProcessed(Boolean.TRUE);
        event.setUpdatedAt(new Date());
        tradeEventMapper.updateById(event);
    }

    private Long resolveMarketEndAt(String marketId, String questionHint) {
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(marketId, false, questionHint);
            return metadata == null ? null : metadata.getEndAt();
        } catch (Exception e) {
            log.debug("{} action=解析跟单市场结束时间 result=失败 marketId={} reason={}",
                    LOG_PREFIX, marketId, e.getMessage());
            return null;
        }
    }

    // ── 诊断：信号健康汇总 ────────────────────────────────────────────────────

    /**
     * 汇总过去 lookbackHours 小时内的信号漏斗状态，用于诊断"为什么策略没有开仓"。
     * 读取 DB 中已保存的 errorMessage，不重新调用风控引擎（避免高开销）。
     */
    public SignalHealthSummaryView getSignalHealthSummary(int lookbackHours) {
        int hours = Math.min(Math.max(lookbackHours, 1), 72);
        Date since = new Date(System.currentTimeMillis() - (long) hours * 3600_000L);

        // ── 1. 资金 & 持仓状态（via accountingService）
        SmartWalletCapacitySummaryView capacity = accountingService.capacitySummary();
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();

        // ── 2. 有效 watchlist 数量
        int qualifiedWatchlistCount = profileStoreService.listWatchEnabled().size();

        // ── 3. 信号漏斗：查询时间窗口内的任务（按状态分组）
        List<SmartWalletFollowTaskEntity> windowTasks = followTaskMapper.selectList(
                new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                        .ge(SmartWalletFollowTaskEntity::getCreatedAt, since));

        Map<String, Long> tasksByStatus = new TreeMap<>();
        long skippedCount = 0;
        long followedCount = 0;
        for (SmartWalletFollowTaskEntity task : windowTasks) {
            String status = task.getStatus() == null ? "UNKNOWN" : task.getStatus().toUpperCase(Locale.ROOT);
            tasksByStatus.merge(status, 1L, Long::sum);
            if ("SKIPPED".equals(status)) skippedCount++;
            if ("FOLLOWED".equals(status)) followedCount++;
        }

        // ── 4. 拒绝原因分析（仅读 errorMessage，不重新评估）
        Map<String, Long> rejectionCategories = new TreeMap<>();
        for (SmartWalletFollowTaskEntity task : windowTasks) {
            if (!"SKIPPED".equalsIgnoreCase(task.getStatus())) {
                continue;
            }
            String msg = task.getErrorMessage();
            if (msg == null || msg.isBlank()) {
                rejectionCategories.merge("OTHER", 1L, Long::sum);
                continue;
            }
            for (String segment : msg.split("\\s*\\|\\s*")) {
                if (segment.isBlank()) continue;
                String category = classifyReason(segment.trim());
                if (category != null) {
                    rejectionCategories.merge(category, 1L, Long::sum);
                }
            }
        }

        // ── 5. 已实现盈亏（via accounting summary）
        BigDecimal realizedPnl = null;
        try {
            realizedPnl = accountingService.summary().getRealizedPnlUsdc();
        } catch (Exception ignored) {
            // summary() 可能依赖复杂聚合，fail-open
        }

        // ── 6. 近期链上事件数
        long recentEventCount = tradeEventMapper.selectCount(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .ge(SmartWalletTradeEventEntity::getCreatedAt, since));

        // ── 6. 共识状态
        List<ConsensusSignalView> consensusSignals = consensusSignalService.listRecentConsensusSignals();
        int consensusWindowSec = risk.getConsensusWindowSeconds() == null ? 180 : risk.getConsensusWindowSeconds();
        int minEntities = risk.getMinDistinctWallets() == null ? 2 : risk.getMinDistinctWallets();

        // ── 7. 健康判断
        BigDecimal available = capacity.getAvailableCapitalUsdc();
        boolean criticalBlock = false;
        String primaryBlock = null;
        if (available != null && available.compareTo(BigDecimal.ZERO) <= 0) {
            criticalBlock = true;
            primaryBlock = "availableCapital ≤ 0 (current=" + available.setScale(2, RoundingMode.HALF_UP) + " USDC)";
        } else if (qualifiedWatchlistCount == 0) {
            criticalBlock = true;
            primaryBlock = "qualifiedWatchlistCount = 0: no wallets are watch_enabled + qualified";
        }

        return SignalHealthSummaryView.builder()
                .lookbackHours(hours)
                .capturedAt(new Date())
                .availableCapitalUsdc(available)
                .maxTotalOpenCostUsdc(risk.getMaxTotalOpenCostUsdc())
                .openCostUsdc(capacity.getTotalOpenCostUsdc())
                .realizedPnlUsdc(realizedPnl)
                .openPositionCount(capacity.getOpenPositionCount() == null ? 0 : capacity.getOpenPositionCount())
                .maxOpenPositions(risk.getMaxOpenPositions() == null ? 0 : risk.getMaxOpenPositions())
                .qualifiedWatchlistCount(qualifiedWatchlistCount)
                .recentTradeEventCount(recentEventCount)
                .tasksByStatus(tasksByStatus)
                .followedTaskCount(followedCount)
                .skippedTaskCount(skippedCount)
                .topRejectionCategories(rejectionCategories)
                .consensusSignalCount(consensusSignals.size())
                .consensusWindowSeconds(consensusWindowSec)
                .consensusGateEnabled(risk.isEnableConsensusGate())
                .minDistinctEntities(minEntities)
                .criticalBlockDetected(criticalBlock)
                .primaryBlockReason(primaryBlock)
                .build();
    }

    // ── 自动过期：清理长时间滞留的 PENDING 任务 ────────────────────────────

    /**
     * 每 5 分钟扫描一次，将超过最大信号年龄 5 倍时间仍处于 PENDING 状态的任务标记为 SKIPPED。
     * 防止僵尸任务永久占用系统资源。
     */
    @Scheduled(fixedDelayString = "${polymarket.smart-wallet.pending-expire-delay-ms:300000}")
    public void expireStalePendingTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            doExpireStalePendingTasks();
        } catch (Exception e) {
            log.error("{} action=过期待执行跟单任务 result=异常 reason={}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    private void doExpireStalePendingTasks() {
        int maxSignalAgeSeconds = properties.getSignalRisk().getMaxSignalAgeSeconds() == null
                ? 300 : properties.getSignalRisk().getMaxSignalAgeSeconds();
        long expiryMs = (long) maxSignalAgeSeconds * 5 * 1000L;
        Date expiryThreshold = new Date(System.currentTimeMillis() - expiryMs);

        List<SmartWalletFollowTaskEntity> staleTasks = followTaskMapper.selectList(
                new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                        .in(SmartWalletFollowTaskEntity::getStatus, List.of("PENDING", "EXECUTING"))
                        .le(SmartWalletFollowTaskEntity::getCreatedAt, expiryThreshold)
                        .last("limit 100"));

        if (staleTasks.isEmpty()) {
            return;
        }

        int expired = 0;
        for (SmartWalletFollowTaskEntity task : staleTasks) {
            try {
                SmartWalletFollowTaskEntity update = new SmartWalletFollowTaskEntity();
                update.setId(task.getId());
                update.setStatus("SKIPPED");
                update.setErrorMessage("[AUTO_EXPIRED] Task exceeded maximum age (" + (expiryMs / 1000) + "s). createdAt=" + task.getCreatedAt());
                update.setUpdatedAt(new Date());
                followTaskMapper.updateById(update);
                expired++;
            } catch (Exception e) {
                log.debug("{} action=过期单个跟单任务 result=失败 taskId={} reason={}",
                        LOG_PREFIX, task.getId(), e.getMessage());
            }
        }
        if (expired > 0) {
            log.info("{} action=过期待执行跟单任务 result=完成 expiredCount={} thresholdSeconds={}",
                    LOG_PREFIX, expired, expiryMs / 1000);
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

    private int compareTaskPriority(SmartWalletFollowTaskView left, SmartWalletFollowTaskView right) {
        int blockerCompare = Integer.compare(
                left == null || left.getBlockerCount() == null ? Integer.MAX_VALUE : left.getBlockerCount(),
                right == null || right.getBlockerCount() == null ? Integer.MAX_VALUE : right.getBlockerCount());
        if (blockerCompare != 0) {
            return blockerCompare;
        }

        BigDecimal leftProbability = left == null || left.getCalibratedFollowProbability() == null
                ? BigDecimal.ZERO
                : left.getCalibratedFollowProbability();
        BigDecimal rightProbability = right == null || right.getCalibratedFollowProbability() == null
                ? BigDecimal.ZERO
                : right.getCalibratedFollowProbability();
        int probabilityCompare = rightProbability.compareTo(leftProbability);
        if (probabilityCompare != 0) {
            return probabilityCompare;
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
        if (text.contains("capital is depleted") || text.contains("bankroll penetration")) {
            return "CAPITAL_DEPLETED";
        }
        if (text.contains("[concentration]") || text.contains("[concentration_penalty]")
                || text.contains("[extreme_concentration]") || text.contains("[extreme_concentration_downsize]")
                || text.contains("[extreme_concentration_warn]")
                || text.contains("[concentration_downsize]") || text.contains("[concentration_warn]")
                || text.contains("[whale_signal]")) {
            return "CONCENTRATION";
        }
        if (text.contains("[auto_expired]")) {
            return "EXPIRED";
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
        risk.setPolicyTag(request.getPolicyTag() == null ? source.getPolicyTag() : request.getPolicyTag());
        risk.setAllowSellSignal(request.getAllowSellSignal() == null ? source.isAllowSellSignal() : request.getAllowSellSignal());
        risk.setMaxSignalAgeSeconds(request.getMaxSignalAgeSeconds() == null ? source.getMaxSignalAgeSeconds() : request.getMaxSignalAgeSeconds());
        risk.setMinTriggerSizeUsdc(request.getMinTriggerSizeUsdc() == null ? source.getMinTriggerSizeUsdc() : request.getMinTriggerSizeUsdc());
        risk.setMinFollowPrice(request.getMinFollowPrice() == null ? source.getMinFollowPrice() : request.getMinFollowPrice());
        risk.setMaxFollowPrice(request.getMaxFollowPrice() == null ? source.getMaxFollowPrice() : request.getMaxFollowPrice());
        risk.setEnableConsensusGate(request.getEnableConsensusGate() == null ? source.isEnableConsensusGate() : request.getEnableConsensusGate());
        risk.setMinDistinctWallets(request.getMinDistinctWallets() == null ? source.getMinDistinctWallets() : request.getMinDistinctWallets());
        risk.setMinDistinctEntities(request.getMinDistinctEntities() == null ? source.getMinDistinctEntities() : request.getMinDistinctEntities());
        risk.setMinConsensusScore(request.getMinConsensusScore() == null ? source.getMinConsensusScore() : request.getMinConsensusScore());
        risk.setQualityWeightedConsensusEnabled(request.getQualityWeightedConsensusEnabled() == null
                ? source.isQualityWeightedConsensusEnabled()
                : request.getQualityWeightedConsensusEnabled());
        risk.setConsensusEntityBaseWeight(request.getConsensusEntityBaseWeight() == null ? source.getConsensusEntityBaseWeight() : request.getConsensusEntityBaseWeight());
        risk.setConsensusBehaviorWeight(request.getConsensusBehaviorWeight() == null ? source.getConsensusBehaviorWeight() : request.getConsensusBehaviorWeight());
        risk.setConsensusRecencyWeight(request.getConsensusRecencyWeight() == null ? source.getConsensusRecencyWeight() : request.getConsensusRecencyWeight());
        risk.setConsensusLeaderFollowerWeight(request.getConsensusLeaderFollowerWeight() == null ? source.getConsensusLeaderFollowerWeight() : request.getConsensusLeaderFollowerWeight());
        risk.setConsensusIndependenceWeight(request.getConsensusIndependenceWeight() == null ? source.getConsensusIndependenceWeight() : request.getConsensusIndependenceWeight());
        risk.setConsensusRecentConsistencyWeight(request.getConsensusRecentConsistencyWeight() == null ? source.getConsensusRecentConsistencyWeight() : request.getConsensusRecentConsistencyWeight());
        risk.setEnableSingleWalletOverride(request.getEnableSingleWalletOverride() == null ? source.isEnableSingleWalletOverride() : request.getEnableSingleWalletOverride());
        risk.setEnableSingleWalletOverrideCanary(request.getEnableSingleWalletOverrideCanary() == null
                ? source.isEnableSingleWalletOverrideCanary()
                : request.getEnableSingleWalletOverrideCanary());
        risk.setSingleWalletOverrideCanaryRate(request.getSingleWalletOverrideCanaryRate() == null
                ? source.getSingleWalletOverrideCanaryRate()
                : request.getSingleWalletOverrideCanaryRate());
        risk.setSingleWalletOverrideWhitelist(source.getSingleWalletOverrideWhitelist() == null
                ? List.of()
                : new ArrayList<>(source.getSingleWalletOverrideWhitelist()));
        risk.setSingleWalletOverrideMinWalletScore(request.getSingleWalletOverrideMinWalletScore() == null ? source.getSingleWalletOverrideMinWalletScore() : request.getSingleWalletOverrideMinWalletScore());
        risk.setSingleWalletOverrideMinWinRate(request.getSingleWalletOverrideMinWinRate() == null ? source.getSingleWalletOverrideMinWinRate() : request.getSingleWalletOverrideMinWinRate());
        risk.setSingleWalletOverrideMinTrades(request.getSingleWalletOverrideMinTrades() == null ? source.getSingleWalletOverrideMinTrades() : request.getSingleWalletOverrideMinTrades());
        risk.setSingleWalletOverrideMinRecentTrades7d(request.getSingleWalletOverrideMinRecentTrades7d() == null
                ? source.getSingleWalletOverrideMinRecentTrades7d()
                : request.getSingleWalletOverrideMinRecentTrades7d());
        risk.setSingleWalletOverrideMinRecentWinRate7d(request.getSingleWalletOverrideMinRecentWinRate7d() == null
                ? source.getSingleWalletOverrideMinRecentWinRate7d()
                : request.getSingleWalletOverrideMinRecentWinRate7d());
        risk.setSingleWalletOverrideMinReplicabilityScore(request.getSingleWalletOverrideMinReplicabilityScore() == null
                ? source.getSingleWalletOverrideMinReplicabilityScore()
                : request.getSingleWalletOverrideMinReplicabilityScore());
        risk.setSingleWalletOverrideMinQualityScore(request.getSingleWalletOverrideMinQualityScore() == null
                ? source.getSingleWalletOverrideMinQualityScore()
                : request.getSingleWalletOverrideMinQualityScore());
        risk.setSingleWalletOverrideMinTriggerSizeUsdc(request.getSingleWalletOverrideMinTriggerSizeUsdc() == null ? source.getSingleWalletOverrideMinTriggerSizeUsdc() : request.getSingleWalletOverrideMinTriggerSizeUsdc());
        risk.setSingleWalletOverrideMinRecommendedStakeUsdc(request.getSingleWalletOverrideMinRecommendedStakeUsdc() == null ? source.getSingleWalletOverrideMinRecommendedStakeUsdc() : request.getSingleWalletOverrideMinRecommendedStakeUsdc());
        risk.setEnableConcentrationGate(request.getEnableConcentrationGate() == null ? source.isEnableConcentrationGate() : request.getEnableConcentrationGate());
        risk.setConcentrationHighRiskMode(request.getConcentrationHighRiskMode() == null ? source.getConcentrationHighRiskMode() : request.getConcentrationHighRiskMode());
        risk.setConcentrationHighRiskDownsizeRate(request.getConcentrationHighRiskDownsizeRate() == null ? source.getConcentrationHighRiskDownsizeRate() : request.getConcentrationHighRiskDownsizeRate());
        risk.setWhaleSignalSourceMode(request.getWhaleSignalSourceMode() == null ? source.getWhaleSignalSourceMode() : request.getWhaleSignalSourceMode());
        risk.setKellyStakeEnabled(request.getKellyStakeEnabled() == null ? source.isKellyStakeEnabled() : request.getKellyStakeEnabled());
        risk.setKellyFraction(request.getKellyFraction() == null ? source.getKellyFraction() : request.getKellyFraction());
        risk.setExitSignalProviderMode(request.getExitSignalProviderMode() == null ? source.getExitSignalProviderMode() : request.getExitSignalProviderMode());
        return risk;
    }

    private List<SmartWalletFollowTaskView> truncate(List<SmartWalletFollowTaskView> items, int limit) {
        return items.size() <= limit ? items : new ArrayList<>(items.subList(0, limit));
    }

    @lombok.Builder
    @lombok.Value
    public static class IntelligenceReplaySummary {
        int scanned;
        int changed;
        int skipped;
    }
}
