package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.model.MarketStateSnapshot;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualBlocklistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletLeaderTradeOutcomeMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.dao.mapper.WalletFollowCandidateMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletLeaderTradeOutcomeEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.WalletFollowCandidateEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.model.EntityResolutionResult;
import com.metaverse.coin.strategy.job.smartwallet.intelligence.service.WalletEntityResolutionService;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowCoverageReasonView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowCoverageReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletBlockerStatView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationResult;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import com.metaverse.coin.strategy.common.service.trace.TraceContext;
import com.metaverse.coin.strategy.common.service.trace.TraceIds;
import com.metaverse.coin.strategy.job.strategy.application.StrategyFlowLogFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.metaverse.coin.strategy.job.smartwallet.model.SignalHealthSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCapacitySummaryView;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletFollowTaskService {

    private static final String LOG_PREFIX = "[POLY_FOLLOW]";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_FOLLOWED = "FOLLOWED";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_PAPER_SHADOW = "PAPER_SHADOW";

    /**
     * W1-T4: 被拒/降级信号的影子记账, 通过 setter 注入以避免破坏现有测试构造器.
     * 当 SignalShadowRecorder bean 不存在时为 null, 业务路径优雅降级 (no-op).
     */
    @Autowired(required = false)
    private SignalShadowRecorder signalShadowRecorder;
    private static final long RISK_DECISION_CACHE_TTL_MS = 60_000L;
    private static final long EXECUTABLE_WARN_THRESHOLD_MS = 3_000L;

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletLeaderTradeOutcomeMapper leaderOutcomeMapper;
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
    private final PolymarketMarketDataCacheService marketDataCacheService;
    private final SmartWalletManualAllowlistService manualAllowlistService;
    private final SmartWalletManualBlocklistService manualBlocklistService;
    private final FollowPositionAdjustmentService followPositionAdjustmentService;
    private final FollowIntentAggregationService followIntentAggregationService;
    private final Map<String, CachedRiskDecision> riskDecisionCache = new ConcurrentHashMap<>();

    public SmartWalletTradeEventEntity recordTradeEvent(SmartWalletTradeEventEntity event) {
        log.info("{} action=记录Polymarket钱包交易事件 result=开始 wallet={} marketId={} question={} outcome={} side={} externalTradeId={} price={} sizeShares={} sizeUsdc={} openedAtBjt={} openedAtMs={} polymarketSearchUrl={}",
                LOG_PREFIX, event.getWalletAddress(), event.getMarketId(), event.getQuestion(), event.getOutcome(),
                event.getSide(), event.getExternalTradeId(), event.getPrice(), event.getSizeShares(), event.getSizeUsdc(),
                openedAtBjt(event), openedAtMillis(event), polymarketSearchUrl(event));
        log.info("{}", flow("S1_EVENT_CAPTURED", "记录Polymarket钱包交易事件", "开始", event, null,
                "question", event.getQuestion(),
                "price", event.getPrice(),
                "sizeShares", event.getSizeShares(),
                "stakeUsdc", event.getSizeUsdc(),
                "openedAtBjt", openedAtBjt(event),
                "openedAtMs", openedAtMillis(event),
                "polymarketSearchUrl", polymarketSearchUrl(event),
                "reasonSummary", "开始记录观察钱包交易事件"));
        SmartWalletTradeEventEntity existing = tradeEventMapper.selectOne(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getSource, event.getSource())
                .eq(SmartWalletTradeEventEntity::getExternalTradeId, event.getExternalTradeId())
                .last("limit 1"));

        if (existing != null) {
            log.info("{} action=记录Polymarket钱包交易事件 result=跳过重复事件 eventId={} wallet={} marketId={} question={} outcome={} externalTradeId={} openedAtBjt={} openedAtMs={} polymarketSearchUrl={}",
                    LOG_PREFIX, existing.getId(), existing.getWalletAddress(), existing.getMarketId(), existing.getQuestion(),
                    existing.getOutcome(), existing.getExternalTradeId(), openedAtBjt(existing), openedAtMillis(existing),
                    polymarketSearchUrl(existing));
            log.info("{}", flow("S1_EVENT_CAPTURED", "记录Polymarket钱包交易事件", "跳过重复事件", existing, null,
                    "question", existing.getQuestion(),
                    "price", existing.getPrice(),
                    "sizeShares", existing.getSizeShares(),
                    "stakeUsdc", existing.getSizeUsdc(),
                    "openedAtBjt", openedAtBjt(existing),
                    "openedAtMs", openedAtMillis(existing),
                    "polymarketSearchUrl", polymarketSearchUrl(existing),
                    "reasonSummary", "externalTradeId already recorded"));
            profileStoreService.recordObservedTradeActivity(existing.getWalletAddress(), existing.getOpenedAt());
            return existing;
        }

        event.setProcessed(Boolean.FALSE);
        event.setCreatedAt(new Date());
        event.setUpdatedAt(new Date());
        tradeEventMapper.insert(event);
        // W1-T3: 入库拿到自增 id 后, 派生 trace_id 并回填. 同一 trade_event 全链路共享.
        String traceId = TraceIds.fromTradeEventId(event.getId());
        event.setTraceId(traceId);
        SmartWalletTradeEventEntity traceUpdate = new SmartWalletTradeEventEntity();
        traceUpdate.setId(event.getId());
        traceUpdate.setTraceId(traceId);
        traceUpdate.setUpdatedAt(new Date());
        tradeEventMapper.updateById(traceUpdate);
        try (TraceContext.Scope ignored = TraceContext.scope(traceId)) {
            log.info("{} action=记录Polymarket钱包交易事件 result=已入库 eventId={} traceId={} wallet={} marketId={} question={} outcome={} side={} openedAtBjt={} openedAtMs={} polymarketSearchUrl={}",
                    LOG_PREFIX, event.getId(), traceId, event.getWalletAddress(), event.getMarketId(), event.getQuestion(),
                    event.getOutcome(), event.getSide(), openedAtBjt(event), openedAtMillis(event), polymarketSearchUrl(event));
            log.info("{}", flow("S1_EVENT_CAPTURED", "记录Polymarket钱包交易事件", "已入库", event, null,
                    "traceId", traceId,
                    "question", event.getQuestion(),
                    "price", event.getPrice(),
                    "sizeShares", event.getSizeShares(),
                    "stakeUsdc", event.getSizeUsdc(),
                    "openedAtBjt", openedAtBjt(event),
                    "openedAtMs", openedAtMillis(event),
                    "polymarketSearchUrl", polymarketSearchUrl(event),
                    "reasonSummary", "交易事件已入库，准备进入候选增强和风控链路"));
            profileStoreService.recordObservedTradeActivity(event.getWalletAddress(), event.getOpenedAt());
            ensureMarketInfoForEvent(event);
            createFollowTaskIfQualified(event);
        }
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
        return listExecutableTasks(limit, null);
    }

    public List<SmartWalletFollowTaskView> listExecutableTasks(Integer limit, Integer scanLimit) {
        long startedAt = System.currentTimeMillis();
        int resultLimit = normalizeLimit(limit, 50);
        int effectiveScanLimit = normalizeExecutableScanLimit(scanLimit, resultLimit);
        List<SmartWalletFollowTaskEntity> tasks = loadPendingAndSkippedTasks(effectiveScanLimit);
        List<SmartWalletFollowTaskView> result = new ArrayList<>();
        ExecutableRiskStats stats = new ExecutableRiskStats();
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTaskCached(task, null, stats);
            if (!decision.isAccepted()) {
                continue;
            }
            result.add(toView(task, decision));
        }
        result.sort(this::compareTaskPriority);
        List<SmartWalletFollowTaskView> truncated = truncate(result, resultLimit);
        long elapsedMs = System.currentTimeMillis() - startedAt;
        if (elapsedMs > EXECUTABLE_WARN_THRESHOLD_MS) {
            log.warn("{} action=加载可执行跟单任务 result=慢查询 scanCount={} evaluatedCount={} cacheHitCount={} elapsedMs={} executableCount={} requestedLimit={} scanLimit={}",
                    LOG_PREFIX, tasks.size(), stats.evaluatedCount, stats.cacheHitCount, elapsedMs, truncated.size(), resultLimit, effectiveScanLimit);
        } else {
            log.info("{} action=加载可执行跟单任务 result=完成 scanCount={} evaluatedCount={} cacheHitCount={} elapsedMs={} executableCount={} requestedLimit={} scanLimit={}",
                    LOG_PREFIX, tasks.size(), stats.evaluatedCount, stats.cacheHitCount, elapsedMs, truncated.size(), resultLimit, effectiveScanLimit);
        }
        return truncated;
    }

    public List<SmartWalletFollowTaskView> listSingleWalletOverrideTasks(Integer limit) {
        return listSingleWalletOverrideTasks(limit, null, null, null);
    }

    public List<SmartWalletFollowTaskView> listSingleWalletOverrideTasks(Integer limit,
                                                                         SmartWalletProperties.SignalRisk overrideRisk,
                                                                         Integer taskScanLimit,
                                                                         String appliedPreset) {
        List<SmartWalletFollowTaskEntity> tasks = loadPendingAndSkippedTasks(
                normalizeLimit(taskScanLimit == null ? (limit == null ? 100 : Math.max(limit * 4, 50)) : taskScanLimit, 100));
        List<SmartWalletFollowTaskView> result = new ArrayList<>();
        ExecutableRiskStats stats = new ExecutableRiskStats();
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTaskCached(task, overrideRisk, stats);
            if (!decision.isAccepted() || !decision.isOverrideApplied()) {
                continue;
            }
            result.add(toView(task, decision, appliedPreset));
        }
        result.sort(this::compareTaskPriority);
        return truncate(result, normalizeLimit(limit, 20));
    }

    public List<SmartWalletFollowTaskView> listManualAllowlistTasks(Integer limit, Boolean includeBlocked) {
        List<SmartWalletFollowTaskEntity> tasks = loadPendingAndSkippedTasks(
                normalizeLimit(limit == null ? 100 : Math.max(limit * 4, 50), 200));
        List<SmartWalletFollowTaskView> result = new ArrayList<>();
        Map<String, Integer> liveAcceptedByWallet = new LinkedHashMap<>();
        ExecutableRiskStats stats = new ExecutableRiskStats();
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTaskCached(task, null, stats);
            if (!decision.isManualAllowlistApplied()) {
                continue;
            }
            if (!Boolean.TRUE.equals(includeBlocked) && !decision.isAccepted()) {
                continue;
            }
            if (decision.isAccepted() && "LIVE".equalsIgnoreCase(task.getExecutionMode())) {
                SmartWalletManualAllowlistEntity allowlist = manualAllowlistService.findActive(task.getWalletAddress()).orElse(null);
                int maxPerRun = manualAllowlistService.maxLiveOrdersPerRun(allowlist);
                String walletKey = normalizeWalletEntityId(task.getWalletAddress());
                int current = liveAcceptedByWallet.getOrDefault(walletKey, 0);
                if (current >= maxPerRun) {
                    continue;
                }
                liveAcceptedByWallet.put(walletKey, current + 1);
            }
            result.add(toView(task, decision));
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
        List<SmartWalletFollowTaskEntity> tasks = loadPendingAndSkippedTasks(
                normalizeLimit(taskScanLimit == null ? (limit == null ? 100 : Math.max(limit * 4, 50)) : taskScanLimit, 100));
        List<SmartWalletFollowTaskView> candidates = new ArrayList<>();
        int blockerThreshold = maxBlockers == null || maxBlockers < 0 ? 3 : maxBlockers;
        ExecutableRiskStats stats = new ExecutableRiskStats();
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTaskCached(task, overrideRisk, stats);
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
        List<SmartWalletFollowTaskEntity> tasks = loadPendingAndSkippedTasks(normalizeLimit(taskLimit, 200));
        Map<String, MutableBlockerStat> grouped = new LinkedHashMap<>();
        ExecutableRiskStats stats = new ExecutableRiskStats();
        for (SmartWalletFollowTaskEntity task : tasks) {
            SignalRiskDecision decision = evaluateTaskCached(task, null, stats);
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

    public List<SmartWalletFollowCoverageReportView> followCoverageReports(String walletAddress, Integer limit) {
        int rowLimit = limit == null || limit <= 0 ? 5000 : Math.min(limit, 50000);
        List<SmartWalletFollowTaskEntity> tasks = loadFollowTasksByWallet(walletAddress, rowLimit);
        marketInfoService.populateFollowTasks(tasks);
        Map<String, List<SmartWalletFollowTaskEntity>> byWallet = tasks.stream()
                .filter(task -> StringUtils.hasText(task.getWalletAddress()))
                .collect(Collectors.groupingBy(SmartWalletFollowTaskEntity::getWalletAddress, LinkedHashMap::new, Collectors.toList()));
        List<SmartWalletFollowCoverageReportView> reports = new ArrayList<>();
        byWallet.forEach((wallet, walletTasks) -> reports.add(buildCoverageReport(wallet, walletTasks)));
        reports.sort((left, right) -> Long.compare(right.getTotalEvents(), left.getTotalEvents()));
        return reports;
    }
    
    private List<SmartWalletFollowTaskEntity> loadFollowTasksByWallet(String walletAddress, int limit) {
        final int pageSize = 1000;
        List<SmartWalletFollowTaskEntity> all = new ArrayList<>();
        Long lastId = null;
        
        while (true) {
            LambdaQueryWrapper<SmartWalletFollowTaskEntity> wrapper = new LambdaQueryWrapper<>();
            
            if (StringUtils.hasText(walletAddress)) {
                wrapper.eq(SmartWalletFollowTaskEntity::getWalletAddress, walletAddress.trim());
            }
            
            if (lastId != null) {
                wrapper.lt(SmartWalletFollowTaskEntity::getId, lastId);
            }
            
            wrapper.orderByDesc(SmartWalletFollowTaskEntity::getId)
                   .last("limit " + pageSize);
            
            List<SmartWalletFollowTaskEntity> chunk = followTaskMapper.selectList(wrapper);
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            
            all.addAll(chunk);
            lastId = chunk.get(chunk.size() - 1).getId();
            
            if (chunk.size() < pageSize || all.size() >= limit) {
                break;
            }
        }
        
        return all.stream()
                  .limit(limit)
                  .collect(Collectors.toList());
    }

    private SmartWalletFollowCoverageReportView buildCoverageReport(String wallet, List<SmartWalletFollowTaskEntity> tasks) {
        Map<String, SmartWalletLeaderTradeOutcomeEntity> truthByKey = leaderOutcomeMapper.selectList(
                        new LambdaQueryWrapper<SmartWalletLeaderTradeOutcomeEntity>()
                                .eq(SmartWalletLeaderTradeOutcomeEntity::getWalletAddress, wallet))
                .stream()
                .filter(this::trustedClosedOutcome)
                .collect(Collectors.toMap(
                        row -> outcomeKey(row.getMarketId(), row.getOutcome()),
                        row -> row,
                        (left, right) -> left.getUpdatedAt() != null
                                && (right.getUpdatedAt() == null || left.getUpdatedAt().after(right.getUpdatedAt())) ? left : right,
                        LinkedHashMap::new));
        CoverageAccumulator followedTruth = new CoverageAccumulator();
        CoverageAccumulator shadowTruth = new CoverageAccumulator();
        Map<String, ReasonAccumulator> reasonBuckets = new LinkedHashMap<>();
        long followed = 0;
        long skipped = 0;
        long shadow = 0;
        long tradableBuy = 0;
        for (SmartWalletFollowTaskEntity task : tasks) {
            String status = task.getStatus() == null ? "" : task.getStatus().toUpperCase(Locale.ROOT);
            if (STATUS_FOLLOWED.equals(status)) {
                followed++;
            } else if (STATUS_SKIPPED.equals(status)) {
                skipped++;
            } else if (STATUS_PAPER_SHADOW.equals(status)) {
                shadow++;
            }
            boolean buy = "BUY".equalsIgnoreCase(task.getSide());
            String primary = StringUtils.hasText(task.getPrimarySkipReason()) ? task.getPrimarySkipReason() : inferPrimarySkipReason(task);
            if (buy && !"NON_TRADABLE".equals(primary)) {
                tradableBuy++;
            }
            if (STATUS_SKIPPED.equals(status) || STATUS_PAPER_SHADOW.equals(status)) {
                ReasonAccumulator bucket = reasonBuckets.computeIfAbsent(primary, key -> new ReasonAccumulator());
                bucket.count++;
                bucket.stake = bucket.stake.add(safe(task.getRecommendedStakeUsdc()));
            }
            SmartWalletLeaderTradeOutcomeEntity truth = truthByKey.get(outcomeKey(task.getMarketId(), task.getOutcome()));
            if (truth != null) {
                if (STATUS_FOLLOWED.equals(status)) {
                    followedTruth.add(truth);
                } else if (STATUS_PAPER_SHADOW.equals(status)) {
                    shadowTruth.add(truth);
                }
            }
        }
        long total = tasks.size();
        long denominator = tradableBuy > 0 ? tradableBuy : total;
        long rejectedTotal = Math.max(1, skipped + shadow);
        List<SmartWalletFollowCoverageReasonView> reasonViews = reasonBuckets.entrySet().stream()
                .map(entry -> SmartWalletFollowCoverageReasonView.builder()
                        .reason(entry.getKey())
                        .count(entry.getValue().count)
                        .ratio(ratio(entry.getValue().count, rejectedTotal))
                        .recommendedStakeUsdc(entry.getValue().stake.setScale(4, RoundingMode.HALF_UP))
                        .build())
                .sorted((left, right) -> Long.compare(right.getCount(), left.getCount()))
                .toList();
        return SmartWalletFollowCoverageReportView.builder()
                .walletAddress(wallet)
                .totalEvents(total)
                .followedEvents(followed)
                .skippedEvents(skipped)
                .shadowEvents(shadow)
                .tradableBuyEvents(tradableBuy)
                .followCoverageRate(ratio(followed, Math.max(1, total)))
                .shadowCoverageRate(ratio(followed + shadow, Math.max(1, denominator)))
                .followedPnlUsdc(followedTruth.pnl.setScale(4, RoundingMode.HALF_UP))
                .followedWinRate(followedTruth.winRate())
                .shadowTruthPnlUsdc(shadowTruth.pnl.setScale(4, RoundingMode.HALF_UP))
                .shadowTruthWinRate(shadowTruth.winRate())
                .recommendation(coverageRecommendation(followedTruth, shadowTruth))
                .skippedPrimaryReasons(reasonViews)
                .build();
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
            log.info("{}", flow("S7_TASK_CLAIMED", "抢占跟单任务执行权", "成功", null, task,
                    "mode", task.getExecutionMode(),
                    "stakeUsdc", task.getRecommendedStakeUsdc(),
                    "reasonSummary", "任务状态 PENDING -> EXECUTING"));
        } else {
            log.debug("{} action=抢占跟单任务执行权 result=失败 taskId={} expectedStatus=PENDING affectedRows={}",
                    LOG_PREFIX, task.getId(), affected);
            log.info("{}", flow("S7_TASK_CLAIMED", "抢占跟单任务执行权", "失败", null, task,
                    "mode", task.getExecutionMode(),
                    "stakeUsdc", task.getRecommendedStakeUsdc(),
                    "reasonSummary", "未能从 PENDING 抢占任务，可能已被其他线程处理"));
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
        return getWalletProfileByAddress(task.getWalletAddress());
    }

    private SmartWalletProfileEntity getWalletProfileByAddress(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return null;
        }
        return profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, walletAddress)
                .eq(SmartWalletProfileEntity::getSource, "POLYMARKET")
                .last("limit 1"));
    }

    private String resolveTaskExecutionMode(String walletAddress) {
        return resolveTaskExecutionMode(walletAddress, null);
    }

    private String resolveTaskExecutionMode(String walletAddress, SignalRiskDecision decision) {
        if (decision != null
                && decision.getMatchedStrategies() != null
                && decision.getMatchedStrategies().contains("SINGLE_WALLET_PAPER_CANARY")) {
            return "PAPER";
        }
        if (!properties.isAutoExecutionEnabled()) {
            return "PAPER";
        }
        if (!properties.isAllowlistLiveEnabled()) {
            return "PAPER";
        }
        return manualAllowlistService.findActive(walletAddress)
                .map(allowlist -> manualAllowlistService.allowsLive(allowlist)
                        && getWalletProfileByAddress(walletAddress) != null ? "LIVE" : "PAPER")
                .orElse("PAPER");
    }

    private void enforceManualAllowlistLiveLimits(SmartWalletFollowTaskEntity task, SignalRiskDecision decision) {
        if (task == null || decision == null || !decision.isManualAllowlistApplied()) {
            return;
        }
        SmartWalletManualAllowlistEntity allowlist = manualAllowlistService.findActive(task.getWalletAddress())
                .orElseThrow(() -> new IllegalStateException("[MANUAL_ALLOWLIST_LIVE_LIMIT_BLOCK] Manual allowlist is no longer active."));
        if (!manualAllowlistService.allowsLive(allowlist)) {
            throw new IllegalStateException("[MANUAL_ALLOWLIST_LIVE_LIMIT_BLOCK] Manual allowlist does not permit LIVE execution for this wallet.");
        }
        if (getWalletProfileByAddress(task.getWalletAddress()) == null) {
            throw new IllegalStateException("[MANUAL_ALLOWLIST_LIVE_LIMIT_BLOCK] Manual allowlist LIVE requires an existing wallet profile.");
        }
        if (manualAllowlistService.exceedsDailyLiveLimit(task.getWalletAddress(), allowlist)) {
            throw new IllegalStateException("[MANUAL_ALLOWLIST_LIVE_LIMIT_BLOCK] Manual allowlist daily LIVE order limit reached for this wallet.");
        }
    }

    public SignalRiskDecision evaluateTask(Long taskId) {
        return evaluateTask(taskId, null);
    }

    public SignalRiskDecision evaluateTask(Long taskId, SmartWalletProperties.SignalRisk overrideRisk) {
        SmartWalletFollowTaskEntity task = requireTask(taskId);
        return evaluateTask(task, overrideRisk);
    }

    private SignalRiskDecision evaluateTask(SmartWalletFollowTaskEntity task, SmartWalletProperties.SignalRisk overrideRisk) {
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

    private SignalRiskDecision evaluateTaskCached(SmartWalletFollowTaskEntity task,
                                                  SmartWalletProperties.SignalRisk overrideRisk,
                                                  ExecutableRiskStats stats) {
        String key = riskDecisionCacheKey(task, overrideRisk);
        CachedRiskDecision cached = riskDecisionCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs > now) {
            stats.cacheHitCount++;
            return cached.decision;
        }
        stats.evaluatedCount++;
        SignalRiskDecision decision = evaluateTask(task, overrideRisk);
        riskDecisionCache.put(key, new CachedRiskDecision(decision, now + RISK_DECISION_CACHE_TTL_MS));
        return decision;
    }

    private String riskDecisionCacheKey(SmartWalletFollowTaskEntity task, SmartWalletProperties.SignalRisk overrideRisk) {
        SmartWalletProperties.SignalRisk applied = overrideRisk == null ? properties.getSignalRisk() : overrideRisk;
        String policyTag = applied == null || !StringUtils.hasText(applied.getPolicyTag()) ? "default" : applied.getPolicyTag();
        long updatedAtMs = task.getUpdatedAt() == null ? 0L : task.getUpdatedAt().getTime();
        return task.getId() + "|" + updatedAtMs + "|" + policyTag;
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

        List<SmartWalletFollowTaskEntity> tasks = loadPendingAndSkippedTasks(scanLimit);

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
            enforceManualAllowlistLiveLimits(task, decision);
            if (decision.getRecommendedStakeUsdc() != null && decision.getRecommendedStakeUsdc().compareTo(BigDecimal.ZERO) > 0) {
                task.setRecommendedStakeUsdc(decision.getRecommendedStakeUsdc().setScale(2, RoundingMode.HALF_UP));
            }
            task.setExecutionMode("LIVE");
            task.setErrorMessage(null);
            task.setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
        }
        return executeTask(task);
    }

    private void createFollowTaskIfQualified(SmartWalletTradeEventEntity event) {
        log.info("{} action=根据交易事件生成跟单任务 result=开始 eventId={} wallet={} marketId={} outcome={} side={} {}",
                LOG_PREFIX, event.getId(), event.getWalletAddress(), event.getMarketId(), event.getOutcome(), event.getSide(),
                eventTrace(event));
        SmartWalletFollowTaskEntity existingTask = followTaskMapper.selectOne(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getSourceEventId, event.getId())
                .last("limit 1"));
        if (existingTask != null) {
            log.info("{} action=根据交易事件生成跟单任务 result=跳过重复任务 eventId={} followTaskId={} status={} wallet={} marketId={} outcome={} {} {}",
                    LOG_PREFIX, event.getId(), existingTask.getId(), existingTask.getStatus(), event.getWalletAddress(),
                    event.getMarketId(), event.getOutcome(), eventTrace(event), taskTrace(existingTask));
            log.info("{}", flow("S6_TASK_CREATED", "创建跟单任务", "跳过重复任务", event, existingTask,
                    "mode", existingTask.getExecutionMode(),
                    "stakeUsdc", existingTask.getRecommendedStakeUsdc(),
                    "reasonSummary", "sourceEventId 已存在跟单任务"));
            return;
        }

        // ── P0-2: 意图聚合 —— 同 wallet+market+outcome+side 在时间窗口内的碎片事件合并 ──
        if (isIntentAggregationEnabled()) {
            FollowIntentAggregationService.IntentAggregationResult aggResult =
                    followIntentAggregationService.aggregate(event);
            if (aggResult.isAggregated()) {
                log.info("{} action=意图聚合合并 result=MERGED eventId={} intentKey={} mergedTaskId={} aggregatedUsdc={} fragments={}",
                        LOG_PREFIX, event.getId(), aggResult.getIntentKey(), aggResult.getMergedIntoTaskId(),
                        aggResult.getAggregatedSizeUsdc(), aggResult.getFragmentCount());
                log.info("{}", flow("S6_TASK_CREATED", "创建跟单任务", "意图聚合合并", event, null,
                        "intentKey", aggResult.getIntentKey(),
                        "mergedTaskId", aggResult.getMergedIntoTaskId(),
                        "aggregatedUsdc", aggResult.getAggregatedSizeUsdc(),
                        "fragments", aggResult.getFragmentCount(),
                        "reasonSummary", "碎片事件已合并到已有意图任务"));
                markSourceEventProcessed(event);
                return;
            }
        }

        if (manualBlocklistService.isBlocked(event.getWalletAddress())) {
            createBlocklistedSkippedTask(event, manualBlocklistService.activeReason(event.getWalletAddress()));
            markSourceEventProcessed(event);
            return;
        }
        SmartWalletProfileEntity profile = profileStoreService.listWatchEnabled().stream()
                .filter(item -> item.getWalletAddress().equalsIgnoreCase(event.getWalletAddress()))
                .findFirst()
                .orElse(null);
        SmartWalletManualAllowlistEntity allowlist = manualAllowlistService.findActive(event.getWalletAddress()).orElse(null);
        if (profile == null && allowlist != null) {
            profile = getWalletProfileByAddress(event.getWalletAddress());
        }
        if (profile == null && allowlist == null) {
            log.info("{} action=根据交易事件生成跟单任务 result=跳过 reason=钱包未在可执行观察名单 eventId={} wallet={} marketId={} outcome={} {}",
                    LOG_PREFIX, event.getId(), event.getWalletAddress(), event.getMarketId(), event.getOutcome(),
                    eventTrace(event));
            log.info("{}", flow("S2_PROFILE_ADMISSION", "钱包准入检查", "拒绝", event, null,
                    "decisionDomain", "ADDRESS",
                    "addressDecision", "REJECTED",
                    "firstRejectGate", "G1_WATCHLIST_UNQUALIFIED",
                    "blockerCategories", "WATCHLIST",
                    "reasonSummary", "钱包未在可执行观察名单"));
            return;
        }
        log.info("{}", flow("S2_PROFILE_ADMISSION", "钱包准入检查", "通过", event, null,
                "decisionDomain", "ADDRESS",
                "addressDecision", "ACCEPTED",
                "entryLane", profile == null ? "MANUAL_ALLOWLIST" : profile.getMonitorTier(),
                "profileQualified", profile == null ? null : profile.getQualified(),
                "watchEnabled", profile == null ? null : profile.getWatchEnabled(),
                "monitorTier", profile == null ? null : profile.getMonitorTier(),
                "walletScore", profile == null ? null : profile.getWalletScore(),
                "winRate", profile == null ? null : profile.getWinRate(),
                "totalTrades", profile == null ? null : profile.getTotalTrades(),
                "allowlistStatus", allowlist == null ? null : allowlist.getStatus(),
                "reasonSummary", "钱包已进入候选任务生成链路"));

        // ---- Step 1: Intelligence Enrich ----
        // 将钱包地址解析为实体 ID + 聚类置信度，写入 wallet_follow_candidate 作为中间记录
        WalletFollowCandidateEntity candidate = enrichAndSaveCandidate(event);
        log.info("{} action=智能增强候选信号 result={} eventId={} candidateId={} wallet={} entityId={} confidence={} firstSignal={} consensusEntities={} reason={} {}",
                LOG_PREFIX, candidate.getEnrichmentStatus(), event.getId(), candidate.getId(), event.getWalletAddress(),
                candidate.getEntityId(), candidate.getClusterConfidence(), candidate.getIsEntityFirstSignal(),
                candidate.getEntityConsensusDedupCount(), candidate.getRejectionReason(), eventTrace(event));
        log.info("{}", flow("S3_CANDIDATE_ENRICHED", "智能增强候选信号", candidate.getEnrichmentStatus(), event, null,
                "candidateId", candidate.getId(),
                "entityId", candidate.getEntityId(),
                "consensusEntities", candidate.getEntityConsensusDedupCount(),
                "reasonSummary", candidate.getRejectionReason()));

        SmartWalletMarketInfoEntity marketInfo = ensureMarketInfoForEvent(event);
        MarketStateSnapshot marketState = refreshMarketState(event);
        if (!marketState.isActive()) {
            createLifecycleSkippedTask(event, candidate, marketInfo, marketState);
            markSourceEventProcessed(event);
            log.info("{} action=根据交易事件生成跟单任务 result=已创建生命周期拦截任务 eventId={} candidateId={} marketId={} outcome={} reason={} {}",
                    LOG_PREFIX, event.getId(), candidate.getId(), event.getMarketId(), event.getOutcome(),
                    marketState.blockingReasonMessage(), eventTrace(event));
            log.info("{}", flow("S4_MARKET_CHECK", "市场状态检查", "拒绝", event, null,
                    "candidateId", candidate.getId(),
                    "firstRejectGate", "G6_MARKET_NOT_ACTIVE",
                    "blockerCategories", "MARKET_STATE",
                    "reasonSummary", marketState.blockingReasonMessage()));
            return;
        }
        log.info("{}", flow("S4_MARKET_CHECK", "市场状态检查", "通过", event, null,
                "candidateId", candidate.getId(),
                "reasonSummary", "市场可交易，继续风控评估"));

        if ("PENDING".equalsIgnoreCase(candidate.getEnrichmentStatus())) {
            createSkippedTaskForPendingCandidate(event, candidate, marketInfo);
            markSourceEventProcessed(event);
            log.info("{} action=根据交易事件生成跟单任务 result=已创建智能增强等待任务 eventId={} candidateId={} marketId={} outcome={} reason={} {}",
                    LOG_PREFIX, event.getId(), candidate.getId(), event.getMarketId(), event.getOutcome(),
                    candidate.getRejectionReason(), eventTrace(event));
            log.info("{}", flow("S3_CANDIDATE_ENRICHED", "智能增强候选信号", "等待", event, null,
                    "candidateId", candidate.getId(),
                    "blockerCategories", "INTELLIGENCE",
                    "reasonSummary", candidate.getRejectionReason()));
            return;
        }

        // ---- Step 2: Risk Evaluation（沿用原有逻辑，现在共识内部已用实体去重）----
        SignalRiskDecision decision = signalRiskService.evaluate(profile, event);
        log.info("{} action=评估跟单风控 result={} eventId={} wallet={} marketId={} outcome={} recommendedStakeUsdc={} reasons={} {}",
                LOG_PREFIX, decision.isAccepted() ? "通过" : "拒绝", event.getId(), event.getWalletAddress(),
                event.getMarketId(), event.getOutcome(), decision.getRecommendedStakeUsdc(),
                String.join(" | ", decision.getReasons()), eventTrace(event));
        List<String> blockerCategories = classifyBlockerCategories(decision);
        log.info("{}", flow("S5_RISK_EVALUATED", "评估跟单风控", decision.isAccepted() ? "通过" : "拒绝", event, null,
                "stakeUsdc", decision.getRecommendedStakeUsdc(),
                "entryLane", decision.getEntryLane(),
                "matchedStrategies", String.join(",", decision.getMatchedStrategies()),
                "gateHighWaterMark", decision.getGateHighWaterMark(),
                "firstRejectGate", decision.getFirstRejectGate(),
                "blockerCategories", String.join(",", blockerCategories),
                "reasonSummary", summarizeReasons(decision.getReasons())));
        BigDecimal recommendedStake = decision.getRecommendedStakeUsdc() == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : decision.getRecommendedStakeUsdc().setScale(2, RoundingMode.HALF_UP);
        SkipReasonSnapshot skipReason = buildSkipReasonSnapshot(event, decision);
        String taskStatus = decision.isAccepted() ? STATUS_PENDING : resolveRejectedTaskStatus(skipReason);
        String executionMode = decision.isAccepted()
                ? resolveTaskExecutionMode(event.getWalletAddress(), decision)
                : STATUS_PAPER_SHADOW.equals(taskStatus) ? "PAPER_SHADOW" : resolveTaskExecutionMode(event.getWalletAddress(), decision);
        // ── P0-2: 如果意图聚合已启用，填充 intentKey 和可能的聚合金额 ──
        String intentKey = null;
        int fragmentCount = 1;
        BigDecimal aggregatedStakeUsdc = recommendedStake;
        if (isIntentAggregationEnabled()) {
            FollowIntentAggregationService.IntentAggregationResult aggResult =
                    followIntentAggregationService.aggregate(event);
            intentKey = aggResult.getIntentKey();
            fragmentCount = aggResult.getFragmentCount();
            if (aggResult.getAggregatedSizeUsdc() != null
                    && aggResult.getAggregatedSizeUsdc().compareTo(recommendedStake) > 0) {
                // 聚合金额比风控推荐更大时，使用聚合金额（受后续 exposure clip 约束）
                aggregatedStakeUsdc = aggResult.getAggregatedSizeUsdc().setScale(2, RoundingMode.HALF_UP);
            }
        }

        // ---- Step 3: Create Follow Task（附带实体维度字段）----
        SmartWalletFollowTaskEntity task = new SmartWalletFollowTaskEntity()
                .setWalletAddress(event.getWalletAddress())
                .setEntityId(candidate.getEntityId())
                .setFollowCandidateId(candidate.getId())
                .setSourceEventId(event.getId())
                .setTraceId(event.getTraceId())
                .setMarketInfoId(marketInfo.getId())
                .setMarketInfo(marketInfo)
                .setSide(event.getSide())
                .setTriggerPrice(event.getPrice())
                .setTriggerSizeUsdc(event.getSizeUsdc())
                .setRecommendedStakeUsdc(decision.isAccepted() ? aggregatedStakeUsdc : recommendedStake)
                .setIntentKey(intentKey)
                .setFragmentCount(fragmentCount)
                .setStatus(taskStatus)
                .setExecutionMode(executionMode)
                .setErrorMessage(String.join(" | ", decision.getReasons()))
                .setPrimarySkipReason(decision.isAccepted() ? null : skipReason.primaryReason())
                .setSkipReasonTags(decision.isAccepted() ? null : String.join(",", skipReason.tags()))
                .setWouldHaveSkippedReason(STATUS_PAPER_SHADOW.equals(taskStatus) ? String.join(" | ", decision.getReasons()) : null)
                .setScheduledAt(new Date(System.currentTimeMillis() + resolveFollowDelayMs(fragmentCount)))
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());
        followTaskMapper.insert(task);
        log.info("{} action=创建跟单任务 result={} followTaskId={} eventId={} candidateId={} wallet={} entityId={} marketId={} outcome={} mode={} stakeUsdc={} scheduledAt={} reason={} {} {}",
                LOG_PREFIX, task.getStatus(), task.getId(), event.getId(), candidate.getId(), task.getWalletAddress(),
                task.getEntityId(), task.getMarketId(), task.getOutcome(), task.getExecutionMode(),
                task.getRecommendedStakeUsdc(), task.getScheduledAt(), task.getErrorMessage(), eventTrace(event), taskTrace(task));
        log.info("{}", flow("S6_TASK_CREATED", "创建跟单任务", task.getStatus(), event, task,
                "candidateId", candidate.getId(),
                "mode", task.getExecutionMode(),
                "stakeUsdc", task.getRecommendedStakeUsdc(),
                "entryLane", decision.getEntryLane(),
                "matchedStrategies", String.join(",", decision.getMatchedStrategies()),
                "gateHighWaterMark", decision.getGateHighWaterMark(),
                "firstRejectGate", decision.getFirstRejectGate(),
                "blockerCategories", String.join(",", blockerCategories),
                "reasonSummary", task.getErrorMessage()));

        // W1-T4: 拒绝或软门控 (PAPER_SHADOW) 时立即录影子记账, 等结算 job 回填出场 PnL.
        // 仅当存在 SignalRejectionShadowRecorder bean 且决策被拒/降级时执行 (signalShadowRecorder 通过 @Autowired(required=false) 注入).
        recordSignalShadowIfRejected(event, decision, task, marketInfo);

        // 回填 candidate 的 follow_task_id
        candidate.setFollowTaskId(task.getId())
                .setUpdatedAt(new Date());
        followCandidateMapper.updateById(candidate);

        markSourceEventProcessed(event);
        log.info("{} action=标记源交易事件已处理 result=完成 eventId={} followTaskId={} processed=true",
                LOG_PREFIX, event.getId(), task.getId());
    }

    private void createBlocklistedSkippedTask(SmartWalletTradeEventEntity event, String reason) {
        String effectiveReason = StringUtils.hasText(reason)
                ? reason
                : SmartWalletManualBlocklistService.REASON_MANUAL_BLOCKLIST;
        SmartWalletFollowTaskEntity task = new SmartWalletFollowTaskEntity()
                .setWalletAddress(event.getWalletAddress())
                .setSourceEventId(event.getId())
                .setTraceId(event.getTraceId())
                .setSide(event.getSide())
                .setTriggerPrice(event.getPrice())
                .setTriggerSizeUsdc(event.getSizeUsdc())
                .setRecommendedStakeUsdc(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .setStatus(STATUS_SKIPPED)
                .setExecutionMode("BLOCKLIST")
                .setErrorMessage("[" + effectiveReason + "] Wallet is blocked by smart-wallet blocklist.")
                .setPrimarySkipReason(effectiveReason)
                .setSkipReasonTags(effectiveReason)
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());
        followTaskMapper.insert(task);
        log.info("{} action=创建阻断名单拦截任务 result=SKIPPED followTaskId={} eventId={} wallet={} marketId={} outcome={} reason={} {} {}",
                LOG_PREFIX, task.getId(), event.getId(), event.getWalletAddress(), event.getMarketId(), event.getOutcome(),
                effectiveReason, eventTrace(event), taskTrace(task));
        log.info("{}", flow("S2_PROFILE_ADMISSION", "钱包准入检查", "拒绝", event, task,
                "decisionDomain", "ADDRESS",
                "addressDecision", "REJECTED",
                "firstRejectGate", "G1_MANUAL_BLOCKLIST",
                "blockerCategories", effectiveReason,
                "reasonSummary", effectiveReason));
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

        if (manualBlocklistService.isBlocked(event.getWalletAddress())) {
            if (task != null) {
                String reason = manualBlocklistService.activeReason(event.getWalletAddress());
                task.setStatus(STATUS_SKIPPED);
                task.setExecutionMode("BLOCKLIST");
                task.setErrorMessage("[" + reason + "] Wallet is blocked by smart-wallet blocklist.");
                task.setPrimarySkipReason(reason);
                task.setSkipReasonTags(reason);
                task.setUpdatedAt(new Date());
                followTaskMapper.updateById(task);
            }
            return true;
        }

        SmartWalletProfileEntity profile = profileStoreService.listWatchEnabled().stream()
                .filter(item -> item.getWalletAddress().equalsIgnoreCase(event.getWalletAddress()))
                .findFirst()
                .orElse(null);
        SmartWalletManualAllowlistEntity allowlist = manualAllowlistService.findActive(event.getWalletAddress()).orElse(null);
        if (profile == null && allowlist != null) {
            profile = getWalletProfileByAddress(event.getWalletAddress());
        }
        if ((profile == null && allowlist == null) || task == null) {
            log.info("{} action=重评延迟智能候选 result=无法恢复任务 eventId={} candidateId={} followTaskId={} reason={}",
                    LOG_PREFIX, event.getId(), candidate.getId(), task == null ? null : task.getId(),
                    profile == null ? "钱包不在可执行观察名单或白名单" : "任务不存在");
            return true;
        }

        ensureMarketInfoForEvent(event);
        MarketStateSnapshot marketState = refreshMarketState(event);
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
        SkipReasonSnapshot skipReason = buildSkipReasonSnapshot(event, decision);
        String taskStatus = decision.isAccepted() ? STATUS_PENDING : resolveRejectedTaskStatus(skipReason);
        String executionMode = decision.isAccepted()
                ? resolveTaskExecutionMode(event.getWalletAddress(), decision)
                : STATUS_PAPER_SHADOW.equals(taskStatus) ? "PAPER_SHADOW" : resolveTaskExecutionMode(event.getWalletAddress(), decision);
        task.setEntityId(candidate.getEntityId())
                .setFollowCandidateId(candidate.getId())
                .setRecommendedStakeUsdc(recommendedStake)
                .setStatus(taskStatus)
                .setExecutionMode(executionMode)
                .setErrorMessage(String.join(" | ", decision.getReasons()))
                .setPrimarySkipReason(decision.isAccepted() ? null : skipReason.primaryReason())
                .setSkipReasonTags(decision.isAccepted() ? null : String.join(",", skipReason.tags()))
                .setWouldHaveSkippedReason(STATUS_PAPER_SHADOW.equals(taskStatus) ? String.join(" | ", decision.getReasons()) : null)
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
                .setTraceId(event.getTraceId())
                .setMarketInfoId(marketInfo.getId())
                .setMarketInfo(marketInfo)
                .setSide(event.getSide())
                .setTriggerPrice(event.getPrice())
                .setTriggerSizeUsdc(event.getSizeUsdc())
                .setRecommendedStakeUsdc(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .setStatus("SKIPPED")
                .setExecutionMode(properties.isAutoExecutionEnabled() ? "LIVE" : "PAPER")
                .setErrorMessage(candidate.getRejectionReason())
                .setPrimarySkipReason("OTHER")
                .setSkipReasonTags("OTHER")
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
                                            MarketStateSnapshot marketState) {
        SmartWalletFollowTaskEntity task = new SmartWalletFollowTaskEntity()
                .setWalletAddress(event.getWalletAddress())
                .setEntityId(candidate == null ? null : candidate.getEntityId())
                .setFollowCandidateId(candidate == null ? null : candidate.getId())
                .setSourceEventId(event.getId())
                .setTraceId(event.getTraceId())
                .setMarketInfoId(marketInfo == null ? null : marketInfo.getId())
                .setMarketInfo(marketInfo)
                .setSide(event.getSide())
                .setTriggerPrice(event.getPrice())
                .setTriggerSizeUsdc(event.getSizeUsdc())
                .setRecommendedStakeUsdc(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .setStatus("SKIPPED")
                .setExecutionMode(properties.isAutoExecutionEnabled() ? "LIVE" : "PAPER")
                .setErrorMessage(marketState.blockingReasonMessage())
                .setPrimarySkipReason("NON_TRADABLE")
                .setSkipReasonTags("NON_TRADABLE")
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

    private boolean trustedClosedOutcome(SmartWalletLeaderTradeOutcomeEntity row) {
        if (row == null || row.getSourceConfidence() == null
                || row.getSourceConfidence().compareTo(new BigDecimal("0.5500")) < 0) {
            return false;
        }
        String status = row.getOutcomeStatus() == null ? "" : row.getOutcomeStatus().toUpperCase(Locale.ROOT);
        return List.of("CLOSED_WIN", "CLOSED_LOSS", "CLOSED_BREAKEVEN").contains(status);
    }

    private String outcomeKey(String marketId, String outcome) {
        return (marketId == null ? "" : marketId.trim().toLowerCase(Locale.ROOT))
                + "|"
                + (outcome == null ? "" : outcome.trim().toLowerCase(Locale.ROOT));
    }

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private PriceReference resolveFreshEntryPrice(SmartWalletFollowTaskEntity task) {
        BigDecimal fallback = safe(task == null ? null : task.getTriggerPrice());
        if (task == null || !StringUtils.hasText(task.getMarketId()) || !StringUtils.hasText(task.getOutcome())) {
            return new PriceReference(fallback, "TASK_FALLBACK");
        }
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                    task.getMarketId(),
                    true,
                    task.getQuestion(),
                    List.of(task.getOutcome())
            );
            String tokenId = firstNonBlank(
                    task.getMarketInfo() == null ? null : task.getMarketInfo().getTokenId(),
                    resolveTokenId(metadata, task.getOutcome())
            );
            if (!StringUtils.hasText(tokenId)) {
                return new PriceReference(fallback, fallback.compareTo(BigDecimal.ZERO) > 0 ? "TASK_FALLBACK_TOKEN_MISSING" : "UNAVAILABLE");
            }
            marketDataCacheService.invalidateOrderBook(tokenId);
            PolymarketApiClient.OrderBook orderBook = marketDataCacheService.getOrderBook(tokenId);
            BigDecimal bestAsk = topPrice(orderBook == null ? null : orderBook.getAsks(), false);
            BigDecimal midpoint = safe(orderBook == null ? null : orderBook.getMidpoint());
            BigDecimal price = firstPositive(bestAsk, midpoint, fallback);
            String source = bestAsk.compareTo(BigDecimal.ZERO) > 0
                    ? "REST_BEST_ASK"
                    : midpoint.compareTo(BigDecimal.ZERO) > 0 ? "REST_MIDPOINT" : "TASK_FALLBACK";
            return new PriceReference(price, source);
        } catch (Exception ex) {
            log.warn("{} action=执行前刷新入场价格 result=失败 followTaskId={} marketId={} outcome={} fallbackPrice={} error={}",
                    LOG_PREFIX, task.getId(), task.getMarketId(), task.getOutcome(), fallback, ex.getMessage());
            return new PriceReference(fallback, fallback.compareTo(BigDecimal.ZERO) > 0 ? "TASK_FALLBACK_BOOK_ERROR" : "BOOK_ERROR");
        }
    }

    private String resolveTokenId(PolymarketApiClient.MarketMetadata metadata, String outcome) {
        if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
            return null;
        }
        if (!StringUtils.hasText(outcome)) {
            return null;
        }
        String exact = metadata.getOutcomeTokenIds().get(outcome);
        if (StringUtils.hasText(exact)) {
            return exact.trim();
        }
        String normalized = outcome.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : metadata.getOutcomeTokenIds().entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().trim().toLowerCase(Locale.ROOT).equals(normalized)
                    && StringUtils.hasText(entry.getValue())) {
                return entry.getValue().trim();
            }
        }
        return null;
    }

    private BigDecimal topPrice(List<PolymarketApiClient.OrderBookLevel> levels, boolean highest) {
        if (levels == null || levels.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal selected = BigDecimal.ZERO;
        for (PolymarketApiClient.OrderBookLevel level : levels) {
            BigDecimal price = safe(level == null ? null : level.getPrice());
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (selected.compareTo(BigDecimal.ZERO) <= 0
                    || (highest && price.compareTo(selected) > 0)
                    || (!highest && price.compareTo(selected) < 0)) {
                selected = price;
            }
        }
        return selected;
    }

    private BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            BigDecimal safeValue = safe(value);
            if (safeValue.compareTo(BigDecimal.ZERO) > 0) {
                return safeValue;
            }
        }
        return BigDecimal.ZERO;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String inferPrimarySkipReason(SmartWalletFollowTaskEntity task) {
        if (task == null) {
            return "OTHER";
        }
        Set<String> tags = new LinkedHashSet<>();
        if (!"BUY".equalsIgnoreCase(task.getSide())) {
            tags.add("NON_TRADABLE");
        }
        String source = StringUtils.hasText(task.getSkipReasonTags())
                ? task.getSkipReasonTags()
                : task.getErrorMessage();
        if (StringUtils.hasText(source)) {
            for (String segment : source.split("\\s*[,|]\\s*")) {
                String tag = classifyShadowTag(segment);
                if (StringUtils.hasText(tag)) {
                    tags.add(tag);
                }
            }
        }
        if (tags.isEmpty()) {
            tags.add("OTHER");
        }
        return primarySkipReason(tags);
    }

    private String coverageRecommendation(CoverageAccumulator followed, CoverageAccumulator shadow) {
        if (followed.pnl.compareTo(new BigDecimal("-50.00")) < 0 && shadow.count < 10) {
            return "OBSERVE_ONLY";
        }
        if (shadow.count < 10) {
            return "EXPAND_PAPER_SHADOW_100_PERCENT";
        }
        BigDecimal followedWinRate = followed.winRate();
        BigDecimal shadowWinRate = shadow.winRate();
        if (shadow.pnl.compareTo(BigDecimal.ZERO) > 0
                && shadowWinRate.compareTo(followedWinRate.add(new BigDecimal("0.05"))) > 0) {
            return "EXPAND_SMALL_CANARY";
        }
        if (shadow.pnl.compareTo(BigDecimal.ZERO) < 0
                && shadowWinRate.compareTo(followedWinRate) <= 0) {
            return "KEEP_FILTER_FIRST";
        }
        return "EXPAND_PAPER_SHADOW_100_PERCENT";
    }

    private SmartWalletExecutionResult executeTask(SmartWalletFollowTaskEntity task) {
        try {
            marketInfoService.populate(task);
            log.info("{} action=执行跟单任务 result=开始 followTaskId={} wallet={} marketId={} outcome={} mode={} status={} triggerPrice={} stakeUsdc={} {}",
                    LOG_PREFIX, task == null ? null : task.getId(), task == null ? null : task.getWalletAddress(),
                    task == null ? null : task.getMarketId(), task == null ? null : task.getOutcome(),
                    task == null ? null : task.getExecutionMode(), task == null ? null : task.getStatus(),
                    task == null ? null : task.getTriggerPrice(), task == null ? null : task.getRecommendedStakeUsdc(),
                    taskTrace(task));
            if (task == null || !StringUtils.hasText(task.getMarketId()) || !StringUtils.hasText(task.getOutcome())) {
                throw new IllegalStateException("Follow task market info is missing before execution. taskId="
                        + (task == null ? null : task.getId())
                        + ", marketInfoId=" + (task == null ? null : task.getMarketInfoId()));
            }
            SignalRiskDecision decision = evaluateTask(task.getId());
            if (!decision.isAccepted()) {
                SmartWalletTradeEventEntity event = getSourceEvent(task);
                SkipReasonSnapshot skipReason = buildSkipReasonSnapshot(event, decision);
                String rejectedStatus = resolveRejectedTaskStatus(skipReason);
                task.setStatus(rejectedStatus);
                task.setExecutionMode(STATUS_PAPER_SHADOW.equals(rejectedStatus) ? "PAPER_SHADOW" : task.getExecutionMode());
                task.setErrorMessage(String.join(" | ", decision.getReasons()));
                task.setPrimarySkipReason(skipReason.primaryReason());
                task.setSkipReasonTags(String.join(",", skipReason.tags()));
                task.setWouldHaveSkippedReason(STATUS_PAPER_SHADOW.equals(rejectedStatus) ? task.getErrorMessage() : null);
                task.setUpdatedAt(new Date());
                followTaskMapper.updateById(task);
                log.info("{} action=执行前复核风控 result=拒绝并跳过 followTaskId={} wallet={} marketId={} outcome={} stakeUsdc={} reasons={} {}",
                        LOG_PREFIX, task.getId(), task.getWalletAddress(), task.getMarketId(), task.getOutcome(),
                        task.getRecommendedStakeUsdc(), task.getErrorMessage(), taskTrace(task));
                return SmartWalletExecutionResult.builder()
                        .signalId(String.valueOf(task.getId()))
                        .mode("LIVE".equalsIgnoreCase(task.getExecutionMode()) ? ExecutionMode.LIVE : ExecutionMode.PAPER)
                        .success(false)
                        .message(task.getErrorMessage())
                        .marketId(task.getMarketId())
                        .outcome(task.getOutcome())
                        .side(task.getSide())
                        .status(rejectedStatus)
                        .price(task.getTriggerPrice())
                        .notionalUsdc(task.getRecommendedStakeUsdc())
                        .feeUsdc(BigDecimal.ZERO)
                        .tradingFeeUsdc(BigDecimal.ZERO)
                        .build();
            }
            if ("DYNAMIC_FOLLOW_EXIT".equalsIgnoreCase(decision.getEntryLane())) {
                // ── P0-1: 不再 SKIP，转调 FollowPositionAdjustmentService 执行减仓/平仓 ──
                ExecutionMode exitMode = "LIVE".equalsIgnoreCase(task.getExecutionMode()) ? ExecutionMode.LIVE : ExecutionMode.PAPER;
                SmartWalletTradeEventEntity exitEvent = getSourceEvent(task);
                String exitIntent = decision.getExitIntent(); // SOURCE_SELL / OPPOSITE_BUY
                log.info("{} action=DYNAMIC_FOLLOW_EXIT转入仓位调整 followTaskId={} exitIntent={} mode={} marketId={} outcome={}",
                        LOG_PREFIX, task.getId(), exitIntent, exitMode, task.getMarketId(), task.getOutcome());

                FollowPositionAdjustmentService.AdjustmentResult adjustResult =
                        followPositionAdjustmentService.adjust(task, exitEvent, exitIntent, exitMode);

                if (adjustResult.getAction() == FollowPositionAdjustmentService.AdjustmentAction.NO_POSITION_SKIP) {
                    // follower 无持仓，安全跳过
                    task.setStatus("SKIPPED");
                    task.setErrorMessage("[DYNAMIC_FOLLOW_EXIT:NO_POSITION] " + adjustResult.getMessage());
                    task.setPrimarySkipReason("NO_POSITION");
                    task.setSkipReasonTags("NO_POSITION,DYNAMIC_FOLLOW_EXIT");
                    task.setUpdatedAt(new Date());
                    followTaskMapper.updateById(task);
                    return SmartWalletExecutionResult.builder()
                            .signalId(String.valueOf(task.getId()))
                            .mode(exitMode)
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

                // 有仓位且已执行调整
                String exitStatus = adjustResult.isSuccess() ? "FOLLOWED" : "FAILED";
                task.setStatus(exitStatus);
                task.setErrorMessage("[DYNAMIC_FOLLOW_EXIT:" + adjustResult.getAction() + "] " + adjustResult.getMessage());
                task.setUpdatedAt(new Date());
                followTaskMapper.updateById(task);

                return SmartWalletExecutionResult.builder()
                        .signalId(String.valueOf(task.getId()))
                        .mode(exitMode)
                        .success(adjustResult.isSuccess())
                        .message(task.getErrorMessage())
                        .marketId(task.getMarketId())
                        .outcome(task.getOutcome())
                        .side(task.getSide())
                        .orderType("EXIT")
                        .status(exitStatus)
                        .price(task.getTriggerPrice())
                        .notionalUsdc(adjustResult.getExitedNotionalUsdc())
                        .feeUsdc(BigDecimal.ZERO)
                        .tradingFeeUsdc(BigDecimal.ZERO)
                        .build();
            }
            // Scale-up: if this is an add-on signal, use scaleUpAddOnStake instead of recommendedStake
            BigDecimal effectiveStake = decision.getRecommendedStakeUsdc();
            if (decision.isScaleUpSignal() && decision.getScaleUpAddOnStake() != null
                    && decision.getScaleUpAddOnStake().compareTo(BigDecimal.ZERO) > 0) {
                effectiveStake = decision.getScaleUpAddOnStake();
                log.info("{} action=追加仓位 result=使用追加金额 followTaskId={} addOnStake={} originalRecommended={} {}",
                        LOG_PREFIX, task.getId(), effectiveStake, decision.getRecommendedStakeUsdc(), taskTrace(task));
            }
            if (effectiveStake != null && effectiveStake.compareTo(BigDecimal.ZERO) > 0) {
                task.setRecommendedStakeUsdc(effectiveStake.setScale(2, RoundingMode.HALF_UP));
            }
            PriceReference entryPrice = resolveFreshEntryPrice(task);
            if (entryPrice.price().compareTo(BigDecimal.ZERO) <= 0) {
                task.setStatus("SKIPPED");
                task.setErrorMessage("[CURRENT_PRICE_UNAVAILABLE] Unable to resolve current executable entry price before follow.");
                task.setPrimarySkipReason("NON_TRADABLE");
                task.setSkipReasonTags("NON_TRADABLE,CURRENT_PRICE_UNAVAILABLE");
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
                        .tradingFeeUsdc(BigDecimal.ZERO)
                        .build();
            }

            // ── F-P0-5: 执行前价格漂移二次检查 ─────────────────────────────────
            // 从创建到执行之间（follow-delay + queueing）盘口可能已大幅移动，
            // 若 |currentPrice - triggerPrice| / triggerPrice 超过 preExecutePriceDriftBps，
            // 直接 SKIP（primarySkipReason=PRE_EXECUTE_PRICE_DRIFT_EXCEEDED），
            // 避免按旧 price 下单造成成交价偏差。
            SmartWalletProperties.SignalRisk preDriftRiskCfg = properties == null ? null : properties.getSignalRisk();
            boolean enableDriftCheck = preDriftRiskCfg == null || preDriftRiskCfg.isEnablePreExecutePriceDriftCheck();
            if (enableDriftCheck
                    && task.getTriggerPrice() != null
                    && task.getTriggerPrice().compareTo(BigDecimal.ZERO) > 0
                    && entryPrice.price().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal trigger = task.getTriggerPrice();
                BigDecimal current = entryPrice.price();
                BigDecimal driftAbs = current.subtract(trigger).abs();
                BigDecimal driftBps = driftAbs.multiply(new BigDecimal("10000"))
                        .divide(trigger, 2, RoundingMode.HALF_UP);
                int threshold = preDriftRiskCfg == null ? 80 : preDriftRiskCfg.getPreExecutePriceDriftBps();
                if (threshold > 0 && driftBps.compareTo(new BigDecimal(threshold)) > 0) {
                    String reason = "[PRE_EXECUTE_PRICE_DRIFT_EXCEEDED] triggerPrice=" + trigger
                            + " currentPrice=" + current + " driftBps=" + driftBps
                            + " threshold=" + threshold + " priceSource=" + entryPrice.source();
                    task.setStatus("SKIPPED");
                    task.setErrorMessage(reason);
                    task.setPrimarySkipReason("PRE_EXECUTE_PRICE_DRIFT_EXCEEDED");
                    task.setSkipReasonTags("PRE_EXECUTE_PRICE_DRIFT_EXCEEDED,driftBps=" + driftBps
                            + ",threshold=" + threshold);
                    task.setUpdatedAt(new Date());
                    followTaskMapper.updateById(task);
                    log.warn("{} action=执行前价格漂移检查 result=SKIP followTaskId={} marketId={} outcome={} {}",
                            LOG_PREFIX, task.getId(), task.getMarketId(), task.getOutcome(), reason);
                    return SmartWalletExecutionResult.builder()
                            .signalId(String.valueOf(task.getId()))
                            .mode("LIVE".equalsIgnoreCase(task.getExecutionMode()) ? ExecutionMode.LIVE : ExecutionMode.PAPER)
                            .success(false)
                            .message(reason)
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
                log.info("{} action=执行前价格漂移检查 result=PASS followTaskId={} triggerPrice={} currentPrice={} driftBps={} threshold={}",
                        LOG_PREFIX, task.getId(), trigger, current, driftBps, threshold);
            }
            SmartWalletSignal signal = SmartWalletSignal.builder()
                    .signalId(String.valueOf(task.getId()))
                    .market(PredictionMarketOpportunity.builder()
                            .marketId(task.getMarketId())
                            .question(task.getQuestion())
                            .outcome(task.getOutcome())
                            .price(entryPrice.price())
                            .build())
                    .followOutcome(task.getOutcome())
                    .recommendedStakeUsdc(task.getRecommendedStakeUsdc())
                    // W2-T4.3: 透传价格冲击门的下游路由提示, PolymarketOrderRequestFactory 据此切换 orderStrategy
                    .priceImpactExecutionMode(decision == null ? null : decision.getPriceImpactExecutionMode())
                    .build();
            ExecutionMode mode = "LIVE".equalsIgnoreCase(task.getExecutionMode()) ? ExecutionMode.LIVE : ExecutionMode.PAPER;
            log.info("{} action=提交跟单执行网关 result=开始 followTaskId={} mode={} marketId={} outcome={} currentPrice={} priceSource={} triggerPrice={} stakeUsdc={} {}",
                    LOG_PREFIX, task.getId(), mode, task.getMarketId(), task.getOutcome(), entryPrice.price(), entryPrice.source(),
                    task.getTriggerPrice(), task.getRecommendedStakeUsdc(), taskTrace(task));
            SmartWalletExecutionResult result = executionGateway.execute(signal, mode);
            log.info("{} action=提交跟单执行网关 result={} followTaskId={} mode={} marketId={} outcome={} externalOrderId={} orderStatus={} message={} {}",
                    LOG_PREFIX, result.isSuccess() ? "成功" : "失败", task.getId(), mode, task.getMarketId(),
                    task.getOutcome(), result.getExternalOrderId(), result.getStatus(), result.getMessage(), taskTrace(task));
            log.info("{}", flow("S8_ORDER_EXECUTED", "提交跟单执行网关", result.isSuccess() ? "成功" : "失败", null, task,
                    "mode", mode,
                    "stakeUsdc", task.getRecommendedStakeUsdc(),
                    "orderStatus", result.getStatus(),
                    "externalOrderId", result.getExternalOrderId(),
                    "reasonSummary", result.getMessage()));
            task.setStatus(result.isSuccess() ? "FOLLOWED" : "FAILED");
            task.setExternalOrderId(result.getExternalOrderId());
            task.setErrorMessage(result.isSuccess() ? null : result.getMessage());
            task.setExecutedAt(new Date());
            task.setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
            accountingService.recordExecution(task, result);
            log.info("{} action=记录跟单执行结果到账本 result=完成 followTaskId={} finalStatus={} mode={} externalOrderId={} notionalUsdc={} feeUsdc={} {}",
                    LOG_PREFIX, task.getId(), task.getStatus(), mode, result.getExternalOrderId(),
                    result.getNotionalUsdc(), result.getFeeUsdc(), taskTrace(task));
            if (result.isSuccess() && mode == ExecutionMode.PAPER) {
                log.info(
                        "{} action=PAPER模拟跟单 result=成功 followTaskId={} marketId={} outcome={} stakeUsdc={} executionMode=PAPER {}",
                        LOG_PREFIX,
                        task.getId(),
                        task.getMarketId(),
                        task.getOutcome(),
                        task.getRecommendedStakeUsdc(),
                        taskTrace(task)
                );
            }
            return result;
        } catch (Exception e) {
            log.error("{} action=执行跟单任务 result=异常 followTaskId={} wallet={} marketId={} outcome={} mode={} reason={} {}",
                    LOG_PREFIX, task == null ? null : task.getId(), task == null ? null : task.getWalletAddress(),
                    task == null ? null : task.getMarketId(), task == null ? null : task.getOutcome(),
                    task == null ? null : task.getExecutionMode(), e.getMessage(), taskTrace(task), e);
            log.info("{}", flow("S8_ORDER_EXECUTED", "执行跟单任务", "异常", null, task,
                    "mode", task == null ? null : task.getExecutionMode(),
                    "stakeUsdc", task == null ? null : task.getRecommendedStakeUsdc(),
                    "reasonSummary", e.getMessage()));
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
            log.info("{} action=记录跟单异常结果到账本 result=完成 followTaskId={} finalStatus=FAILED reason={} {}",
                    LOG_PREFIX, task.getId(), e.getMessage(), taskTrace(task));
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
                .recommendedStakeUsdc(decision == null ? task.getRecommendedStakeUsdc() : decision.getRecommendedStakeUsdc())
                .marketType(decision == null ? null : decision.getMarketType())
                .entryLane(decision == null ? null : decision.getEntryLane())
                .exitIntent(decision == null ? null : decision.getExitIntent())
                .status(task.getStatus())
                .executionMode(task.getExecutionMode())
                .externalOrderId(task.getExternalOrderId())
                .errorMessage(task.getErrorMessage())
                .primarySkipReason(task.getPrimarySkipReason())
                .skipReasonTags(parseCsv(task.getSkipReasonTags()))
                .wouldHaveSkippedReason(task.getWouldHaveSkippedReason())
                .consensusWallets(decision == null
                        ? (consensus == null ? null : consensus.getDistinctWallets())
                        : decision.getConsensusWallets())
                .consensusScore(decision == null
                        ? (consensus == null ? null : consensus.getConsensusScore())
                        : decision.getConsensusScore())
                .estimatedFeeUsdc(decision == null ? null : decision.getEstimatedFeeUsdc())
                .estimatedSlippageUsdc(decision == null ? null : decision.getEstimatedSlippageUsdc())
                .estimatedNetProfitabilityUsdc(decision == null ? null : decision.getEstimatedNetProfitabilityUsdc())
                .followProbability(decision == null ? null : decision.getFollowProbability())
                .calibratedFollowProbability(decision == null ? null : decision.getCalibratedFollowProbability())
                .slippageSpikeProbability(decision == null ? null : decision.getSlippageSpikeProbability())
                .estimateSampleSize(decision == null ? null : decision.getEstimateSampleSize())
                .signalScore(decision == null ? null : decision.getSignalScore())
                .signalScoreLiquidityFactor(decision == null ? null : decision.getSignalScoreLiquidityFactor())
                .signalScoreRiskPenalty(decision == null ? null : decision.getSignalScoreRiskPenalty())
                .signalScoreLeaderReliabilityFactor(decision == null ? null : decision.getSignalScoreLeaderReliabilityFactor())
                .taskExecutable(decision == null ? null : decision.isAccepted())
                .overrideApplied(decision == null ? null : decision.isOverrideApplied())
                .manualAllowlistApplied(decision == null ? null : decision.isManualAllowlistApplied())
                .manualAllowlistReason(decision == null ? null : decision.getManualAllowlistReason())
                .stakeMultiplierApplied(decision == null ? null : decision.getStakeMultiplierApplied())
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

    private MarketStateSnapshot refreshMarketState(SmartWalletTradeEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getMarketId())) {
            return MarketStateSnapshot.unknown(null);
        }
        return marketStateService.refreshMarket(
                event.getMarketId(),
                event.getQuestion(),
                StringUtils.hasText(event.getOutcome()) ? List.of(event.getOutcome()) : List.of()
        );
    }

    private boolean isIntentAggregationEnabled() {
        SmartWalletProperties.IntentAggregationConfig config = properties.getIntentAggregation();
        return config != null && config.isEnabled();
    }

    /**
     * 计算跟单任务的执行延迟（毫秒）。
     * 当意图聚合检测到碎片化（fragmentCount > 1）时，使用较长的延迟
     * （executionDelaySeconds，默认 30s），让后续碎片有时间到达并合并，
     * 避免每笔碎片各产生一个 1U 跟单。
     * 否则使用默认的 followDelaySeconds（默认 3s）快速执行。
     */
    private long resolveFollowDelayMs(int fragmentCount) {
        if (fragmentCount > 1 && isIntentAggregationEnabled()) {
            SmartWalletProperties.IntentAggregationConfig config = properties.getIntentAggregation();
            int delaySeconds = config.getExecutionDelaySeconds();
            if (delaySeconds > 0) {
                log.info("{} action=延长跟单执行延迟 fragmentCount={} delaySeconds={}",
                        LOG_PREFIX, fragmentCount, delaySeconds);
                return delaySeconds * 1000L;
            }
        }
        return properties.getFollowDelaySeconds() * 1000L;
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
            // 2026-05-17 ray@cowork: 修复 endAt 缓存命中后永远不重拉的问题。
            // 第一次 refresh=false 读 cache；若 cache 中 endAt 缺失或非法 → 强制 refresh=true 重拉 Polymarket Gamma。
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(marketId, false, questionHint);
            Long endAt = metadata == null ? null : metadata.getEndAt();
            if (endAt == null || endAt <= 0) {
                try {
                    PolymarketApiClient.MarketMetadata refreshed = marketMetadataService.getByConditionId(marketId, true, questionHint);
                    if (refreshed != null && refreshed.getEndAt() != null && refreshed.getEndAt() > 0) {
                        endAt = refreshed.getEndAt();
                        log.debug("{} action=解析跟单市场结束时间 result=强制刷新成功 marketId={} endAt={}",
                                LOG_PREFIX, marketId, endAt);
                    }
                } catch (Exception refreshEx) {
                    log.debug("{} action=解析跟单市场结束时间 result=强制刷新失败 marketId={} reason={}",
                            LOG_PREFIX, marketId, refreshEx.getMessage());
                }
            }
            return endAt;
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
        long shadowCount = 0;
        for (SmartWalletFollowTaskEntity task : windowTasks) {
            String status = task.getStatus() == null ? "UNKNOWN" : task.getStatus().toUpperCase(Locale.ROOT);
            tasksByStatus.merge(status, 1L, Long::sum);
            if ("SKIPPED".equals(status)) skippedCount++;
            if ("FOLLOWED".equals(status)) followedCount++;
            if (STATUS_PAPER_SHADOW.equals(status)) shadowCount++;
        }

        // ── 4. 拒绝/Shadow 原因分析（仅读 errorMessage，不重新评估）
        Map<String, Long> rejectionCategories = new TreeMap<>();
        for (SmartWalletFollowTaskEntity task : windowTasks) {
            if (!"SKIPPED".equalsIgnoreCase(task.getStatus())
                    && !STATUS_PAPER_SHADOW.equalsIgnoreCase(task.getStatus())) {
                continue;
            }
            if (StringUtils.hasText(task.getPrimarySkipReason())) {
                rejectionCategories.merge(task.getPrimarySkipReason(), 1L, Long::sum);
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
                .shadowTaskCount(shadowCount)
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
                update.setPrimarySkipReason("STALE");
                update.setSkipReasonTags("STALE");
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

    private SkipReasonSnapshot buildSkipReasonSnapshot(SmartWalletTradeEventEntity event, SignalRiskDecision decision) {
        Set<String> tags = new LinkedHashSet<>();
        if (event == null
                || !"BUY".equalsIgnoreCase(event.getSide())
                || !StringUtils.hasText(event.getMarketId())
                || !StringUtils.hasText(event.getOutcome())) {
            tags.add("NON_TRADABLE");
        }
        if (decision != null && decision.getReasons() != null) {
            for (String reason : decision.getReasons()) {
                String tag = classifyShadowTag(reason);
                if (StringUtils.hasText(tag)) {
                    tags.add(tag);
                }
            }
        }
        if (tags.isEmpty()) {
            tags.add("OTHER");
        }
        String primary = primarySkipReason(tags);
        return new SkipReasonSnapshot(primary, new ArrayList<>(tags));
    }

    private String resolveRejectedTaskStatus(SkipReasonSnapshot skipReason) {
        if (skipReason == null || "NON_TRADABLE".equals(skipReason.primaryReason())) {
            return STATUS_SKIPPED;
        }
        return STATUS_PAPER_SHADOW;
    }

    private String primarySkipReason(Set<String> tags) {
        List<String> priority = List.of(
                SmartWalletManualBlocklistService.REASON_MANUAL_BLOCKLIST,
                SmartWalletManualBlocklistService.REASON_INACTIVE_GT_35D_BLOCKLIST,
                "NON_TRADABLE", "PRICE", "PROFILE_BEHAVIOR", "CAPACITY", "STALE", "SIZE", "OTHER");
        for (String candidate : priority) {
            if (tags.contains(candidate)) {
                return candidate;
            }
        }
        return "OTHER";
    }

    private String classifyShadowTag(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String text = reason.toLowerCase(Locale.ROOT);
        if (text.contains("[" + SmartWalletManualBlocklistService.REASON_INACTIVE_GT_35D_BLOCKLIST.toLowerCase(Locale.ROOT) + "]")) {
            return SmartWalletManualBlocklistService.REASON_INACTIVE_GT_35D_BLOCKLIST;
        }
        if (text.contains("[" + SmartWalletManualBlocklistService.REASON_MANUAL_BLOCKLIST.toLowerCase(Locale.ROOT) + "]")
                || text.contains("smart-wallet blocklist")) {
            return SmartWalletManualBlocklistService.REASON_MANUAL_BLOCKLIST;
        }
        if (text.contains("[market_not_accepting_orders]")
                || text.contains("[market_closed]")
                || text.contains("[market_resolved]")
                || text.contains("market is no longer accepting orders")
                || text.contains("market is already closed")
                || text.contains("market is already resolved")
                || text.contains("sell signals are disabled")
                || text.contains("source trade event is missing")) {
            return "NON_TRADABLE";
        }
        if (text.contains("trigger price")) {
            return "PRICE";
        }
        if (text.contains("[archetype_block]")
                || text.contains("[behavior_block]")
                || text.contains("wallet is not qualified")) {
            return "PROFILE_BEHAVIOR";
        }
        if (text.contains("open position count")
                || text.contains("portfolio limit")
                || text.contains("total open position cost")
                || text.contains("portfolio budget")
                || text.contains("capital is depleted")
                || text.contains("bankroll penetration")
                || text.contains("minimum order")) {
            return "CAPACITY";
        }
        if (text.contains("signal is stale") || text.contains("[paper_canary_stale")) {
            return "STALE";
        }
        if (text.contains("trigger size") || text.contains("[trigger_size_too_small]")) {
            return "SIZE";
        }
        return "OTHER";
    }

    private List<String> parseCsv(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String flow(String stage,
                        String action,
                        String result,
                        SmartWalletTradeEventEntity event,
                        SmartWalletFollowTaskEntity task,
                        Object... extra) {
        List<Object> fields = new ArrayList<>();
        fields.add("flowStage");
        fields.add(stage);
        fields.add("action");
        fields.add(action);
        fields.add("result");
        fields.add(result);
        fields.add("eventId");
        fields.add(event == null ? (task == null ? null : task.getSourceEventId()) : event.getId());
        fields.add("followTaskId");
        fields.add(task == null ? null : task.getId());
        fields.add("wallet");
        fields.add(event == null ? (task == null ? null : task.getWalletAddress()) : event.getWalletAddress());
        fields.add("marketId");
        fields.add(event == null ? (task == null ? null : task.getMarketId()) : event.getMarketId());
        fields.add("outcome");
        fields.add(event == null ? (task == null ? null : task.getOutcome()) : event.getOutcome());
        fields.add("side");
        fields.add(event == null ? (task == null ? null : task.getSide()) : event.getSide());
        if (extra != null) {
            for (Object item : extra) {
                fields.add(item);
            }
        }
        return StrategyFlowLogFormatter.flow(fields.toArray());
    }

    private String openedAtBjt(SmartWalletTradeEventEntity event) {
        Long millis = openedAtMillis(event);
        return millis == null ? null : timeFormatter.formatEpochMillisToBjt(millis);
    }

    private Long openedAtMillis(SmartWalletTradeEventEntity event) {
        return event == null || event.getOpenedAt() == null ? null : event.getOpenedAt().getTime();
    }

    private String polymarketSearchUrl(SmartWalletTradeEventEntity event) {
        if (event == null || !StringUtils.hasText(event.getQuestion())) {
            return null;
        }
        return "https://polymarket.com/search?query=" + URLEncoder.encode(event.getQuestion().trim(), StandardCharsets.UTF_8);
    }

    private String eventTrace(SmartWalletTradeEventEntity event) {
        if (event == null) {
            return "";
        }
        return structuredFields(
                "question", event.getQuestion(),
                "openedAtBjt", openedAtBjt(event),
                "openedAtMs", openedAtMillis(event),
                "polymarketSearchUrl", polymarketSearchUrl(event)
        );
    }

    private String taskTrace(SmartWalletFollowTaskEntity task) {
        if (task == null) {
            return "";
        }
        return structuredFields(
                "marketInfoId", task.getMarketInfoId(),
                "question", task.getQuestion(),
                "tokenId", task.getMarketInfo() == null ? null : task.getMarketInfo().getTokenId(),
                "scheduledAtBjt", formatDateBjt(task.getScheduledAt()),
                "scheduledAtMs", dateMillis(task.getScheduledAt()),
                "executedAtBjt", formatDateBjt(task.getExecutedAt()),
                "executedAtMs", dateMillis(task.getExecutedAt()),
                "marketEndAtBjt", timeFormatter.formatEpochMillisToBjt(task.getMarketEndAt()),
                "marketEndAtMs", task.getMarketEndAt(),
                "polymarketSearchUrl", polymarketSearchUrl(task.getQuestion())
        );
    }

    private String polymarketSearchUrl(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        return "https://polymarket.com/search?query=" + URLEncoder.encode(question.trim(), StandardCharsets.UTF_8);
    }

    private String formatDateBjt(Date date) {
        Long millis = dateMillis(date);
        return millis == null ? null : timeFormatter.formatEpochMillisToBjt(millis);
    }

    private Long dateMillis(Date date) {
        return date == null ? null : date.getTime();
    }

    private String structuredFields(Object... keyValues) {
        StringBuilder builder = new StringBuilder();
        if (keyValues == null) {
            return "";
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key == null) {
                continue;
            }
            builder.append(key)
                    .append('=')
                    .append(formatStructuredValue(keyValues[i + 1]))
                    .append(' ');
        }
        return builder.toString().trim();
    }

    private String formatStructuredValue(Object raw) {
        if (raw == null) {
            return "-";
        }
        String value = String.valueOf(raw)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (value.isEmpty()) {
            return "-";
        }
        if (value.length() > 600) {
            value = value.substring(0, 600) + "...";
        }
        boolean quote = value.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch == '"' || ch == '=');
        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private String summarizeReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        return reasons.stream()
                .filter(StringUtils::hasText)
                .filter(reason -> reason.contains("BLOCK")
                        || reason.contains("REJECT")
                        || reason.contains("stale")
                        || reason.contains("below")
                        || reason.contains("outside")
                        || reason.contains("通过")
                        || reason.contains("拒绝"))
                .findFirst()
                .orElse(reasons.get(0));
    }

    private int compareTaskPriority(SmartWalletFollowTaskView left, SmartWalletFollowTaskView right) {
        int blockerCompare = Integer.compare(
                left == null || left.getBlockerCount() == null ? Integer.MAX_VALUE : left.getBlockerCount(),
                right == null || right.getBlockerCount() == null ? Integer.MAX_VALUE : right.getBlockerCount());
        if (blockerCompare != 0) {
            return blockerCompare;
        }

        BigDecimal leftEv = left == null || left.getEstimatedNetProfitabilityUsdc() == null
                ? BigDecimal.ZERO
                : left.getEstimatedNetProfitabilityUsdc();
        BigDecimal rightEv = right == null || right.getEstimatedNetProfitabilityUsdc() == null
                ? BigDecimal.ZERO
                : right.getEstimatedNetProfitabilityUsdc();
        int evCompare = rightEv.compareTo(leftEv);
        if (evCompare != 0) {
            return evCompare;
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
        if (text.contains("[market_end_at_data_quality]")) {
            return "MARKET_DATA_QUALITY";
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

    private static class ReasonAccumulator {
        private long count;
        private BigDecimal stake = BigDecimal.ZERO;
    }

    private static class CoverageAccumulator {
        private long count;
        private long wins;
        private BigDecimal pnl = BigDecimal.ZERO;

        private void add(SmartWalletLeaderTradeOutcomeEntity outcome) {
            if (outcome == null) {
                return;
            }
            count++;
            if (Boolean.TRUE.equals(outcome.getIsWin())) {
                wins++;
            }
            if (outcome.getEstimatedRealizedPnlUsdc() != null) {
                pnl = pnl.add(outcome.getEstimatedRealizedPnlUsdc());
            }
        }

        private BigDecimal winRate() {
            if (count <= 0) {
                return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            }
            return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
        }
    }

    private record SkipReasonSnapshot(String primaryReason, List<String> tags) {
    }

    private ConsensusSignalView findConsensus(SmartWalletFollowTaskEntity task) {
        return consensusSignalService.listRecentConsensusSignals().stream()
                .filter(item -> item.getMarketId() != null && item.getMarketId().equalsIgnoreCase(task.getMarketId()))
                .filter(item -> item.getOutcome() != null && item.getOutcome().equalsIgnoreCase(task.getOutcome()))
                .filter(item -> item.getSide() != null && item.getSide().equalsIgnoreCase(task.getSide()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 查询 PENDING + SKIPPED 状态的任务，拆成两次单值查询合并。
     * 单值 WHERE status = ? ORDER BY created_at DESC 可完美走 idx_follow_status_created 索引，
     * 而 IN ('PENDING','SKIPPED') 会导致 45k+ 行全表扫描 + filesort。
     */
    private List<SmartWalletFollowTaskEntity> loadPendingAndSkippedTasks(int limit) {
        List<SmartWalletFollowTaskEntity> pending = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getStatus, STATUS_PENDING)
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + limit));
        List<SmartWalletFollowTaskEntity> skipped = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .eq(SmartWalletFollowTaskEntity::getStatus, STATUS_SKIPPED)
                .orderByDesc(SmartWalletFollowTaskEntity::getCreatedAt)
                .last("limit " + limit));
        List<SmartWalletFollowTaskEntity> merged = new ArrayList<>(pending.size() + skipped.size());
        merged.addAll(pending);
        merged.addAll(skipped);
        merged.sort((a, b) -> {
            Date ca = a.getCreatedAt(), cb = b.getCreatedAt();
            if (ca == null && cb == null) return 0;
            if (ca == null) return 1;
            if (cb == null) return -1;
            return cb.compareTo(ca); // DESC
        });
        return merged.size() <= limit ? merged : merged.subList(0, limit);
    }

    private int normalizeLimit(Integer limit, int defaultLimit) {
        if (limit == null || limit <= 0) {
            return defaultLimit;
        }
        return Math.min(limit, 200);
    }

    private int normalizeExecutableScanLimit(Integer scanLimit, int resultLimit) {
        if (scanLimit != null && scanLimit > 0) {
            return Math.min(scanLimit, 200);
        }
        return Math.min(Math.max(resultLimit * 3, resultLimit), 60);
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

    private static class ExecutableRiskStats {
        int evaluatedCount;
        int cacheHitCount;
    }

    private record CachedRiskDecision(SignalRiskDecision decision, long expiresAtMs) {
    }

    private record PriceReference(BigDecimal price, String source) {
    }

    /**
     * W1-T4: 当 decision 为 REJECTED / PAPER_SHADOW / DOWNGRADED 时录影子记账.
     * recorder 缺失或异常都不阻塞主链路 (内部已 catch).
     *
     * decision_status 解析规则:
     *   - task.status == FOLLOWED                         → 不录 (真实跟单)
     *   - task.status == PAPER_SHADOW                     → PAPER_SHADOW
     *   - task.status == SKIPPED 且 decision.scaleUpSignal → 不录 (单纯的加仓不模拟)
     *   - 其它 SKIPPED                                    → REJECTED
     *
     * 仓位用于影子模拟的 stake 优先使用 task.recommendedStakeUsdc; 若为 0 / null
     * (拒绝路径常见), 退化为 trigger_size_usdc 作为"若放行原样跟单"的近似.
     */
    private void recordSignalShadowIfRejected(SmartWalletTradeEventEntity event,
                                              SignalRiskDecision decision,
                                              SmartWalletFollowTaskEntity task,
                                              SmartWalletMarketInfoEntity marketInfo) {
        if (signalShadowRecorder == null || event == null || decision == null || task == null) {
            return;
        }
        String taskStatus = task.getStatus();
        if (STATUS_FOLLOWED.equals(taskStatus) || STATUS_PENDING.equals(taskStatus)) {
            return; // 真实跟单不需要影子
        }
        String decisionStatus;
        if (STATUS_PAPER_SHADOW.equals(taskStatus)) {
            decisionStatus = "PAPER_SHADOW";
        } else if (STATUS_SKIPPED.equals(taskStatus)) {
            decisionStatus = "REJECTED";
        } else {
            decisionStatus = "DOWNGRADED";
        }

        BigDecimal stake = task.getRecommendedStakeUsdc();
        if (stake == null || stake.signum() <= 0) {
            stake = event.getSizeUsdc();
        }
        if (stake == null || stake.signum() <= 0) {
            return; // 没有可模拟的本金, 跳过
        }

        Long marketInfoId = marketInfo == null ? task.getMarketInfoId() : marketInfo.getId();
        try {
            signalShadowRecorder.record(event, decision, decisionStatus,
                    task.getId(), marketInfoId, event.getSide(), stake);
        } catch (RuntimeException ex) {
            log.warn("{} action=记录影子信号 result=失败 followTaskId={} eventId={} reason={}",
                    LOG_PREFIX, task.getId(), event.getId(), ex.toString());
        }
    }
}
