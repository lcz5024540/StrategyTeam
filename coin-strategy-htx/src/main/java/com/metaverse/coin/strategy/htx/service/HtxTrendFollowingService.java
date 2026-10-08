package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapFundingRate;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxFundingArbitrageProperties;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendRuntimeKlineRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendExitDecisionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendAssessmentEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionMarkSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendProfitProtectionEventEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendBreadthStats;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendPerformanceStats;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** Contract-only trend following for HTX swaps, safe to run in observe-only mode. */
@Slf4j
@Service
@ConditionalOnBean(HtxUsdtSwapApiService.class)
@ConditionalOnProperty(prefix = "htx.trend", name = "enabled", havingValue = "true")
public class HtxTrendFollowingService {

    private static final String LONG = "LONG";
    private static final String SHORT = "SHORT";
    private static final String POSITION_SIDE_LONG = "long";
    private static final String POSITION_SIDE_SHORT = "short";
    private static final String SIDE_BUY = "buy";
    private static final String SIDE_SELL = "sell";
    private static final String ORDER_TYPE_MARKET = "market";
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final String MODE_GATE = "mode gate";
    private static final String CANDIDATE_SCAN = "candidate scan";
    private static final String EXECUTION_PREFLIGHT = "execution preflight";
    private static final String ENTRY_GATE = "entry gate";
    private static final String ORDER_EXECUTION = "order execution";
    private static final String EXIT_ENGINE = "exit engine";
    private static final String EXIT_HARD_RISK = "HARD_RISK";
    private static final String EXIT_MODEL_INVALIDATION = "MODEL_INVALIDATION";
    private static final String EXIT_FEE_PROTECTION = "FEE_PROTECTION";
    private static final String EXIT_TREND_EXHAUSTION = "TREND_EXHAUSTION";
    private static final String EXIT_FUNDING_COST = "FUNDING_COST";
    private static final String EXIT_TRAILING_STOP = "TRAILING_STOP";
    private static final String EXIT_TARGET_ZONE = "TARGET_ZONE";
    private static final LocalDateTime SIGNAL_HISTORY_FLOOR = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final List<String> EXIT_CONFIRMATION_PERIODS = List.of("30min", "60min", "4hour", "1day");

    private final HtxUsdtSwapService swap;
    private final HtxContractProperties contractProperties;
    private final HtxFundingArbitrageProperties fundingArbitrageProperties;
    private final HtxMarketMakerProperties marketMakerProperties;
    private final HtxTrendProperties properties;
    private final HtxTrendRecorder recorder;
    private final HtxTrendSignalCalculator calculator;
    private final HtxTrendEnsembleShadowProperties ensembleProperties;
    private final HtxTrendEnsembleSignalCalculator ensembleCalculator;
    private final HtxTrendMirrorExecutor mirrorExecutor;
    private final HtxSwapFeeRateResolver feeRateResolver;
    private final HtxTrendExitDecisionEngine exitDecisionEngine = new HtxTrendExitDecisionEngine();
    private final HtxTrendStateEvaluator trendStateEvaluator = new HtxTrendStateEvaluator();
    private final HtxTrendProfitProtectionPlanner profitProtectionPlanner = new HtxTrendProfitProtectionPlanner();
    private HtxTrendCombinationService combinationService;
    private HtxTrendPortfolioAllocationAuditService portfolioAllocationAuditService;
    private HtxTradeAccountService tradeAccountService;
    private HtxTrendRuntimeKlineRecorder runtimeKlineRecorder;
    private HtxTrendRealtimePriceFeed realtimePriceFeed;
    private HtxTrendStructureLifecycleService structureLifecycleService;
    private final Map<String, EntryCandidate> pendingEntryCandidates = new ConcurrentHashMap<>();
    /** Per scheduler run: permits a compact, searchable summary of every final entry gate result. */
    private final Map<String, AtomicLong> entryGateOutcomeCounts = new ConcurrentHashMap<>();
    private volatile long lastCandidateScanCloseEpoch = Long.MIN_VALUE;

    @Autowired
    public HtxTrendFollowingService(HtxUsdtSwapApiService api,
                                    HtxContractProperties contractProperties,
                                    HtxFundingArbitrageProperties fundingArbitrageProperties,
                                    HtxMarketMakerProperties marketMakerProperties,
                                    HtxTrendProperties properties,
                                    HtxTrendRecorder recorder,
                                    HtxTrendMirrorExecutor mirrorExecutor,
                                    HtxTrendEnsembleShadowProperties ensembleProperties,
                                    HtxAccountFeeProfileRecorder feeProfileRecorder) {
        this(api.getSwapService(), contractProperties, fundingArbitrageProperties, marketMakerProperties,
                properties, recorder, new HtxTrendSignalCalculator(), ensembleProperties,
                new HtxTrendEnsembleSignalCalculator(), mirrorExecutor, feeProfileRecorder);
    }

    HtxTrendFollowingService(HtxUsdtSwapService swap,
                             HtxContractProperties contractProperties,
                             HtxFundingArbitrageProperties fundingArbitrageProperties,
                             HtxMarketMakerProperties marketMakerProperties,
                             HtxTrendProperties properties,
                             HtxTrendRecorder recorder,
                             HtxTrendSignalCalculator calculator) {
        this(swap, contractProperties, fundingArbitrageProperties, marketMakerProperties,
                properties, recorder, calculator, null);
    }

    HtxTrendFollowingService(HtxUsdtSwapService swap,
                             HtxContractProperties contractProperties,
                             HtxFundingArbitrageProperties fundingArbitrageProperties,
                             HtxMarketMakerProperties marketMakerProperties,
                             HtxTrendProperties properties,
                             HtxTrendRecorder recorder,
                             HtxTrendSignalCalculator calculator,
                             HtxTrendMirrorExecutor mirrorExecutor) {
        this(swap, contractProperties, fundingArbitrageProperties, marketMakerProperties,
                properties, recorder, calculator, new HtxTrendEnsembleShadowProperties(),
                new HtxTrendEnsembleSignalCalculator(), mirrorExecutor);
    }

    HtxTrendFollowingService(HtxUsdtSwapService swap,
                             HtxContractProperties contractProperties,
                             HtxFundingArbitrageProperties fundingArbitrageProperties,
                             HtxMarketMakerProperties marketMakerProperties,
                             HtxTrendProperties properties,
                             HtxTrendRecorder recorder,
                             HtxTrendSignalCalculator calculator,
                             HtxTrendEnsembleShadowProperties ensembleProperties,
                             HtxTrendEnsembleSignalCalculator ensembleCalculator,
                             HtxTrendMirrorExecutor mirrorExecutor) {
        this(swap, contractProperties, fundingArbitrageProperties, marketMakerProperties, properties, recorder,
                calculator, ensembleProperties, ensembleCalculator, mirrorExecutor, null);
    }

    HtxTrendFollowingService(HtxUsdtSwapService swap,
                             HtxContractProperties contractProperties,
                             HtxFundingArbitrageProperties fundingArbitrageProperties,
                             HtxMarketMakerProperties marketMakerProperties,
                             HtxTrendProperties properties,
                             HtxTrendRecorder recorder,
                             HtxTrendSignalCalculator calculator,
                             HtxTrendEnsembleShadowProperties ensembleProperties,
                             HtxTrendEnsembleSignalCalculator ensembleCalculator,
                             HtxTrendMirrorExecutor mirrorExecutor,
                             HtxAccountFeeProfileRecorder feeProfileRecorder) {
        this.swap = swap;
        this.contractProperties = contractProperties;
        this.fundingArbitrageProperties = fundingArbitrageProperties;
        this.marketMakerProperties = marketMakerProperties;
        this.properties = properties;
        this.recorder = recorder;
        this.calculator = calculator;
        this.ensembleProperties = ensembleProperties == null ? new HtxTrendEnsembleShadowProperties() : ensembleProperties;
        this.ensembleCalculator = ensembleCalculator == null ? new HtxTrendEnsembleSignalCalculator() : ensembleCalculator;
        this.mirrorExecutor = mirrorExecutor;
        this.feeRateResolver = new HtxSwapFeeRateResolver(swap, properties::getEstimatedTakerFeeRate,
                properties::getFeeRateCacheMs, "primary", feeProfileRecorder);
    }

    public RunResult runOnce() {
        return runOnce(true);
    }

    @Autowired(required = false)
    void setCombinationService(HtxTrendCombinationService combinationService) {
        this.combinationService = combinationService;
    }

    @Autowired(required = false)
    void setPortfolioAllocationAuditService(HtxTrendPortfolioAllocationAuditService portfolioAllocationAuditService) {
        this.portfolioAllocationAuditService = portfolioAllocationAuditService;
    }

    @Autowired(required = false)
    void setTradeAccountService(HtxTradeAccountService tradeAccountService) {
        this.tradeAccountService = tradeAccountService;
    }

    @Autowired(required = false)
    void setStructureLifecycleService(HtxTrendStructureLifecycleService structureLifecycleService) {
        this.structureLifecycleService = structureLifecycleService;
    }

    @Autowired(required = false)
    void setRuntimeKlineRecorder(HtxTrendRuntimeKlineRecorder runtimeKlineRecorder) {
        this.runtimeKlineRecorder = runtimeKlineRecorder;
    }

    @Autowired(required = false)
    void setRealtimePriceFeed(HtxTrendRealtimePriceFeed realtimePriceFeed) {
        this.realtimePriceFeed = realtimePriceFeed;
    }

    public boolean hasOpenLikePositions() {
        return recorder.countOpenLike() > 0L;
    }

    private HoldingAssessment holdingAssessment(HtxTrendPositionEntity position, TrendSignal signal) {
        if (position == null || position.getId() == null || signal == null || signal.barTime() == null) {
            return HoldingAssessment.unavailable();
        }
        HtxTrendStateEvaluator.HoldingState state = trendStateEvaluator.evaluateHolding(
                new HtxTrendStateEvaluator.HoldingInput(position.getSide(), signal.side(), signal.emaFast(), signal.emaSlow(),
                        majorTimeframeOpposition(position), againstBroadMarket(LONG.equals(position.getSide()) ? TrendSide.LONG : TrendSide.SHORT,
                        marketBreadthContext())));
        return new HoldingAssessment(state.name(), recorder.latestHoldingAssessmentStateBefore(position.getId(), signal.barTime()),
                "HOLDING:" + position.getId() + ":" + signal.barTime());
    }

    private void recordHoldingAssessment(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference,
                                         BigDecimal protection, ExitSnapshot snapshot,
                                         HtxTrendExitDecisionEngine.Decision decision, HoldingAssessment holding) {
        if (position == null || position.getId() == null || signal == null || signal.barTime() == null || holding == null
                || holding.state() == null || recorder.assessmentExists(holding.key())) return;
        HtxTrendAssessmentEntity assessment = new HtxTrendAssessmentEntity();
        assessment.setAssessmentKey(holding.key());
        assessment.setAssessmentStage("HOLDING_ASSESSMENT"); assessment.setPositionId(position.getId());
        assessment.setRunId(position.getRunId()); assessment.setAccountLabel(assessmentAccountLabel(position));
        assessment.setContractCode(position.getContractCode()); assessment.setSide(position.getSide());
        assessment.setPrimaryModel(position.getPrimaryModel()); assessment.setTimeframeProfile(position.getTimeframeProfile());
        assessment.setBarTime(signal.barTime()); assessment.setTrendState(holding.state()); assessment.setPreviousTrendState(holding.previousState());
        if (holding.previousState() != null && !holding.previousState().equals(holding.state())) {
            assessment.setTransitionType(holding.previousState() + "_TO_" + holding.state());
        }
        assessment.setTransitionReason(decision == null ? "non-fib holding evaluation" : decision.reason());
        assessment.setReferencePrice(reference); assessment.setExpectedRMultiple(position.getExpectedRMultiple());
        assessment.setCurrentNetPnl(snapshot.currentNet()); assessment.setMfeNetPnl(snapshot.mfe()); assessment.setMaeNetPnl(snapshot.mae());
        assessment.setProtectionPrice(decision == null ? protection : decision.protectionPrice());
        assessment.setEvidenceJson(decision == null ? "{\"signal\":\"" + signal.side() + "\"}" : decision.evidenceJson());
        recorder.recordAssessment(assessment);
        if (assessment.getTransitionType() != null) {
            HtxTrendAssessmentEntity transition = new HtxTrendAssessmentEntity();
            transition.setAssessmentKey("TRANSITION:" + position.getId() + ":" + signal.barTime());
            transition.setAssessmentStage("TREND_TRANSITION"); transition.setPositionId(position.getId());
            transition.setRunId(position.getRunId()); transition.setAccountLabel(assessmentAccountLabel(position));
            transition.setContractCode(position.getContractCode()); transition.setSide(position.getSide());
            transition.setPrimaryModel(position.getPrimaryModel()); transition.setTimeframeProfile(position.getTimeframeProfile());
            transition.setBarTime(signal.barTime()); transition.setTrendState(holding.state()); transition.setPreviousTrendState(holding.previousState());
            transition.setTransitionType(assessment.getTransitionType()); transition.setTransitionReason(assessment.getTransitionReason());
            transition.setReferencePrice(reference); transition.setProtectionPrice(assessment.getProtectionPrice());
            transition.setEvidenceJson(assessment.getEvidenceJson()); recorder.recordAssessment(transition);
            log.info("[HTX-TREND-STATE] positionId={} contract={} assessmentStage=TREND_TRANSITION trendState={} previousState={} transitionReason={}",
                    position.getId(), position.getContractCode(), holding.state(), holding.previousState(), assessment.getTransitionReason());
        }
    }

    /**
     * Manages and reconciles only existing positions. It deliberately omits candidate scans, factor snapshots,
     * and every entry path so an exit supervisor cannot create a new position.
     */
    RunResult manageOpenPositionsOnly() {
        List<String> contractCodes = liveContractCodes();
        if (contractCodes.isEmpty()) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(MODE_GATE, "trend contract list is blank"));
        }
        RunResult selected = null;
        List<String> summaries = new ArrayList<>();
        for (String contractCode : contractCodes) {
            RunResult result = manageActiveContract(contractCode);
            if (result == null) {
                continue;
            }
            selected = select(selected, tag(contractCode, result));
            summaries.add(contractCode + "=" + result.code() + ":" + result.message());
        }
        if (selected == null) {
            return RunResult.of(RunCode.SKIPPED, null, "exit supervisor found no open-like trend positions");
        }
        return RunResult.of(selected.code(), selected.positionId(), selected.message()
                + "; exitSupervisor=true; managedContracts=" + String.join(" | ", summaries));
    }

    RunResult runOnce(boolean entryWindowAllowsNewEntries) {
        return runOnce(() -> entryWindowAllowsNewEntries);
    }

    /**
     * Evaluates the entry-window policy only after candidate collection finishes.
     * A 52-contract scan can begin before the post-close window and finish inside it;
     * freezing the scheduler-start result would incorrectly discard that fresh candidate.
     */
    RunResult runOnce(BooleanSupplier entryWindowAllowsNewEntries) {
        List<String> contractCodes = liveContractCodes();
        entryGateOutcomeCounts.clear();
        if (contractCodes.isEmpty()) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(MODE_GATE, "trend contract list is blank"));
        }
        long candidateScanCloseEpoch = currentStableCandleCloseEpoch();
        boolean scanEntryCandidates = markCandidateScanStarted(candidateScanCloseEpoch);
        long openLikePositions = recorder.countOpenLike();
        int maxActivePositions = Math.max(0, properties.getMaxActivePositions());
        MarketBreadthContext marketBreadth = marketBreadthContext();
        MarketAnchorContext marketAnchor = MarketAnchorContext.neutral("not evaluated");
        PerformanceContext historicalPerformance = performanceContext();
        List<String> summaries = new ArrayList<>();
        Map<String, EntryCandidate> entryCandidates = new HashMap<>();
        long candidateScanDurationMs = 0L;
        int candidateScanConcurrency = candidateScanConcurrency(contractCodes.size());
        int candidateScanContracts = scanEntryCandidates ? contractCodes.size() : 0;
        RunResult selected = null;
        FactorSnapshotCaptureSummary factorSnapshotSummary = FactorSnapshotCaptureSummary.notRun();

        for (String contractCode : contractCodes) {
            RunResult result = manageActiveContract(contractCode);
            if (result == null) {
                continue;
            }
            RunResult tagged = tag(contractCode, result);
            summaries.add(contractCode + "=" + result.code() + ":" + result.message());
            selected = select(selected, tagged);
        }

        addPendingEntryCandidates(entryCandidates, summaries);
        boolean hasFreshCarryoverCandidate = hasFreshPendingEntryCandidate(new ArrayList<>(entryCandidates.values()));
        if (scanEntryCandidates || hasFreshCarryoverCandidate) {
            marketAnchor = marketAnchorContext();
        }
        if (scanEntryCandidates && combinationService != null) {
            combinationService.recordMarketSnapshot(candidateScanCloseEpoch, marketBreadth.regime().name(),
                    marketBreadth.total(), marketBreadth.longCount(), marketBreadth.shortCount(), marketBreadth.reason(),
                    marketAnchor.regime().name(), marketAnchor.validSignals(), marketAnchor.longSignals(),
                    marketAnchor.shortSignals(), marketAnchor.reason());
        }

        if (scanEntryCandidates) {
            long scanStartedAt = System.nanoTime();
            for (CandidateScanResult scanResult : evaluateEntryCandidates(contractCodes, marketBreadth, marketAnchor,
                    candidateScanConcurrency)) {
                String contractCode = scanResult.contractCode();
                CandidateEvaluation evaluation = scanResult.evaluation();
                if (evaluation == null) {
                    pendingEntryCandidates.remove(contractCode);
                    continue;
                }
                RunResult tagged = tag(contractCode, evaluation.result());
                summaries.add(contractCode + "=" + evaluation.result().code() + ":" + evaluation.result().message());
                if (evaluation.candidate() == null) {
                    pendingEntryCandidates.remove(contractCode);
                    selected = select(selected, tagged);
                } else {
                    rememberEntryCandidate(evaluation.candidate());
                    putBestCandidate(entryCandidates, evaluation.candidate());
                }
            }
            candidateScanDurationMs = Duration.ofNanos(System.nanoTime() - scanStartedAt).toMillis();
        } else {
            RunResult scanSkipped = RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(CANDIDATE_SCAN, "already completed closeEpoch=" + candidateScanCloseEpoch));
            selected = select(selected, tag("ENTRY_SCAN", scanSkipped));
            summaries.add("ENTRY_SCAN=" + scanSkipped.code() + ":" + scanSkipped.message());
        }

        long remainingSlots = properties.isCombinationEntryEnabled() && maxActivePositions == 0
                ? Long.MAX_VALUE : Math.max(0L, maxActivePositions - openLikePositions);
        List<EntryCandidate> rankedCandidates = entryCandidates.values().stream()
                .sorted(Comparator.comparing(EntryCandidate::score).reversed())
                .toList();
        recordPortfolioAllocationObservation(rankedCandidates, candidateScanCloseEpoch, remainingSlots);
        if (!rankedCandidates.isEmpty()) {
            boolean entryWindowOpenAtExecution = entryWindowAllowsNewEntries.getAsBoolean();
            if (!properties.isEntryEnabled()) {
                RunResult blocked = RunResult.of(RunCode.SKIPPED, null,
                        gateMessage(EXECUTION_PREFLIGHT, "new trend entries disabled"));
                selected = select(selected, tag("ENTRY", blocked));
                summaries.add("ENTRY=" + blocked.code() + ":" + blocked.message());
            } else if (!entryWindowOpenAtExecution && !hasFreshCarryoverCandidate) {
                RunResult blocked = RunResult.of(RunCode.SKIPPED, null,
                        gateMessage(EXECUTION_PREFLIGHT, "entry window closed; candidates cached"));
                selected = select(selected, tag("ENTRY", blocked));
                summaries.add("ENTRY=" + blocked.code() + ":" + blocked.message());
            } else if (remainingSlots <= 0L) {
                RunResult blocked = RunResult.of(RunCode.SKIPPED, null,
                        gateMessage(EXECUTION_PREFLIGHT, "max active trend positions reached"));
                selected = select(selected, tag("ENTRY", blocked));
                summaries.add("ENTRY=" + blocked.code() + ":" + blocked.message());
            } else {
                long opened = 0L;
                for (EntryCandidate candidate : rankedCandidates) {
                    if (opened >= remainingSlots) {
                        break;
                    }
                    RunResult result = openIfEligible(candidate.contractCode(), candidate.signal(),
                            candidate.entryCandles(), candidate.contract(), marketBreadth, marketAnchor, candidate.combination());
                    pendingEntryCandidates.remove(candidate.contractCode());
                    RunResult tagged = tag(candidate.contractCode(), result);
                    summaries.add(candidate.contractCode() + "=" + result.code() + ":" + result.message());
                    selected = select(selected, tagged);
                    if (opensPosition(result)) {
                        opened++;
                    }
                }
            }
        }

        if (scanEntryCandidates && combinationService != null && properties.isFactorSnapshotEnabled()) {
            long snapshotStartedAt = System.nanoTime();
            factorSnapshotSummary = recordMultiTimeframeFactorSnapshots(contractCodes, candidateScanCloseEpoch,
                    marketBreadth, candidateScanConcurrency(contractCodes.size()));
            factorSnapshotSummary = factorSnapshotSummary.withDuration(
                    Duration.ofNanos(System.nanoTime() - snapshotStartedAt).toMillis());
        }

        HtxTrendRuntimeKlineRecorder.QualitySnapshot klineQuality = runtimeKlineRecorder == null
                ? HtxTrendRuntimeKlineRecorder.QualitySnapshot.unavailable() : runtimeKlineRecorder.qualitySnapshot();
        log.info("[HTX-TREND-SCAN] assessmentStage=SCAN_SUMMARY contracts={} candidateScanDurationMs={} gateOutcomes={} "
                        + "klineEvidence={}", candidateScanContracts, candidateScanDurationMs, gateOutcomeSummary(), klineQuality.summary());
        if (selected == null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(CANDIDATE_SCAN, "no trend contracts evaluated"));
        }
        return RunResult.of(selected.code(), selected.positionId(), selected.message()
                + "; market=" + marketBreadth.summary()
                + "; anchor=" + marketAnchor.summary()
                + "; historicalStats=" + historicalPerformance.summary()
                + "; candidates=" + rankedCandidates.size()
                + "; pendingCandidates=" + pendingEntryCandidates.size()
                + "; candidateScanCloseEpoch=" + candidateScanCloseEpoch
                + "; candidateScanConcurrency=" + candidateScanConcurrency
                + "; candidateScanContracts=" + candidateScanContracts
                + "; candidateScanDurationMs=" + candidateScanDurationMs
                + "; factorSnapshots=" + factorSnapshotSummary.summary()
                + "; remainingSlots=" + remainingSlots
                + "; liveContracts=" + String.join(" | ", summaries));
    }

    /**
     * P2 observation runs after live candidates have already passed their gates and orders have been submitted.
     * It is read-only and bounded separately from the entry scanner, so extra evidence cannot make a valid signal stale.
     */
    private FactorSnapshotCaptureSummary recordMultiTimeframeFactorSnapshots(List<String> contractCodes,
                                                                               long entryBarTime,
                                                                               MarketBreadthContext marketBreadth,
                                                                               int scanConcurrency) {
        List<String> periods = normalizedPeriods(properties.getFactorSnapshotPeriods());
        if (contractCodes == null || contractCodes.isEmpty() || periods.isEmpty()) {
            return FactorSnapshotCaptureSummary.skipped("contracts or periods blank");
        }
        int concurrency = Math.min(Math.max(1, properties.getFactorSnapshotConcurrency()),
                Math.max(1, Math.min(contractCodes.size(), scanConcurrency)));
        if (concurrency <= 1 || contractCodes.size() <= 1) {
            List<FactorSnapshotCaptureResult> results = new ArrayList<>(contractCodes.size());
            for (String contractCode : contractCodes) {
                results.add(captureMultiTimeframeFactorSnapshot(contractCode, entryBarTime, marketBreadth, periods));
            }
            return FactorSnapshotCaptureSummary.from(results, periods.size());
        }
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "htx-trend-factor-snapshot-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, threadFactory);
        try {
            List<Callable<FactorSnapshotCaptureResult>> tasks = new ArrayList<>(contractCodes.size());
            for (String contractCode : contractCodes) {
                tasks.add(() -> captureMultiTimeframeFactorSnapshot(contractCode, entryBarTime, marketBreadth, periods));
            }
            List<FactorSnapshotCaptureResult> results = new ArrayList<>(contractCodes.size());
            for (Future<FactorSnapshotCaptureResult> future : executor.invokeAll(tasks)) {
                results.add(future.get());
            }
            return FactorSnapshotCaptureSummary.from(results, periods.size());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return FactorSnapshotCaptureSummary.skipped("interrupted");
        } catch (ExecutionException ex) {
            return FactorSnapshotCaptureSummary.skipped("failed: " + rootMessage(ex));
        } finally {
            executor.shutdownNow();
        }
    }

    private FactorSnapshotCaptureResult captureMultiTimeframeFactorSnapshot(String contractCode, long entryBarTime,
                                                                              MarketBreadthContext marketBreadth,
                                                                              List<String> periods) {
        List<HtxTrendEnsembleSignalCalculator.TrendCandidateSignal> signals = new ArrayList<>(periods.size());
        int available = 0;
        int modelCoverageOutcomes = 0;
        List<RuntimeKlineWindow> runtimeWindows = new ArrayList<>(periods.size());
        for (String period : periods) {
            HtxRestResponse<List<HtxSwapKline>> response = swap.historyKline(contractCode, period,
                    Math.max(1, properties.getFactorSnapshotKlineSize()));
            if (response == null || !response.ok() || response.payload() == null) {
                signals.add(HtxTrendEnsembleSignalCalculator.TrendCandidateSignal.skip(period,
                        "history kline unavailable: " + error(response)));
                continue;
            }
            HtxTrendEnsembleSignalCalculator.TrendCandidateSignal signal = ensembleCalculator.evaluate(response.payload(),
                    ensembleProperties, period, Instant.now().getEpochSecond());
            signals.add(signal);
            runtimeWindows.add(new RuntimeKlineWindow(period, signal.barTime(), response.payload(),
                    Math.max(1, properties.getFactorSnapshotKlineSize())));
            if (signal.barTime() != null && signal.barTime() > 0L) {
                available++;
            }
            if (period.equalsIgnoreCase(properties.getPeriod())) {
                HtxSwapFeeRateResolver.FeeRates fees = feeRateResolver.rates(contractCode);
                modelCoverageOutcomes = combinationService.recordMaturedModelCoverageOutcomes(contractCode, period,
                        response.payload(), ensembleCalculator, ensembleProperties,
                        new HtxTrendCombinationService.ModelCoverageFeeContext("primary", fees.openTaker(),
                                fees.closeTaker(), fees.fallback() ? "CONFIGURED_FALLBACK" : "HTX_ACCOUNT", fees.fallback()));
            }
        }
        Long factorSnapshotId = combinationService.recordMultiTimeframeSnapshot(contractCode, properties.getPeriod(), entryBarTime,
                marketBreadth == null ? "UNKNOWN" : marketBreadth.regime().name(), signals);
        for (RuntimeKlineWindow window : runtimeWindows) {
            captureRuntimeKlines("FACTOR_SNAPSHOT", contractCode, window.period(), window.targetBarTime(),
                    window.expectedWindowSize(), null, factorSnapshotId, null, window.candles());
        }
        return new FactorSnapshotCaptureResult(contractCode, available, periods.size(), modelCoverageOutcomes);
    }

    private List<CandidateScanResult> evaluateEntryCandidates(List<String> contractCodes,
                                                              MarketBreadthContext marketBreadth,
                                                              MarketAnchorContext marketAnchor,
                                                              int concurrency) {
        if (contractCodes.size() <= 1 || concurrency <= 1) {
            List<CandidateScanResult> results = new ArrayList<>(contractCodes.size());
            for (String contractCode : contractCodes) {
                results.add(scanEntryCandidate(contractCode, marketBreadth, marketAnchor));
            }
            return results;
        }
        AtomicInteger threadIndex = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "htx-trend-candidate-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, threadFactory);
        try {
            List<Callable<CandidateScanResult>> tasks = new ArrayList<>(contractCodes.size());
            for (String contractCode : contractCodes) {
                tasks.add(() -> scanEntryCandidate(contractCode, marketBreadth, marketAnchor));
            }
            List<Future<CandidateScanResult>> futures = executor.invokeAll(tasks);
            List<CandidateScanResult> results = new ArrayList<>(futures.size());
            for (Future<CandidateScanResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            List<CandidateScanResult> interrupted = new ArrayList<>(contractCodes.size());
            for (String contractCode : contractCodes) {
                interrupted.add(new CandidateScanResult(contractCode, new CandidateEvaluation(
                        RunResult.of(RunCode.SKIPPED, null, gateMessage(CANDIDATE_SCAN, "interrupted")), null)));
            }
            return interrupted;
        } catch (ExecutionException ex) {
            return List.of(new CandidateScanResult("ENTRY_SCAN", new CandidateEvaluation(
                    RunResult.of(RunCode.SKIPPED, null, gateMessage(CANDIDATE_SCAN, "failed: " + rootMessage(ex))), null)));
        } finally {
            executor.shutdownNow();
        }
    }

    private CandidateScanResult scanEntryCandidate(String contractCode, MarketBreadthContext marketBreadth,
                                                   MarketAnchorContext marketAnchor) {
        try {
            return new CandidateScanResult(contractCode, evaluateEntryCandidate(contractCode, marketBreadth, marketAnchor));
        } catch (RuntimeException ex) {
            log.warn("[HTX-TREND] candidate scan failed contract={}", contractCode, ex);
            return new CandidateScanResult(contractCode, new CandidateEvaluation(
                    RunResult.of(RunCode.SKIPPED, null, gateMessage(CANDIDATE_SCAN, "failed: " + rootMessage(ex))), null));
        }
    }

    private int candidateScanConcurrency(int contractCount) {
        int configured = Math.max(1, properties.getCandidateScanConcurrency());
        return Math.min(configured, Math.max(1, contractCount));
    }

    /**
     * P3 observation only: consume the whole ranked combination-candidate batch before the first
     * live gate runs. The allocator output is persisted as evidence, never read by openIfEligible.
     */
    private void recordPortfolioAllocationObservation(List<EntryCandidate> rankedCandidates, long scanCloseEpoch,
                                                      long remainingSlots) {
        if (!properties.isPortfolioAuditEnabled() || portfolioAllocationAuditService == null
                || tradeAccountService == null || rankedCandidates == null || rankedCandidates.isEmpty()) {
            return;
        }
        List<HtxTrendPortfolioAllocationAuditService.AuditedCandidate> candidates = rankedCandidates.stream()
                .filter(candidate -> candidate.combination() != null && candidate.signal().barTime() != null
                        && candidate.signal().side() != TrendSide.FLAT)
                .filter(candidate -> positive(candidate.combination().plannedNotionalUsdt()))
                .filter(candidate -> positive(properties.getRiskPerTradeUsdt()))
                .map(candidate -> new HtxTrendPortfolioAllocationAuditService.AuditedCandidate(properties.getPeriod(),
                        candidate.signal().barTime(), isBlank(candidate.combination().marketRegime()) ? "UNKNOWN"
                                : candidate.combination().marketRegime(),
                        new HtxTrendPortfolioAllocator.Candidate(candidate.combination().runId(), candidate.contractCode(),
                                candidate.signal().side().name(), candidate.combination().plannedNotionalUsdt(),
                                nonNegative(properties.getRiskPerTradeUsdt()), candidate.score(),
                                portfolioCorrelationCluster(candidate.contractCode()))))
                .toList();
        if (candidates.isEmpty()) return;
        Optional<Long> accountId = tradeAccountService.primaryTrendAccountId();
        if (accountId.isEmpty()) {
            log.warn("[HTX-TREND] portfolio audit skipped: primary DB account identity unavailable");
            return;
        }
        BigDecimal availableMargin = portfolioAvailableMargin(candidates.get(0).candidate().contractCode());
        if (!positive(availableMargin)) {
            log.warn("[HTX-TREND] portfolio audit skipped: global available margin unavailable");
            return;
        }
        int capacity = remainingSlots == Long.MAX_VALUE ? candidates.size()
                : (int) Math.min(Integer.MAX_VALUE, Math.max(1L, remainingSlots));
        BigDecimal largestNotional = candidates.stream().map(value -> value.candidate().requestedNotional())
                .max(BigDecimal::compareTo).orElse(properties.getMaxQuoteNotional());
        BigDecimal maxGross = largestNotional.multiply(BigDecimal.valueOf(capacity));
        try {
            portfolioAllocationAuditService.allocateAndRecord("portfolio-scan:" + scanCloseEpoch, candidates,
                    List.of(new HtxTrendPortfolioAllocator.AccountCapacity(accountId.get(), "primary", true,
                            availableMargin, maxGross, nonNegative(properties.getEstimatedTakerFeeRate()))),
                    new HtxTrendPortfolioAllocator.RiskBudget(capacity,
                            Math.max(1, properties.getPortfolioMaxPositionsPerCluster()), maxGross, maxGross));
        } catch (RuntimeException exception) {
            log.warn("[HTX-TREND] portfolio audit failed without affecting entry path", exception);
        }
    }

    private BigDecimal portfolioAvailableMargin(String contractCode) {
        HtxRestResponse<List<HtxSwapAccount>> response = swap.accountInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) return null;
        return response.payload().stream().filter(item -> item != null).map(HtxSwapAccount::getMarginAvailable)
                .filter(HtxTrendFollowingService::positive).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String portfolioCorrelationCluster(String contractCode) {
        if (properties.getPortfolioCorrelationClusters() != null) {
            String configured = properties.getPortfolioCorrelationClusters().get(contractCode);
            if (configured != null && !configured.isBlank()) return configured.trim();
        }
        return "UNCLASSIFIED:" + contractCode;
    }

    private void addPendingEntryCandidates(Map<String, EntryCandidate> entryCandidates, List<String> summaries) {
        for (EntryCandidate candidate : pendingEntryCandidates.values()) {
            if (candidate == null || isBlank(candidate.contractCode())) {
                continue;
            }
            if (configGate(candidate.contractCode()) != null || entrySignalFreshnessGate(candidate.signal()) != null) {
                pendingEntryCandidates.remove(candidate.contractCode());
                continue;
            }
            putBestCandidate(entryCandidates, candidate);
            summaries.add(candidate.contractCode() + "=OBSERVED:cached entry candidate score="
                    + candidate.score().setScale(2, RoundingMode.HALF_UP)
                    + " reason=" + candidate.signal().reason());
        }
    }

    private void rememberEntryCandidate(EntryCandidate candidate) {
        pendingEntryCandidates.compute(candidate.contractCode(), (contractCode, existing) -> {
            if (existing == null || candidate.score().compareTo(existing.score()) > 0) {
                return candidate;
            }
            return existing;
        });
    }

    private static void putBestCandidate(Map<String, EntryCandidate> candidates, EntryCandidate candidate) {
        candidates.merge(candidate.contractCode(), candidate,
                (left, right) -> right.score().compareTo(left.score()) > 0 ? right : left);
    }

    private boolean hasFreshPendingEntryCandidate(List<EntryCandidate> candidates) {
        for (EntryCandidate candidate : candidates) {
            if (entrySignalFreshnessGate(candidate.signal()) == null) {
                return true;
            }
        }
        return false;
    }

    private synchronized boolean markCandidateScanStarted(long closeEpoch) {
        if (closeEpoch <= lastCandidateScanCloseEpoch) {
            return false;
        }
        lastCandidateScanCloseEpoch = closeEpoch;
        return true;
    }

    private long currentStableCandleCloseEpoch() {
        long periodSeconds = periodSeconds(properties.getPeriod());
        long now = Instant.now().getEpochSecond();
        long stableNow = now - Math.max(0L, properties.getCandleCloseGraceSeconds());
        return stableNow - Math.floorMod(stableNow, periodSeconds);
    }

    private RunResult manageActiveContract(String contractCode) {
        String gate = configGate(contractCode);
        if (gate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gate);
        }
        Optional<HtxTrendPositionEntity> active = recorder.findActive(contractCode);
        if (active.isEmpty()) {
            return null;
        }
        pendingEntryCandidates.remove(contractCode);
        RunResult recoveredClose = recoverAcceptedClose(active.get());
        if (recoveredClose != null) {
            return recoveredClose;
        }
        RunResult recoveredPartialClose = recoverAcceptedPartialClose(active.get());
        if (recoveredPartialClose != null) {
            return recoveredPartialClose;
        }
        HtxSwapContractInfo contract = loadContract(contractCode);
        if (contract == null || !positive(contract.getContractSize())) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(EXECUTION_PREFLIGHT, "contract info unavailable"));
        }
        SignalSnapshot snapshot = loadSignal(contractCode);
        captureSignalWindows("POSITION_MANAGEMENT", contractCode, snapshot, active.get().getRunId(),
                active.get().getFactorSnapshotId(), active.get().getId());
        TrendSignal rawSignal = snapshot.signal();
        recordSignal(contractCode, rawSignal);
        return advance(active.get(), rawSignal, contract, snapshot.entryCandles());
    }

    private CandidateEvaluation evaluateEntryCandidate(String contractCode, MarketBreadthContext marketBreadth,
                                                       MarketAnchorContext marketAnchor) {
        String gate = configGate(contractCode);
        if (gate != null) {
            return new CandidateEvaluation(RunResult.of(RunCode.SKIPPED, null, gate), null);
        }
        Optional<HtxTrendPositionEntity> active = recorder.findActive(contractCode);
        if (active.isPresent()) {
            return null;
        }
        HtxSwapContractInfo contract = loadContract(contractCode);
        if (contract == null || !positive(contract.getContractSize())) {
            return new CandidateEvaluation(RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(EXECUTION_PREFLIGHT, "contract info unavailable")), null);
        }
        SignalSnapshot snapshot = loadSignal(contractCode);
        captureSignalWindows("ENTRY_SCAN", contractCode, snapshot, null, null, null);
        TrendSignal rawSignal = snapshot.signal();
        if (properties.isCombinationEntryEnabled()) {
            return combinationCandidateEvaluation(contractCode, contract, snapshot, marketBreadth, marketAnchor);
        }
        TrendSignal signal = entrySignalWithCarryContext(contractCode, rawSignal, marketBreadth);
        signal = entrySignalWithEnsembleContext(contractCode, signal, snapshot.entryCandles(), marketBreadth);
        signal = entrySignalWithMarketAnchorContext(signal, marketAnchor);
        recordSignal(contractCode, signal);
        if (signal.side() == TrendSide.FLAT) {
            return new CandidateEvaluation(RunResult.of(RunCode.NO_SIGNAL, null, signal.reason()), null);
        }
        BigDecimal score = entryCandidateScore(signal, marketBreadth, marketAnchor);
        EntryCandidate candidate = new EntryCandidate(contractCode, signal, snapshot.entryCandles(), contract, score, null);
        return new CandidateEvaluation(RunResult.of(RunCode.OBSERVED, null,
                "entry candidate queued side=" + signal.side() + " score=" + score.setScale(2, RoundingMode.HALF_UP)
                        + " reason=" + signal.reason()), candidate);
    }

    private CandidateEvaluation combinationCandidateEvaluation(String contractCode, HtxSwapContractInfo contract,
                                                               SignalSnapshot snapshot, MarketBreadthContext marketBreadth,
                                                               MarketAnchorContext marketAnchor) {
        if (combinationService == null) {
            return new CandidateEvaluation(RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(EXECUTION_PREFLIGHT, "combination service unavailable")), null);
        }
        HtxTrendEnsembleSignalCalculator.TrendCandidateSignal ensemble = ensembleCalculator.evaluate(snapshot.entryCandles(),
                ensembleProperties, properties.getPeriod(), Instant.now().getEpochSecond());
        HtxTrendCombinationService.Selection selection = combinationService.select(contractCode, properties.getPeriod(),
                snapshot.entryCandles(), ensemble, marketEntryRank(marketBreadth, marketAnchor));
        // Compatibility for existing integrations that still implement the pre-ranking selection contract.
        if (selection == null) {
            selection = combinationService.select(contractCode, properties.getPeriod(), snapshot.entryCandles(), ensemble);
        }
        if (!selection.accepted()) {
            recordSignal(contractCode, snapshot.signal());
            return new CandidateEvaluation(RunResult.of(RunCode.NO_SIGNAL, null, selection.rejection()), null);
        }
        TrendSide side = trendSide(selection.candidate().direction());
        TrendSignal signal = TrendSignal.of(side, ensemble.barTime(), ensemble.closePrice(), ensemble.emaFast(),
                ensemble.emaSlow(), ensemble.breakoutHigh(), ensemble.breakoutLow(), ensemble.atr(), ensemble.atrPct(),
                ensemble.adx(), selection.candidate().hypothesis() + "; policy=" + selection.policy().getPolicyCode());
        captureSignalWindows("ENTRY_GATE", contractCode, snapshot, selection.runId(), selection.factorSnapshotId(), null);
        recordSignal(contractCode, signal);
        BigDecimal score = selection.candidate().score();
        CombinationContext context = new CombinationContext(selection, ensemble.volumeRatio());
        captureFibonacciExecutionObservation(contractCode, signal, context);
        EntryCandidate candidate = new EntryCandidate(contractCode, signal, snapshot.entryCandles(), contract, score, context);
        return new CandidateEvaluation(RunResult.of(RunCode.OBSERVED, null,
                "combination candidate queued policy=" + selection.policy().getPolicyCode() + " score=" + score), candidate);
    }

    /**
     * Preserves the short execution context for later market/limit/confirmation replay.  This method is strictly
     * observational: its failure cannot reject a candidate or alter the exchange order path.
     */
    private void captureFibonacciExecutionObservation(String contractCode, TrendSignal signal, CombinationContext context) {
        if (context == null || signal == null || !properties.isFibExecutionObservationEnabled()
                || !"FIB_PULLBACK_CONTINUATION".equals(context.primaryModel())) {
            return;
        }
        int size = Math.max(2, properties.getFibExecutionObservationKlineSize());
        for (String period : properties.getFibExecutionObservationPeriods()) {
            if (period == null || period.isBlank()) continue;
            HtxRestResponse<List<HtxSwapKline>> response = swap.historyKline(contractCode, period, size);
            if (response == null || !response.ok() || response.payload() == null || response.payload().isEmpty()) {
                log.debug("[HTX-TREND-FIB-OBSERVE] unavailable contract={} period={}", contractCode, period);
                continue;
            }
            captureRuntimeKlines("FIB_EXECUTION_OBSERVATION", contractCode, period, signal.barTime(), size,
                    context.runId(), context.factorSnapshotId(), null, response.payload());
        }
    }

    private TrendSignal entrySignalWithCarryContext(String contractCode, TrendSignal signal, MarketBreadthContext marketBreadth) {
        if (!properties.isFundingCarryEntryEnabled() || signal == null || signal.side() != TrendSide.FLAT) {
            return signal;
        }
        FundingMarket funding = currentFunding(contractCode);
        if (funding == null || funding.fundingRate() == null || funding.fundingRate().signum() == 0) {
            return signal;
        }
        BigDecimal fundingRate = funding.fundingRate();
        BigDecimal minCarry = nonNegative(properties.getMinCarryFundingRateAbs());
        if (fundingRate.abs().compareTo(minCarry) < 0) {
            return signal;
        }
        TrendSide carrySide = fundingRate.signum() > 0 ? TrendSide.SHORT : TrendSide.LONG;
        String rejection = carryEntryRejectionReason(signal, carrySide, marketBreadth);
        if (rejection != null) {
            return TrendSignal.of(TrendSide.FLAT, signal.barTime(), signal.closePrice(), signal.emaFast(),
                    signal.emaSlow(), signal.breakoutHigh(), signal.breakoutLow(), signal.atr(), signal.atrPct(),
                    signal.adx(), signal.reason() + "; funding carry rejected side=" + carrySide
                            + " funding=" + fundingRate + " reason=" + rejection);
        }
        return TrendSignal.of(carrySide, signal.barTime(), signal.closePrice(), signal.emaFast(),
                signal.emaSlow(), signal.breakoutHigh(), signal.breakoutLow(), signal.atr(), signal.atrPct(),
                signal.adx(), "funding carry " + carrySide + " promoted from flat signal funding=" + fundingRate
                        + "; " + carryConfirmationSummary(carrySide, signal, marketBreadth)
                        + "; base=" + signal.reason());
    }


    private TrendSignal entrySignalWithEnsembleContext(String contractCode, TrendSignal signal,
                                                       List<HtxSwapKline> entryCandles,
                                                       MarketBreadthContext marketBreadth) {
        if (!properties.isEnsembleEntryEnabled() || signal == null || signal.side() != TrendSide.FLAT) {
            return signal;
        }
        HtxTrendEnsembleSignalCalculator.TrendCandidateSignal candidate = ensembleCalculator.evaluate(entryCandles,
                ensembleProperties, properties.getPeriod(), Instant.now().getEpochSecond());
        TrendSide side = trendSide(candidate.direction());
        String rejection = ensembleEntryRejectionReason(candidate, side, marketBreadth);
        if (rejection != null) {
            return TrendSignal.of(TrendSide.FLAT, signal.barTime(), signal.closePrice(), signal.emaFast(),
                    signal.emaSlow(), signal.breakoutHigh(), signal.breakoutLow(), signal.atr(), signal.atrPct(),
                    signal.adx(), signal.reason() + "; ensemble entry rejected: " + rejection);
        }
        return TrendSignal.of(side, candidate.barTime(), candidate.closePrice(), candidate.emaFast(),
                candidate.emaSlow(), candidate.breakoutHigh(), candidate.breakoutLow(), candidate.atr(),
                candidate.atrPct(), candidate.adx(), "ensemble entry " + side
                        + " score=" + candidate.signalScore().setScale(2, RoundingMode.HALF_UP)
                        + " gap=" + candidate.scoreGap().setScale(2, RoundingMode.HALF_UP)
                        + " regime=" + candidate.regime()
                        + "; " + candidate.entryReason()
                        + "; base=" + signal.reason());
    }

    private String ensembleEntryRejectionReason(HtxTrendEnsembleSignalCalculator.TrendCandidateSignal candidate,
                                                TrendSide side, MarketBreadthContext marketBreadth) {
        if (candidate == null || side == TrendSide.FLAT) {
            return candidate == null ? "candidate unavailable" : candidate.invalidationReason();
        }
        if (!positive(candidate.closePrice()) || !positive(candidate.atr()) || !positive(candidate.atrPct())) {
            return "candidate price or volatility unavailable";
        }
        if (candidate.signalScore().compareTo(nonNegative(properties.getEnsembleEntryMinScore())) < 0) {
            return "score below live threshold " + properties.getEnsembleEntryMinScore();
        }
        if (candidate.scoreGap().compareTo(nonNegative(properties.getEnsembleEntryMinScoreGap())) < 0) {
            return "score gap below live threshold " + properties.getEnsembleEntryMinScoreGap();
        }
        if (candidate.adx() == null || candidate.adx().compareTo(nonNegative(properties.getEnsembleEntryMinAdx())) < 0) {
            return "ADX below live ensemble threshold " + properties.getEnsembleEntryMinAdx();
        }
        if (candidate.atrPct().compareTo(nonNegative(properties.getEnsembleEntryMinAtrPct())) < 0) {
            return "ATR below live ensemble threshold " + properties.getEnsembleEntryMinAtrPct();
        }
        if (properties.isEnsembleEntryRequireTrendingRegime()
                && !ensembleRegimeAligned(candidate.regime(), side)) {
            return "regime not aligned for live entry " + candidate.regime();
        }
        if (!properties.isEnsembleEntryAllowAgainstBroadMarket() && againstBroadMarket(side, marketBreadth)) {
            return "against broad market " + marketBreadth.summary();
        }
        if (properties.isEnsembleEntryRequireBreadthAlignment() && !breadthAligned(side, marketBreadth)) {
            return "broad market is not aligned " + (marketBreadth == null ? "UNKNOWN" : marketBreadth.summary());
        }
        return null;
    }

    private static boolean ensembleRegimeAligned(HtxTrendEnsembleSignalCalculator.Regime regime, TrendSide side) {
        return (side == TrendSide.LONG && regime == HtxTrendEnsembleSignalCalculator.Regime.TRENDING_UP)
                || (side == TrendSide.SHORT && regime == HtxTrendEnsembleSignalCalculator.Regime.TRENDING_DOWN);
    }

    private static TrendSide trendSide(HtxTrendEnsembleSignalCalculator.Direction direction) {
        if (direction == HtxTrendEnsembleSignalCalculator.Direction.LONG) {
            return TrendSide.LONG;
        }
        if (direction == HtxTrendEnsembleSignalCalculator.Direction.SHORT) {
            return TrendSide.SHORT;
        }
        return TrendSide.FLAT;
    }

    private TrendSignal entrySignalWithMarketAnchorContext(TrendSignal signal, MarketAnchorContext marketAnchor) {
        if (!properties.isMarketAnchorEnabled() || signal == null || signal.side() == TrendSide.FLAT) {
            return signal;
        }
        String suffix;
        if (anchorAligned(signal.side(), marketAnchor)) {
            suffix = "; market anchor aligned " + marketAnchor.summary();
        } else if (anchorAgainst(signal.side(), marketAnchor)) {
            suffix = "; market anchor counter " + marketAnchor.summary();
        } else {
            suffix = "; market anchor mixed " + (marketAnchor == null ? "UNKNOWN" : marketAnchor.summary());
        }
        return TrendSignal.of(signal.side(), signal.barTime(), signal.closePrice(), signal.emaFast(),
                signal.emaSlow(), signal.breakoutHigh(), signal.breakoutLow(), signal.atr(), signal.atrPct(),
                signal.adx(), signal.reason() + suffix);
    }

    private String carryEntryRejectionReason(TrendSignal signal, TrendSide carrySide, MarketBreadthContext marketBreadth) {
        if (weakFlatReason(signal.reason())) {
            return "base signal is weak trend: " + signal.reason();
        }
        if (!positive(signal.atrPct()) || signal.atrPct().compareTo(nonNegative(properties.getFundingCarryMinAtrPct())) < 0) {
            return "ATR below carry threshold";
        }
        BigDecimal requiredAdx = nonNegative(properties.getFundingCarryMinAdx()).max(nonNegative(properties.getMinAdx()));
        if (signal.adx() == null || signal.adx().compareTo(requiredAdx) < 0) {
            return "ADX below strict carry threshold " + requiredAdx;
        }
        if (!properties.isFundingCarryAllowAgainstBroadMarket() && againstBroadMarket(carrySide, marketBreadth)) {
            return "against broad market " + marketBreadth.summary();
        }
        if (properties.isFundingCarryRequireConfirmation()
                && (!emaAligned(signal, carrySide) || !breadthAligned(carrySide, marketBreadth))) {
            return "carry requires both EMA and broad-market confirmation";
        }
        return null;
    }

    private HtxTrendCombinationService.MarketEntryRank marketEntryRank(MarketBreadthContext marketBreadth,
                                                                         MarketAnchorContext marketAnchor) {
        boolean bothUp = marketBreadth != null && marketAnchor != null
                && marketBreadth.regime() == MarketRegime.BROAD_UP && marketAnchor.regime() == MarketRegime.BROAD_UP;
        boolean bothDown = marketBreadth != null && marketAnchor != null
                && marketBreadth.regime() == MarketRegime.BROAD_DOWN && marketAnchor.regime() == MarketRegime.BROAD_DOWN;
        String side = bothUp ? TrendSide.LONG.name() : bothDown ? TrendSide.SHORT.name() : TrendSide.FLAT.name();
        return new HtxTrendCombinationService.MarketEntryRank(
                side.equals(TrendSide.FLAT.name()) ? "MIXED" : (bothUp ? "BROAD_UP" : "BROAD_DOWN"),
                marketBreadth == null ? "MIXED" : marketBreadth.regime().name(),
                marketAnchor == null ? "MIXED" : marketAnchor.regime().name(), side,
                new BigDecimal("8"), new BigDecimal("12"), new BigDecimal("2"));
    }

    private BigDecimal entryCandidateScore(TrendSignal signal, MarketBreadthContext marketBreadth,
                                           MarketAnchorContext marketAnchor) {
        BigDecimal score = nz(signal.adx()).add(nonNegative(signal.atrPct()).multiply(new BigDecimal("10000")));
        String reason = signal.reason() == null ? "" : signal.reason().toLowerCase(Locale.ROOT);
        if (reason.contains("broke donchian")) {
            score = score.add(new BigDecimal("30"));
        }
        if (reason.contains("fibonacci")) {
            score = score.add(new BigDecimal("20"));
        }
        if (reason.contains("funding carry")) {
            score = score.subtract(new BigDecimal("15"));
        }
        if (reason.contains("ensemble entry")) {
            score = score.add(new BigDecimal("25"));
        }
        if (breadthAligned(signal.side(), marketBreadth)) {
            score = score.add(new BigDecimal("10"));
        }
        if (anchorAligned(signal.side(), marketAnchor)) {
            score = score.add(nonNegative(properties.getMarketAnchorScoreBonus()));
        } else if (anchorAgainst(signal.side(), marketAnchor)) {
            score = score.subtract(nonNegative(properties.getMarketAnchorCounterScorePenalty()));
        }
        return score;
    }

    private String directionConfidenceGate(TrendSignal signal, DirectionConfidence confidence) {
        if (!properties.isDirectionConfidenceGateEnabled() || signal == null || signal.side() == TrendSide.FLAT
                || confidence == null) {
            return null;
        }
        if (confidence.score().compareTo(confidence.requiredScore()) >= 0) {
            return null;
        }
        return "direction confidence too low side=" + signal.side()
                + " score=" + confidence.score().setScale(2, RoundingMode.HALF_UP)
                + " required=" + confidence.requiredScore().setScale(2, RoundingMode.HALF_UP)
                + " detail=" + confidence.reason();
    }

    private DirectionConfidence directionConfidence(TrendSignal signal, List<HtxSwapKline> entryCandles,
                                                    MarketBreadthContext marketBreadth, MarketAnchorContext marketAnchor) {
        BigDecimal score = BigDecimal.ZERO;
        StringBuilder reason = new StringBuilder();
        BigDecimal minAdx = nonNegative(properties.getMinAdx());
        if (signal.adx() != null && signal.adx().compareTo(minAdx) >= 0) {
            BigDecimal excessAdx = signal.adx().subtract(minAdx).max(BigDecimal.ZERO).min(new BigDecimal("15"));
            score = score.add(new BigDecimal("25")).add(excessAdx);
            reason.append("adx+");
        } else {
            score = score.subtract(new BigDecimal("20"));
            reason.append("adx-");
        }
        BigDecimal minAtrPct = nonNegative(properties.getMinAtrPct());
        if (positive(signal.atrPct()) && signal.atrPct().compareTo(minAtrPct) >= 0) {
            score = score.add(new BigDecimal("10"));
            if (minAtrPct.signum() > 0 && signal.atrPct().compareTo(minAtrPct.multiply(TWO)) >= 0) {
                score = score.add(new BigDecimal("5"));
            }
            reason.append(" atr+");
        } else {
            score = score.subtract(new BigDecimal("15"));
            reason.append(" atr-");
        }
        if (emaAligned(signal, signal.side())) {
            score = score.add(new BigDecimal("15"));
            reason.append(" ema+");
        } else {
            score = score.subtract(new BigDecimal("20"));
            reason.append(" ema-");
        }
        String normalizedReason = signal.reason() == null ? "" : signal.reason().toLowerCase(Locale.ROOT);
        if (normalizedReason.contains("broke donchian")) {
            score = score.add(new BigDecimal("20"));
            reason.append(" donchian+");
        } else if (normalizedReason.contains("fibonacci")) {
            score = score.add(new BigDecimal("10"));
            reason.append(" fibonacci+");
        } else if (normalizedReason.contains("funding carry")) {
            score = score.add(new BigDecimal("5"));
            reason.append(" carry+");
        } else if (normalizedReason.contains("ensemble entry")) {
            score = score.add(new BigDecimal("20"));
            reason.append(" ensemble+");
        }
        if (localMomentumAligned(signal.side(), entryCandles)) {
            score = score.add(new BigDecimal("10"));
            reason.append(" localMomentum+");
        } else {
            score = score.subtract(new BigDecimal("10"));
            reason.append(" localMomentum-");
        }
        if (breadthAligned(signal.side(), marketBreadth)) {
            score = score.add(new BigDecimal("20"));
            reason.append(" breadth+");
        } else if (againstBroadMarket(signal.side(), marketBreadth)) {
            score = score.subtract(new BigDecimal("40"));
            reason.append(" breadthCounter-");
        } else {
            score = score.subtract(new BigDecimal("5"));
            reason.append(" breadthMixed-");
        }
        if (anchorAligned(signal.side(), marketAnchor)) {
            score = score.add(nonNegative(properties.getMarketAnchorConfidenceBonus()));
            reason.append(" marketAnchor+");
        } else if (anchorAgainst(signal.side(), marketAnchor)) {
            score = score.subtract(nonNegative(properties.getMarketAnchorCounterConfidencePenalty()));
            reason.append(" marketAnchorCounter-");
        } else {
            reason.append(" marketAnchorMixed");
        }
        BigDecimal required = nonNegative(properties.getMinDirectionConfidenceScore());
        if (marketBreadth == null || marketBreadth.regime() == MarketRegime.MIXED) {
            required = required.max(nonNegative(properties.getMinMixedDirectionConfidenceScore()));
        }
        if (againstBroadMarket(signal.side(), marketBreadth)) {
            required = required.max(nonNegative(properties.getMinCounterBreadthDirectionConfidenceScore()));
        }
        if (normalizedReason.contains("fibonacci")) {
            required = required.max(nonNegative(properties.getFibonacciMinDirectionConfidenceScore()));
        }
        if (normalizedReason.contains("funding carry")) {
            required = required.max(nonNegative(properties.getFundingCarryMinDirectionConfidenceScore()));
        }
        return new DirectionConfidence(score, required, reason.toString().trim());
    }

    private static boolean localMomentumAligned(TrendSide side, List<HtxSwapKline> entryCandles) {
        if (entryCandles == null || entryCandles.size() < 2) {
            return false;
        }
        HtxSwapKline latest = entryCandles.get(entryCandles.size() - 1);
        HtxSwapKline previous = entryCandles.get(entryCandles.size() - 2);
        if (latest == null || previous == null || latest.getClose() == null || previous.getClose() == null) {
            return false;
        }
        if (side == TrendSide.LONG) {
            return latest.getClose().compareTo(previous.getClose()) > 0;
        }
        if (side == TrendSide.SHORT) {
            return latest.getClose().compareTo(previous.getClose()) < 0;
        }
        return false;
    }

    private static RunResult select(RunResult current, RunResult candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || rank(candidate.code()) > rank(current.code())) {
            return candidate;
        }
        return current;
    }

    private String carryConfirmationSummary(TrendSide side, TrendSignal signal, MarketBreadthContext marketBreadth) {
        return "emaAligned=" + emaAligned(signal, side)
                + " breadthAligned=" + breadthAligned(side, marketBreadth)
                + " market=" + (marketBreadth == null ? "UNKNOWN" : marketBreadth.summary());
    }

    private static boolean emaAligned(TrendSignal signal, TrendSide side) {
        if (signal == null || signal.emaFast() == null || signal.emaSlow() == null) {
            return false;
        }
        if (side == TrendSide.LONG) {
            return signal.emaFast().compareTo(signal.emaSlow()) > 0;
        }
        if (side == TrendSide.SHORT) {
            return signal.emaFast().compareTo(signal.emaSlow()) < 0;
        }
        return false;
    }

    private static boolean breadthAligned(TrendSide side, MarketBreadthContext marketBreadth) {
        if (marketBreadth == null) {
            return false;
        }
        return (side == TrendSide.LONG && marketBreadth.regime() == MarketRegime.BROAD_UP)
                || (side == TrendSide.SHORT && marketBreadth.regime() == MarketRegime.BROAD_DOWN);
    }

    private static boolean againstBroadMarket(TrendSide side, MarketBreadthContext marketBreadth) {
        if (marketBreadth == null) {
            return false;
        }
        return (side == TrendSide.LONG && marketBreadth.regime() == MarketRegime.BROAD_DOWN)
                || (side == TrendSide.SHORT && marketBreadth.regime() == MarketRegime.BROAD_UP);
    }

    private static boolean anchorAligned(TrendSide side, MarketAnchorContext marketAnchor) {
        if (marketAnchor == null) {
            return false;
        }
        return (side == TrendSide.LONG && marketAnchor.regime() == MarketRegime.BROAD_UP)
                || (side == TrendSide.SHORT && marketAnchor.regime() == MarketRegime.BROAD_DOWN);
    }

    private static boolean anchorAgainst(TrendSide side, MarketAnchorContext marketAnchor) {
        if (marketAnchor == null) {
            return false;
        }
        return (side == TrendSide.LONG && marketAnchor.regime() == MarketRegime.BROAD_DOWN)
                || (side == TrendSide.SHORT && marketAnchor.regime() == MarketRegime.BROAD_UP);
    }

    private static boolean weakFlatReason(String reason) {
        if (reason == null) {
            return true;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("adx below threshold")
                || normalized.contains("atr below threshold")
                || normalized.contains("kline unavailable")
                || normalized.contains("not enough");
    }

    private RunResult openIfEligible(String contractCode, TrendSignal signal, List<HtxSwapKline> entryCandles,
                                     HtxSwapContractInfo contract, MarketBreadthContext marketBreadth,
                                     MarketAnchorContext marketAnchor,
                                     CombinationContext combination) {
        DirectionalQualityContext directionalQuality = directionalQualityContext(signal, combination);
        recordCombinationDirectionalQualityEvidence(contractCode, signal, combination, directionalQuality);
        recordCombinationCostNotEvaluated(contractCode, signal, combination);
        HtxTrendCounterMarketEntryGate.Decision counterMarketDecision = combination == null
                ? HtxTrendCounterMarketEntryGate.Decision.notApplicable()
                : counterMarketEntryDecision(signal, combination, marketBreadth);
        CandidateTrendState candidateTrendState = combination == null ? CandidateTrendState.legacy()
                : candidateTrendState(signal, combination, marketBreadth, counterMarketDecision.qualified());
        recordEntryAssessment(contractCode, signal, combination, marketBreadth, candidateTrendState, counterMarketDecision);
        if (signal.side() == TrendSide.FLAT) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.NO_SIGNAL, null, signal.reason()));
        }
        if (properties.isObserveOnly()) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.OBSERVED, null, gateMessage(MODE_GATE, "observe-only signal=" + signal.side()
                    + "; live entry gates bypassed reason=" + signal.reason())));
        }
        String fundingCostGate = fundingCostGate(contractCode, signal.side(), properties.getMaxEntryFundingCostRate());
        if (fundingCostGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, fundingCostGate)));
        }
        String freshnessGate = entrySignalFreshnessGate(signal);
        if (freshnessGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, freshnessGate)));
        }
        String cooldownGate = cooldownGate(contractCode, signal, marketBreadth);
        if (cooldownGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, cooldownGate)));
        }
        DirectionConfidence confidence = null;
        if (combination != null) {
            if (counterMarketDecision.blocksEntry()) {
                return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null,
                        gateMessage(ENTRY_GATE, "counter-market candidate rejected: " + counterMarketDecision.reason())));
            }
        } else {
            confidence = directionConfidence(signal, entryCandles, marketBreadth, marketAnchor);
            String directionGate = directionConfidenceGate(signal, confidence);
            if (directionGate != null) {
                return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, directionGate)));
            }
        }
        if (properties.isCandidateTrendStateGateEnabled() && combination != null && !candidateTrendState.tradable()) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(ENTRY_GATE, "candidate trend state=" + candidateTrendState.regime() + "; "
                            + candidateTrendState.reason())));
        }
        if (combination != null && properties.isMixedMarketRequiresStrongCandidate()
                && marketBreadth != null && marketBreadth.regime() == MarketRegime.MIXED
                && !mixedMarketCandidateEligible(candidateTrendState, combination)) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(ENTRY_GATE, "mixed market requires A-grade candidate; state="
                            + candidateTrendState.regime() + "; " + mixedMarketCandidateReason(combination))));
        }
        String fibonacciExecutionQualityGate = fibonacciExecutionQualityGate(combination);
        if (fibonacciExecutionQualityGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(ENTRY_GATE, fibonacciExecutionQualityGate)));
        }
        String structureLifecycleGate = structureLifecycleAvailabilityGate(contractCode, combination);
        if (structureLifecycleGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(ENTRY_GATE, structureLifecycleGate)));
        }
        HtxDepth depth = depthSnapshot(contractCode);
        TopOfBook book = topOfBook(depth);
        if (book == null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, "depth unavailable")));
        }
        BigDecimal spreadRate = book.ask().subtract(book.bid()).divide(mid(book), 18, RoundingMode.HALF_UP);
        if (spreadRate.compareTo(properties.getMaxEntrySpreadRate()) > 0) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, "spread too wide: " + spreadRate)));
        }
        String openOrderGate = openOrderGate(contractCode);
        if (openOrderGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, openOrderGate)));
        }
        String externalPositionGate = externalPositionGate(contractCode);
        if (externalPositionGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, externalPositionGate)));
        }
        BigDecimal entryPrice = signal.side() == TrendSide.LONG ? book.ask() : book.bid();
        String chaseGate = entryChaseGate(signal, entryPrice);
        if (chaseGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, chaseGate)));
        }
        SizingContext sizing = sizingContext(signal.side(), marketBreadth, confidence, combination != null, candidateTrendState);
        long volume = combination == null ? targetVolume(entryPrice, signal.atr(), contract.getContractSize(), sizing)
                : targetVolumeForNotional(entryPrice, contract.getContractSize(),
                combination.plannedNotionalUsdt().multiply(sizing.multiplier()));
        if (volume <= 0) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(ENTRY_GATE, "target volume below minimum " + sizing.summary())));
        }
        String liquidityGate = liquidityGate(signal, entryCandles, depth, volume);
        HtxSwapFeeRateResolver.FeeRates feeRates = feeRateResolver.rates(contractCode);
        BigDecimal takerFeeRate = feeRates.taker();
        String expectedMoveGate = expectedMoveGate(signal, entryPrice, spreadRate, takerFeeRate);
        EntryCostContext entryCost = entryCostContext(signal, entryPrice, spreadRate, contract.getContractSize(),
                volume, takerFeeRate, confidence);
        recordCombinationEntryCostEvidence(contractCode, signal, contract, combination, depth, book, volume,
                feeRates, entryCost, liquidityGate, expectedMoveGate);
        if (liquidityGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, liquidityGate)));
        }
        if (expectedMoveGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, expectedMoveGate)));
        }
        BigDecimal notional = BigDecimal.valueOf(volume).multiply(contract.getContractSize()).multiply(entryPrice);
        String marginGate = marginGate(contractCode, notional);
        if (marginGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(EXECUTION_PREFLIGHT, marginGate)));
        }
        String leverageGate = switchLeverage(contractCode, signal.side());
        if (leverageGate != null) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null, gateMessage(EXECUTION_PREFLIGHT, leverageGate)));
        }
        String side = signal.side() == TrendSide.LONG ? LONG : SHORT;
        HtxTrendTimeframeProfile timeframeProfile = HtxTrendTimeframeProfile.fromPeriod(properties.getPeriod());
        if (timeframeProfile == HtxTrendTimeframeProfile.SWING_4H && !properties.isSwingEntryEnabled()) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(ENTRY_GATE, "SWING_4H entry disabled pending replay and paper validation")));
        }
        BigDecimal stopPrice = combination == null ? initialStop(signal, side, contract, timeframeProfile)
                : scalePrice(combination.invalidationPrice(), contract);
        BigDecimal takeProfitPrice = initialTakeProfit(signal, side, contract, timeframeProfile);
        recordCombinationPricePlan(contractCode, signal, contract, combination, entryPrice, stopPrice, book,
                volume, feeRates, entryCost);
        HtxTrendStructureLifecycleService.Decision structureClaim = claimStructureLifecycle(contractCode, combination);
        if (!structureClaim.allowed()) {
            return executionResult(contractCode, combination, RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(ENTRY_GATE, structureClaim.reason())));
        }
        String positionKey = positionKey(contractCode);
        String clientOrderId = Long.toString(numericClientOrderId());
        HtxTrendPositionEntity entity = new HtxTrendPositionEntity();
        entity.setPositionKey(positionKey);
        entity.setRunId(combination == null ? null : combination.runId());
        entity.setContractCode(contractCode);
        entity.setStatus(HtxTrendRecorder.INTENT);
        entity.setSide(side);
        entity.setContractSize(contract.getContractSize());
        entity.setContractVolume(volume);
        entity.setLeverage(properties.getLeverage());
        entity.setEntryClientOrderId(clientOrderId);
        entity.setEntryFilledContracts(BigDecimal.ZERO);
        entity.setEntryFee(BigDecimal.ZERO);
        entity.setCloseFilledContracts(BigDecimal.ZERO);
        entity.setCloseFee(BigDecimal.ZERO);
        entity.setStopPrice(stopPrice);
        entity.setTrailingStopPrice(stopPrice);
        entity.setTakeProfitPrice(takeProfitPrice);
        entity.setHighestPrice(signal.closePrice());
        entity.setLowestPrice(signal.closePrice());
        entity.setEntrySignal(clip(signal.reason() + "; " + sizing.summary()));
        entity.setMessage(clip("trend entry intent signal=" + signal.side() + " " + sizing.summary()));
        entity.setAccountLabel("primary");
        entity.setStrategyFamily("TREND");
        entity.setStrategyName("HTX_TREND_V1");
        entity.setTimeframeProfile(timeframeProfile.name());
        if (combination != null) {
            entity.setPrimaryModel(combination.primaryModel());
            entity.setModelName(combination.primaryModel());
            entity.setConfirmationModels(combination.confirmationModels());
            entity.setPolicyVersion(combination.policyVersion());
            entity.setMarketRegime(combination.marketRegime());
            entity.setFactorSnapshotId(combination.factorSnapshotId());
            entity.setEntryHypothesis(combination.hypothesis());
            entity.setInvalidationPrice(combination.invalidationPrice());
            entity.setTargetZoneLow(combination.targetZoneLow());
            entity.setTargetZoneHigh(combination.targetZoneHigh());
            entity.setExpectedRMultiple(combination.expectedRMultiple());
            entity.setMomentumQualityScore(combination.momentumQualityScore());
        }
        entity.setEntryFeeRate(takerFeeRate);
        entity.setEntryFeeProfileId(feeRates.profileId());
        entity.setEstimatedEntryFee(entryCost.estimatedEntryFee());
        entity.setEstimatedCloseFee(entryCost.estimatedCloseFee());
        entity.setExpectedNetAfterFees(entryCost.expectedNetAfterFees());
        entity.setSizingMultiplier(sizing.multiplier());
        entity.setSizingReason(clip(sizing.reason()));
        entity.setEntryGateReason(clip(entryCost.entryGateReason(), 1024));
        long id = recorder.createIntent(entity);
        entity.setId(id);
        attachStructureIntent(contractCode, combination, id);
        HtxSwapOrderRequest request = orderRequest(contractCode, signal.side(), false, clientOrderId, volume);
        recorder.createOrderIntent(orderIntent(entity, HtxTrendRecorder.LEG_ENTRY, signal.side().name(), request,
                "entry signal: " + signal.reason() + "; " + sizing.summary()));
        HtxRestResponse<HtxSwapOrderResult> response = swap.placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        Long orderId = accepted == null ? null : accepted.getOrderId();
        if (response == null || !response.ok() || orderId == null) {
            String rejectionReason = gateMessage(ORDER_EXECUTION, "entry rejected: " + error(response));
            recorder.markOrderRejected(id, HtxTrendRecorder.LEG_ENTRY, clientOrderId, rejectionReason);
            recorder.markStatus(id, HtxTrendRecorder.REJECTED, rejectionReason);
            abandonStructureIntent(id, rejectionReason);
            return executionResult(contractCode, combination, RunResult.of(RunCode.REJECTED, id, rejectionReason));
        }
        recorder.markOrderAccepted(id, HtxTrendRecorder.LEG_ENTRY, clientOrderId, orderId,
                accepted.getClientOrderId(), "entry accepted by exchange");
        recorder.markEntryAccepted(id, orderId, "entry sent side=" + side + " volume=" + volume);
        log.info("[HTX-TREND] entry sent id={} contract={} side={} orderId={} volume={} close={} atr={} adx={} feeRate={} reason={} sizing={}",
                id, contractCode, side, orderId, volume, signal.closePrice(), signal.atr(), signal.adx(),
                takerFeeRate, signal.reason(), sizing.summary());
        return executionResult(contractCode, combination, RunResult.of(RunCode.ENTRY_SENT, id, "entry order sent " + sizing.summary()));
    }

    /**
     * A Fibonacci retest is allowed to be a pullback candle, so this does not require a directional close.
     * It does require normal relative participation and rejects wick-only or one-bar spike entries.
     */
    private String fibonacciExecutionQualityGate(CombinationContext combination) {
        if (combination == null || !HtxTrendStructureLifecycleService.FIB_PULLBACK_CONTINUATION
                .equals(combination.primaryModel()) || !properties.isFibonacciExecutionQualityGateEnabled()) {
            return null;
        }
        HtxTrendCombinationCalculator.CombinationCandidate candidate = combination.selection().candidate();
        if (candidate.fibonacciEvidence() == null || candidate.fibonacciEvidence().breakoutBarTime() == null) {
            return "Fibonacci structure evidence unavailable";
        }
        if (candidate.fibonacciEvidence().emaReclaimConfirmed() == false) {
            return "Fibonacci retest has not reclaimed the EMA structure";
        }
        // The ensemble signal owns relative-volume calculation; it is carried in the combination selection snapshot.
        // It is intentionally read from the candidate's factor snapshot rather than using the old absolute turnover gate.
        BigDecimal volumeRatio = combination.signalVolumeRatio();
        if (volumeRatio == null || volumeRatio.compareTo(nonNegative(properties.getFibonacciMinVolumeRatio())) < 0) {
            return "Fibonacci relative volume below threshold ratio=" + volumeRatio + " required="
                    + properties.getFibonacciMinVolumeRatio();
        }
        HtxTrendCombinationCalculator.EntryCandleQuality quality = candidate.entryCandleQuality();
        if (quality == null || quality.bodyToRange() == null || quality.rangeToAtr() == null
                || quality.upperWickToRange() == null || quality.lowerWickToRange() == null) {
            return "Fibonacci retest microstructure unavailable";
        }
        if (quality.bodyToRange().compareTo(nonNegative(properties.getFibonacciMinBodyToRange())) < 0) {
            return "Fibonacci retest wick-only bodyToRange=" + quality.bodyToRange();
        }
        if (quality.rangeToAtr().compareTo(nonNegative(properties.getFibonacciMaxEntryRangeAtr())) > 0) {
            return "Fibonacci retest one-bar spike rangeToAtr=" + quality.rangeToAtr();
        }
        BigDecimal counterWick = candidate.direction() == HtxTrendEnsembleSignalCalculator.Direction.LONG
                ? quality.lowerWickToRange() : quality.upperWickToRange();
        if (counterWick.compareTo(nonNegative(properties.getFibonacciMaxCounterWickToRange())) > 0) {
            return "Fibonacci retest counter-wick too large ratio=" + counterWick;
        }
        return null;
    }

    private String structureLifecycleAvailabilityGate(String contractCode, CombinationContext combination) {
        if (combination == null || structureLifecycleService == null || combination.structureKey(contractCode) == null) {
            return null;
        }
        HtxTrendStructureLifecycleService.Decision decision = structureLifecycleService.availability(combination.structureKey(contractCode));
        return decision.allowed() ? null : decision.reason();
    }

    private HtxTrendStructureLifecycleService.Decision claimStructureLifecycle(String contractCode,
                                                                                 CombinationContext combination) {
        if (combination == null || structureLifecycleService == null || combination.structureKey(contractCode) == null) {
            return HtxTrendStructureLifecycleService.Decision.notApplicable();
        }
        return structureLifecycleService.claim(new HtxTrendStructureLifecycleService.Claim(combination.structureKey(contractCode),
                contractCode, properties.getPeriod(), combination.selection().candidate().direction().name(),
                combination.primaryModel(), combination.selection().candidate().fibonacciEvidence().breakoutBarTime(),
                combination.runId()));
    }

    private void attachStructureIntent(String contractCode, CombinationContext combination, long positionId) {
        if (combination != null && structureLifecycleService != null) {
            String structureKey = combination.structureKey(contractCode);
            if (structureKey != null) {
                structureLifecycleService.attachIntent(structureKey, combination.runId(), positionId);
            }
        }
    }

    private void abandonStructureIntent(long positionId, String reason) {
        if (structureLifecycleService != null) {
            structureLifecycleService.markEntryAbandoned(positionId, reason);
        }
    }

    private RunResult executionResult(String contractCode, CombinationContext combination, RunResult result) {
        if (result == null) return null;
        String stage = executionStage(result.message());
        entryGateOutcomeCounts.computeIfAbsent(stage + ":" + result.code(), ignored -> new AtomicLong()).incrementAndGet();
        if (combination == null) {
            log.info("[HTX-TREND-GATE] contract={} assessmentStage=ENTRY_OUTCOME outcome={} gateStage={} reason={}",
                    contractCode, result.code(), stage, clip(result.message()));
            return result;
        }
        String state = switch (result.code()) {
            case ENTRY_SENT -> "ORDER_SUBMITTED";
            case REJECTED -> "ORDER_REJECTED";
            case OBSERVED -> "OBSERVED";
            default -> "REJECTED";
        };
        if (combinationService != null) {
            combinationService.recordExecutionGate(combination.runId(), contractCode, state, result.message());
        }
        recordEntryOutcomeAssessment(contractCode, combination, result, stage);
        log.info("[HTX-TREND-GATE] runId={} contract={} model={} timeframeProfile=INTRADAY_15M assessmentStage=ENTRY_OUTCOME "
                        + "outcome={} gateStage={} reason={}", combination.runId(), contractCode, combination.primaryModel(),
                result.code(), stage, clip(result.message()));
        return result;
    }

    private void recordEntryOutcomeAssessment(String contractCode, CombinationContext combination, RunResult result, String stage) {
        String reason = result.message() == null ? "" : result.message();
        HtxTrendAssessmentEntity outcome = new HtxTrendAssessmentEntity();
        outcome.setAssessmentKey("ENTRY_OUTCOME:" + combination.runId() + ":" + contractCode + ":" + result.code()
                + ":" + Integer.toUnsignedString(reason.hashCode()));
        outcome.setAssessmentStage("ENTRY_OUTCOME");
        outcome.setPositionId(result.positionId()); outcome.setRunId(combination.runId()); outcome.setContractCode(contractCode);
        outcome.setAccountLabel("primary");
        outcome.setSide(combination.selection().candidate().direction().name()); outcome.setPrimaryModel(combination.primaryModel());
        outcome.setTimeframeProfile("INTRADAY_15M"); outcome.setTrendState(result.code().name());
        outcome.setTransitionReason(stage + ": " + reason);
        outcome.setExpectedRMultiple(combination.expectedRMultiple());
        outcome.setEvidenceJson("{\"schema\":\"trend-entry-outcome-v1\",\"runId\":\""
                + combination.runId() + "\",\"gateStage\":\"" + stage + "\",\"outcome\":\""
                + result.code() + "\"}");
        recorder.recordAssessment(outcome);
    }

    private static String executionStage(String message) {
        if (message == null) return "UNKNOWN";
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains(EXECUTION_PREFLIGHT)) return "EXECUTION_PREFLIGHT";
        if (normalized.contains(ENTRY_GATE)) return "ENTRY_GATE";
        if (normalized.contains(ORDER_EXECUTION)) return "ORDER_EXECUTION";
        if (normalized.contains(MODE_GATE)) return "MODE_GATE";
        if (normalized.contains(CANDIDATE_SCAN)) return "CANDIDATE_SCAN";
        return "EXECUTION";
    }

    /**
     * Computes a read-only, post-fee outcome slice for the exact side/model/regime of the candidate.
     * It deliberately does not participate in the current execution gate, direction confidence, or sizing path.
     */
    private DirectionalQualityContext directionalQualityContext(TrendSignal signal, CombinationContext combination) {
        if (signal == null || combination == null || signal.side() == TrendSide.FLAT) {
            return DirectionalQualityContext.unavailable("candidate has no directional combination model");
        }
        int lookbackHours = Math.max(1, properties.getQualityLookbackHours());
        int lookbackTrades = Math.max(1, properties.getQualityLookbackTrades());
        HtxTrendPerformanceStats stats = recorder.trendPerformanceForBucket(
                LocalDateTime.now().minusHours(lookbackHours), lookbackTrades, signal.side().name(),
                combination.primaryModel(), combination.marketRegime(), "primary");
        if (stats == null) {
            return DirectionalQualityContext.unavailable("performance unavailable");
        }
        long closed = nonNegativeLong(stats.getClosedCount());
        long wins = nonNegativeLong(stats.getWinCount());
        long losses = nonNegativeLong(stats.getLossCount());
        BigDecimal grossProfit = nonNegative(stats.getGrossProfit());
        BigDecimal grossLoss = nonNegative(stats.getGrossLoss());
        BigDecimal netProfit = nz(stats.getNetProfit());
        BigDecimal averageWin = average(grossProfit, wins);
        BigDecimal averageLoss = average(grossLoss, losses);
        BigDecimal payoffRatio = payoffRatio(averageWin, averageLoss);
        BigDecimal expectancy = closed > 0L ? netProfit.divide(BigDecimal.valueOf(closed), 18, RoundingMode.HALF_UP)
                : nz(stats.getExpectancy());
        BigDecimal profitFactor = profitFactor(grossProfit, grossLoss);
        if (closed < Math.max(1, properties.getQualityMinClosedTrades())) {
            return new DirectionalQualityContext(DirectionalQualityRegime.INSUFFICIENT_SAMPLE, closed, wins, losses,
                    profitFactor, payoffRatio, averageWin, averageLoss, expectancy, netProfit,
                    "insufficient closed trades=" + closed);
        }
        BigDecimal minPayoff = nonNegative(properties.getQualityMinPayoffRatio());
        boolean payoffTooSmall = losses > 0L && wins > 0L && payoffRatio.compareTo(minPayoff) < 0;
        if (wins <= losses || profitFactor.compareTo(nonNegative(properties.getQualityMinProfitFactor())) < 0
                || payoffTooSmall || expectancy.compareTo(nz(properties.getQualityMinExpectancyUsdt())) < 0) {
            return new DirectionalQualityContext(DirectionalQualityRegime.WEAK, closed, wins, losses, profitFactor,
                    payoffRatio, averageWin, averageLoss, expectancy, netProfit, "weak quality");
        }
        if (wins > losses && profitFactor.compareTo(nonNegative(properties.getQualityStrongProfitFactor())) >= 0
                && (losses == 0L || payoffRatio.compareTo(nonNegative(properties.getQualityStrongPayoffRatio())) >= 0)
                && expectancy.signum() > 0) {
            return new DirectionalQualityContext(DirectionalQualityRegime.STRONG, closed, wins, losses, profitFactor,
                    payoffRatio, averageWin, averageLoss, expectancy, netProfit, "strong quality");
        }
        return new DirectionalQualityContext(DirectionalQualityRegime.NEUTRAL, closed, wins, losses, profitFactor,
                payoffRatio, averageWin, averageLoss, expectancy, netProfit, "normal quality");
    }

    /** Writes immutable candidate evidence only; callers must not use it as an entry rejection or sizing signal. */
    private void recordCombinationDirectionalQualityEvidence(String contractCode, TrendSignal signal,
                                                             CombinationContext combination,
                                                             DirectionalQualityContext quality) {
        if (combinationService == null || combination == null || signal == null || signal.barTime() == null
                || quality == null) {
            return;
        }
        combinationService.recordDirectionalQualityEvidence(contractCode, properties.getPeriod(), signal.barTime(),
                combination.policyVersion(), combination.marketRegime(),
                new HtxTrendCombinationService.DirectionalQualityEvidence(combination.runId(), "primary",
                        signal.side().name(), combination.primaryModel(), combination.marketRegime(),
                        quality.regime().name(), quality.reason(), quality.closedCount(), quality.winCount(),
                        quality.lossCount(), quality.profitFactor(), quality.payoffRatio(), quality.averageWin(),
                        quality.averageLoss(), quality.expectancy(), quality.netProfit()));
    }

    /** Every model-passed combination receives a cost-evidence row, even if an earlier gate prevents market-data preflight. */
    private void recordCombinationCostNotEvaluated(String contractCode, TrendSignal signal, CombinationContext combination) {
        if (combinationService == null || combination == null || signal == null || signal.barTime() == null) {
            return;
        }
        combinationService.recordEntryCostEvidence(contractCode, properties.getPeriod(), signal.barTime(),
                combination.policyVersion(), combination.marketRegime(),
                new HtxTrendCombinationService.EntryCostEvidence(combination.runId(), "primary", "NOT_EVALUATED",
                        null, null, null, null, null, null, 0, null, null, null, signal.side().name(), null, 0L,
                        null, null, null, null, null, null, null, null, "NOT_EVALUATED",
                        "cost preflight awaiting depth, fee and sizing inputs"));
    }

    private RunResult advance(HtxTrendPositionEntity position, TrendSignal signal, HtxSwapContractInfo contract,
                              List<HtxSwapKline> entryCandles) {
        if (HtxTrendRecorder.ENTRY_SENT.equals(position.getStatus())) {
            return reconcileEntry(position);
        }
        if (HtxTrendRecorder.ACTIVE.equals(position.getStatus())) {
            return monitorActive(position, signal, contract, entryCandles);
        }
        if (HtxTrendRecorder.PARTIAL_CLOSE_SENT.equals(position.getStatus())) {
            return reconcilePartialClose(position);
        }
        if (HtxTrendRecorder.CLOSE_SENT.equals(position.getStatus())) {
            return reconcileClose(position);
        }
        if (HtxTrendRecorder.UNKNOWN.equals(position.getStatus())) {
            return reconcileUnknownPosition(position);
        }
        return RunResult.of(RunCode.WAITING, position.getId(), "position status=" + position.getStatus());
    }

    private RunResult reconcileEntry(HtxTrendPositionEntity position) {
        QueryOrderResult query = queryOrder(position.getContractCode(), position.getEntryOrderId(),
                position.getEntryClientOrderId());
        if (!query.found()) {
            return RunResult.of(RunCode.WAITING, position.getId(), query.message());
        }
        HtxSwapOrder order = query.order();
        if (filled(order)) {
            BigDecimal avg = firstPositive(order.getTradeAveragePrice(), position.getEntryAvgPrice());
            BigDecimal highLow = avg == null ? BigDecimal.ZERO : avg;
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_ENTRY,
                    position.getEntryClientOrderId(), HtxTrendRecorder.ORDER_FILLED, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()), avg, nz(order.getFee()),
                    order.getFeeCurrency(), order.getRealProfit(), "entry filled state=" + exchangeState(order));
            position.setEntryAvgPrice(avg);
            position.setEntryFilledContracts(nz(order.getTradeVolume()));
            position.setEntryFee(nz(order.getFee()));
            recorder.markEntryFilled(position.getId(), nz(order.getTradeVolume()), avg, nz(order.getFee()),
                    highLow, highLow, "entry filled state=" + exchangeState(order));
            HtxTrendMirrorExecutor.SyncResult mirror = syncMirrorEntry(position);
            if (!mirror.ok()) {
                log.warn("[HTX-TREND-MIRROR] entry sync failed id={} contract={} side={} message={}",
                        position.getId(), position.getContractCode(), position.getSide(), mirror.message());
                return RunResult.of(RunCode.ACTIVE, position.getId(),
                        "entry filled; mirror warning: " + mirror.message());
            }
            return RunResult.of(RunCode.ACTIVE, position.getId(), "entry filled; " + mirror.message());
        }
        if (terminal(order)) {
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_ENTRY,
                    position.getEntryClientOrderId(), HtxTrendRecorder.ORDER_TERMINAL, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()),
                    order.getTradeAveragePrice(), nz(order.getFee()), order.getFeeCurrency(), order.getRealProfit(),
                    "entry terminal without fill state=" + exchangeState(order));
            recorder.markStatus(position.getId(), HtxTrendRecorder.REJECTED,
                    "entry terminal without fill state=" + exchangeState(order));
            return RunResult.of(RunCode.REJECTED, position.getId(), "entry terminal without fill");
        }
        return RunResult.of(RunCode.WAITING, position.getId(), "waiting entry fill");
    }

    private RunResult monitorActive(HtxTrendPositionEntity position, TrendSignal signal, HtxSwapContractInfo contract,
                                    List<HtxSwapKline> entryCandles) {
        ExchangePositionLookup positionLookup = exchangePosition(position.getContractCode(), position.getSide());
        if (!positionLookup.available()) {
            return RunResult.of(RunCode.WAITING, position.getId(), "position query unavailable: " + positionLookup.reason());
        }
        if (positionLookup.position().isEmpty()) {
            String reviewReason = exitEngineReason(EXIT_HARD_RISK, "managed position missing on exchange");
            recorder.markStatus(position.getId(), HtxTrendRecorder.UNKNOWN,
                    reviewReason + "; manual review");
            return RunResult.of(RunCode.MANUAL_REVIEW, position.getId(), reviewReason);
        }
        HtxSwapPosition exchangePosition = positionLookup.position().get();
        BigDecimal realtimePrice = realtimePriceFeed == null ? null : realtimePriceFeed.latest(position.getContractCode())
                .map(HtxTrendRealtimePriceFeed.Quote::price).orElse(null);
        BigDecimal reference = firstPositive(realtimePrice, exchangePosition.getMarkPrice(), exchangePosition.getLastPrice(),
                signal.closePrice(), position.getEntryAvgPrice());
        BigDecimal highest = max(firstPositive(position.getHighestPrice(), reference), reference);
        BigDecimal lowest = min(firstPositive(position.getLowestPrice(), reference), reference);
        StopContext stopContext = stopContext(position, signal, reference, highest, lowest, contract, entryCandles);
        BigDecimal trailingStop = stopContext.effectiveStop();
        // Fibonacci positions have one exit owner.  v4 already derives its protection from the actual two-leg
        // cost and MFE state, so the old fee-protection clamp must not silently tighten its decision boundary.
        boolean fibV4Owned = properties.isExitEngineEnabled()
                && HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION.equals(position.getPrimaryModel());
        BigDecimal feeProtectedStop = fibV4Owned ? null : feeProtectedStop(position, highest, lowest, contract);
        if (feeProtectedStop != null) {
            trailingStop = LONG.equals(position.getSide())
                    ? max(trailingStop, feeProtectedStop) : min(trailingStop, feeProtectedStop);
            if (trailingStop.compareTo(stopContext.effectiveStop()) != 0) {
                stopContext = stopContext.withEffectiveStop(trailingStop, "LEGACY_FEE_PROTECTED_CLAMP");
            }
        }
        BigDecimal takeProfit = position.getTakeProfitPrice();
        ExitSnapshot exitSnapshot = exitSnapshot(position, signal, reference, highest, lowest, trailingStop,
                takeProfit, stopContext);
        recorder.updateRiskLevels(position.getId(), position.getStopPrice(), trailingStop, takeProfit, highest, lowest,
                clip("holding signal=" + signal.side() + " ref=" + reference + " " + stopContext.summary()
                        + feeProtectionMessage(feeProtectedStop) + " " + exitSnapshot.summary()),
                exitSnapshot.state(), exitSnapshot.currentNet(), exitSnapshot.mfe(), exitSnapshot.mae(),
                exitSnapshot.giveback(), exitSnapshot.bars(), exitSnapshot.candidates());
        HtxTrendProfitProtectionPlanner.Plan profitProtectionPlan = observeProfitProtection(position, reference,
                highest, lowest, signal);
        BigDecimal engineProtectionPrice = exitEngineProtectionPrice(position, trailingStop);
        if (profitProtectionPlan != null && profitProtectionPlan.armed()) {
            engineProtectionPrice = tighterExitProtection(position.getSide(), engineProtectionPrice,
                    profitProtectionPlan.protectionPrice());
        }
        HoldingAssessment holdingAssessment = holdingAssessment(position, signal);
        HtxTrendExitDecisionEngine.Decision engineDecision = exitEngineDecision(position, signal, reference, highest, lowest,
                engineProtectionPrice, exitSnapshot, holdingAssessment);
        recordHoldingAssessment(position, signal, reference, engineProtectionPrice, exitSnapshot, engineDecision, holdingAssessment);
        recordPositionMarkSnapshot(position, signal, reference, engineProtectionPrice, exitSnapshot, engineDecision,
                holdingAssessment);
        if (engineDecision != null) {
            if (!exitEngineLiveFor(position)) {
                persistExitEngineState(position, engineDecision);
                recordExitEngineDecision(position, reference, engineProtectionPrice, exitSnapshot, engineDecision, profitProtectionPlan,
                        "OBSERVE", exitEngineBlockReason(position));
                // A model-routed position has a single exit owner even during observation. Allowing the legacy
                // FLAT/ATR branch to submit an order here would make the recorded decision differ from execution.
                return RunResult.of(RunCode.ACTIVE, position.getId(), clip("exit-engine observing ref=" + reference
                        + " action=" + engineDecision.action() + " phase=" + engineDecision.phase()
                        + " reason=" + engineDecision.reason()));
            } else {
                persistExitEngineState(position, engineDecision);
                if (engineDecision.action() == HtxTrendExitDecisionEngine.Action.FULL_CLOSE) {
                    RunResult result = closePosition(position, exitEngineReason(exitEngineCategory(engineDecision),
                            "model-routed " + engineDecision.reason()));
                    recordExitEngineDecision(position, reference, engineProtectionPrice, exitSnapshot, engineDecision, profitProtectionPlan,
                            result.code() == RunCode.CLOSE_SENT ? engineDecision.action().name() : "NOT_EXECUTED",
                            result.code() == RunCode.CLOSE_SENT ? null : result.message());
                    return result;
                }
                if (engineDecision.action() == HtxTrendExitDecisionEngine.Action.PARTIAL_CLOSE) {
                    PartialExitDecision partial = dynamicPartialExitDecision(position, engineDecision);
                    if (partial.triggered()) {
                        RunResult result = partialClosePosition(position, partial, clip("model-routed " + engineDecision.reason()));
                        recordExitEngineDecision(position, reference, engineProtectionPrice, exitSnapshot, engineDecision, profitProtectionPlan,
                                result.code() == RunCode.PARTIAL_CLOSE_SENT ? engineDecision.action().name() : "NOT_EXECUTED",
                                result.code() == RunCode.PARTIAL_CLOSE_SENT ? null : result.message());
                        return result;
                    }
                    recordExitEngineDecision(position, reference, engineProtectionPrice, exitSnapshot, engineDecision, profitProtectionPlan,
                            "NOT_EXECUTED", "target remaining exposure cannot form a valid reduce-only order");
                } else {
                    recordExitEngineDecision(position, reference, engineProtectionPrice, exitSnapshot, engineDecision, profitProtectionPlan,
                            engineDecision.action().name(), null);
                }
                // A live model-routed Fibonacci decision owns the whole exit choice. Falling through to the
                // legacy FLAT/ATR path would make the recorded action differ from the executed one.
                return RunResult.of(RunCode.ACTIVE, position.getId(), clip("exit-engine holding ref=" + reference
                        + " action=" + engineDecision.action() + " phase=" + engineDecision.phase()
                        + " reason=" + engineDecision.reason()));
            }
        }
        String exitReason = combinationInvalidationExitReason(position, reference);
        if (exitReason == null) {
            exitReason = flatAfterFeeProtectionExitReason(position, signal, reference, feeProtectedStop);
        }
        if (exitReason == null) {
            exitReason = flatLossControlExitReason(position, signal, reference);
        }
        if (exitReason != null) {
            return closePosition(position, clip(exitReason + " " + exitSnapshot.summary()));
        }
        PartialExitDecision partialExit = partialExitDecision(position, reference, exitSnapshot.currentNet());
        if (partialExit.triggered() && properties.isPartialTakeProfitLiveEnabled()) {
            return partialClosePosition(position, partialExit, clip(exitSnapshot.summary()));
        }
        exitReason = exitReason(position, signal, reference, trailingStop, takeProfit, stopContext.stopSource());
        if (exitReason == null) {
            exitReason = fundingCostExitReason(position.getContractCode(), position.getSide());
        }
        if (exitReason == null) {
            return RunResult.of(RunCode.ACTIVE, position.getId(), clip("holding ref=" + reference
                    + " signal=" + signal.side() + " trailingStop=" + trailingStop
                    + " partial=" + partialExit.summary() + " " + stopContext.summary() + " " + exitSnapshot.summary()));
        }
        return closePosition(position, clip(exitReason + " " + exitSnapshot.summary()));
    }

    private static String combinationInvalidationExitReason(HtxTrendPositionEntity position, BigDecimal reference) {
        if (position.getPrimaryModel() == null || !positive(position.getInvalidationPrice()) || !positive(reference)) {
            return null;
        }
        boolean invalidated = LONG.equals(position.getSide())
                ? reference.compareTo(position.getInvalidationPrice()) <= 0
                : reference.compareTo(position.getInvalidationPrice()) >= 0;
        return invalidated ? exitEngineReason(EXIT_MODEL_INVALIDATION,
                "combination invalidation " + position.getPrimaryModel()
                        + " ref=" + reference + " invalidation=" + position.getInvalidationPrice()) : null;
    }

    private RunResult closePosition(HtxTrendPositionEntity position, String exitReason) {
        if (properties.isObserveOnly()) {
            return RunResult.of(RunCode.ACTIVE, position.getId(), "observe-only close signal: " + exitReason);
        }
        String clientOrderId = Long.toString(numericClientOrderId());
        TrendSide side = LONG.equals(position.getSide()) ? TrendSide.LONG : TrendSide.SHORT;
        HtxSwapOrderRequest request = orderRequest(position.getContractCode(), side, true, clientOrderId,
                remainingContractVolume(position));
        recorder.createOrderIntent(orderIntent(position, HtxTrendRecorder.LEG_CLOSE, position.getSide(), request,
                "exit signal: " + exitReason));
        HtxRestResponse<HtxSwapOrderResult> response = swap.placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        Long orderId = accepted == null ? null : accepted.getOrderId();
        if (response == null || !response.ok() || orderId == null) {
            String rejectionReason = gateMessage(ORDER_EXECUTION, "close rejected: " + error(response));
            recorder.markOrderRejected(position.getId(), HtxTrendRecorder.LEG_CLOSE, clientOrderId,
                    rejectionReason);
            return RunResult.of(RunCode.WAITING, position.getId(), rejectionReason);
        }
        recorder.markOrderAccepted(position.getId(), HtxTrendRecorder.LEG_CLOSE, clientOrderId, orderId,
                accepted.getClientOrderId(), "close accepted by exchange");
        recorder.markCloseAccepted(position.getId(), exitReason, clientOrderId, orderId,
                "close sent reason=" + exitReason);
        log.info("[HTX-TREND] close sent id={} contract={} side={} orderId={} volume={} reason={}",
                position.getId(), position.getContractCode(), position.getSide(), orderId,
                remainingContractVolume(position), exitReason);
        return RunResult.of(RunCode.CLOSE_SENT, position.getId(), exitReason);
    }

    private RunResult partialClosePosition(HtxTrendPositionEntity position, PartialExitDecision decision,
                                            String exitSnapshotSummary) {
        long remaining = remainingContractVolume(position);
        if (properties.isObserveOnly()) {
            return RunResult.of(RunCode.ACTIVE, position.getId(), "observe-only partial close signal: " + decision.summary());
        }
        long requestedVolume = partialCloseVolume(remaining, decision.rate());
        if (requestedVolume <= 0L || requestedVolume >= remaining) {
            return RunResult.of(RunCode.ACTIVE, position.getId(), "partial close skipped: " + decision.summary()
                    + " remaining=" + remaining);
        }
        String clientOrderId = Long.toString(numericClientOrderId());
        TrendSide side = LONG.equals(position.getSide()) ? TrendSide.LONG : TrendSide.SHORT;
        HtxSwapOrderRequest request = orderRequest(position.getContractCode(), side, true, clientOrderId, requestedVolume);
        String reason = "partial exit stage=" + decision.stage() + " " + decision.reason();
        recorder.createOrderIntent(orderIntent(position, HtxTrendRecorder.LEG_PARTIAL_CLOSE, position.getSide(), request,
                reason));
        HtxRestResponse<HtxSwapOrderResult> response = swap.placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        Long orderId = accepted == null ? null : accepted.getOrderId();
        if (response == null || !response.ok() || orderId == null) {
            String rejectionReason = gateMessage(ORDER_EXECUTION, "partial close rejected: " + error(response));
            recorder.markOrderRejected(position.getId(), HtxTrendRecorder.LEG_PARTIAL_CLOSE, clientOrderId, rejectionReason);
            return RunResult.of(RunCode.WAITING, position.getId(), rejectionReason);
        }
        recorder.markOrderAccepted(position.getId(), HtxTrendRecorder.LEG_PARTIAL_CLOSE, clientOrderId, orderId,
                accepted.getClientOrderId(), "partial close accepted by exchange");
        recorder.markPartialCloseAccepted(position.getId(), clientOrderId, orderId,
                "partial close sent stage=" + decision.stage() + " reason=" + decision.reason());
        log.info("[HTX-TREND] partial close sent id={} contract={} side={} orderId={} volume={} stage={} reason={}",
                position.getId(), position.getContractCode(), position.getSide(), orderId, requestedVolume,
                decision.stage(), decision.reason());
        return RunResult.of(RunCode.PARTIAL_CLOSE_SENT, position.getId(), reason + " " + exitSnapshotSummary);
    }

    private RunResult reconcilePartialClose(HtxTrendPositionEntity position) {
        QueryOrderResult query = queryOrder(position.getContractCode(), position.getPartialCloseOrderId(),
                position.getPartialCloseClientOrderId());
        if (!query.found()) {
            return RunResult.of(RunCode.WAITING, position.getId(), query.message());
        }
        HtxSwapOrder order = query.order();
        if (hasTradeFill(order) && terminal(order)) {
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_PARTIAL_CLOSE,
                    position.getPartialCloseClientOrderId(), fillStatus(order), order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()), order.getTradeAveragePrice(),
                    nz(order.getFee()), order.getFeeCurrency(), order.getRealProfit(),
                    "partial close executed state=" + exchangeState(order));
            int stage = nextPartialExitStage(position);
            HtxTrendMirrorExecutor.SyncResult mirror = syncMirrorPartialClose(position, nz(order.getTradeVolume()));
            if (!mirror.ok() && mirrorExecutor != null && mirrorExecutor.blockPrimaryCloseUntilSynced()) {
                return RunResult.of(RunCode.WAITING, position.getId(), "primary partial close filled; mirror pending: "
                        + mirror.message());
            }
            recorder.markPartialCloseFilled(position.getId(), stage, position.getPartialCloseClientOrderId(),
                    nz(order.getTradeVolume()), order.getTradeAveragePrice(), nz(order.getFee()), order.getRealProfit(),
                    "partial close executed stage=" + stage + " state=" + exchangeState(order) + "; " + mirror.message());
            return RunResult.of(RunCode.ACTIVE, position.getId(), "partial close executed stage=" + stage + "; " + mirror.message());
        }
        if (terminal(order)) {
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_PARTIAL_CLOSE,
                    position.getPartialCloseClientOrderId(), HtxTrendRecorder.ORDER_TERMINAL, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()), order.getTradeAveragePrice(),
                    nz(order.getFee()), order.getFeeCurrency(), order.getRealProfit(),
                    "partial close terminal without fill state=" + exchangeState(order));
            recorder.markStatus(position.getId(), HtxTrendRecorder.ACTIVE,
                    "partial close terminal without fill state=" + exchangeState(order));
            return RunResult.of(RunCode.ACTIVE, position.getId(), "partial close terminal without fill");
        }
        return RunResult.of(RunCode.WAITING, position.getId(), "waiting partial close fill");
    }

    private RunResult reconcileClose(HtxTrendPositionEntity position) {
        QueryOrderResult query = queryOrder(position.getContractCode(), position.getCloseOrderId(),
                position.getCloseClientOrderId());
        if (!query.found()) {
            return RunResult.of(RunCode.WAITING, position.getId(), query.message());
        }
        HtxSwapOrder order = query.order();
        if (filled(order)) {
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_CLOSE,
                    position.getCloseClientOrderId(), HtxTrendRecorder.ORDER_FILLED, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()),
                    order.getTradeAveragePrice(), nz(order.getFee()), order.getFeeCurrency(), order.getRealProfit(),
                    "close filled state=" + exchangeState(order));
            HtxTrendMirrorExecutor.SyncResult mirror = syncMirrorClose(position);
            if (!mirror.ok() && mirrorExecutor != null && mirrorExecutor.blockPrimaryCloseUntilSynced()) {
                log.warn("[HTX-TREND-MIRROR] close sync pending id={} contract={} side={} message={}",
                        position.getId(), position.getContractCode(), position.getSide(), mirror.message());
                return RunResult.of(RunCode.WAITING, position.getId(),
                        "primary close filled; mirror pending: " + mirror.message());
            }
            recorder.markCloseFilled(position.getId(), nz(order.getTradeVolume()), order.getTradeAveragePrice(),
                    nz(order.getFee()), order.getRealProfit(), "close filled state=" + exchangeState(order)
                            + "; " + mirror.message());
            return RunResult.of(RunCode.DONE, position.getId(), "position done; " + mirror.message());
        }
        if (hasTradeFill(order) && terminal(order)) {
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_CLOSE,
                    position.getCloseClientOrderId(), HtxTrendRecorder.ORDER_PARTIALLY_FILLED, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()),
                    order.getTradeAveragePrice(), nz(order.getFee()), order.getFeeCurrency(), order.getRealProfit(),
                    "close partially filled state=" + exchangeState(order));
            HtxTrendMirrorExecutor.SyncResult mirror = syncMirrorPartialClose(position, nz(order.getTradeVolume()));
            if (!mirror.ok() && mirrorExecutor != null && mirrorExecutor.blockPrimaryCloseUntilSynced()) {
                return RunResult.of(RunCode.WAITING, position.getId(), "primary close partially filled; mirror pending: "
                        + mirror.message());
            }
            recorder.markClosePartiallyFilled(position.getId(), position.getCloseClientOrderId(),
                    nz(order.getTradeVolume()), order.getTradeAveragePrice(), nz(order.getFee()), order.getRealProfit(),
                    "close partially filled state=" + exchangeState(order) + "; " + mirror.message());
            return RunResult.of(RunCode.ACTIVE, position.getId(), "close partially filled; remaining position active; "
                    + mirror.message());
        }
        if (terminal(order)) {
            recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_CLOSE,
                    position.getCloseClientOrderId(), HtxTrendRecorder.ORDER_TERMINAL, order.getOrderId(),
                    order.getClientOrderId(), exchangeState(order), nz(order.getTradeVolume()),
                    order.getTradeAveragePrice(), nz(order.getFee()), order.getFeeCurrency(), order.getRealProfit(),
                    "close terminal without fill state=" + exchangeState(order));
            recorder.markStatus(position.getId(), HtxTrendRecorder.UNKNOWN,
                    "close terminal without fill state=" + exchangeState(order));
            return RunResult.of(RunCode.MANUAL_REVIEW, position.getId(), "close terminal without fill");
        }
        return RunResult.of(RunCode.WAITING, position.getId(), "waiting close fill");
    }

    /**
     * Repairs the narrow acknowledgement gap where HTX accepted a reduce-only close order but the local
     * position state was not advanced. The order ledger is durable before the exchange call, so it is the
     * authority for resuming reconciliation without issuing another close order.
     */
    private RunResult recoverAcceptedClose(HtxTrendPositionEntity position) {
        Optional<HtxTrendOrderEntity> acceptedClose = recorder.findAcceptedCloseOrder(position.getId(),
                assessmentAccountLabel(position));
        if (acceptedClose.isEmpty()) {
            return null;
        }
        HtxTrendOrderEntity closeOrder = acceptedClose.get();
        if (closeOrder.getExchangeOrderId() == null && isBlank(closeOrder.getClientOrderId())) {
            return RunResult.of(RunCode.MANUAL_REVIEW, position.getId(),
                    "accepted close order missing exchange identifiers");
        }
        recorder.markCloseAccepted(position.getId(), closeOrder.getReason(), closeOrder.getClientOrderId(),
                closeOrder.getExchangeOrderId(), "recovered accepted close order=" + closeOrder.getExchangeOrderId());
        position.setStatus(HtxTrendRecorder.CLOSE_SENT);
        position.setCloseClientOrderId(closeOrder.getClientOrderId());
        position.setCloseOrderId(closeOrder.getExchangeOrderId());
        return reconcileClose(position);
    }


    private RunResult recoverAcceptedPartialClose(HtxTrendPositionEntity position) {
        Optional<HtxTrendOrderEntity> acceptedPartialClose = recorder.findAcceptedPartialCloseOrder(position.getId());
        if (acceptedPartialClose.isEmpty()) {
            return null;
        }
        HtxTrendOrderEntity partialCloseOrder = acceptedPartialClose.get();
        if (partialCloseOrder.getExchangeOrderId() == null && isBlank(partialCloseOrder.getClientOrderId())) {
            return RunResult.of(RunCode.MANUAL_REVIEW, position.getId(),
                    "accepted partial close order missing exchange identifiers");
        }
        recorder.markPartialCloseAccepted(position.getId(), partialCloseOrder.getClientOrderId(),
                partialCloseOrder.getExchangeOrderId(),
                "recovered accepted partial close order=" + partialCloseOrder.getExchangeOrderId());
        position.setStatus(HtxTrendRecorder.PARTIAL_CLOSE_SENT);
        position.setPartialCloseClientOrderId(partialCloseOrder.getClientOrderId());
        position.setPartialCloseOrderId(partialCloseOrder.getExchangeOrderId());
        return reconcilePartialClose(position);
    }

    private HtxTrendMirrorExecutor.SyncResult syncMirrorEntry(HtxTrendPositionEntity position) {
        if (mirrorExecutor == null) {
            return HtxTrendMirrorExecutor.SyncResult.ok("mirror disabled");
        }
        try {
            return mirrorExecutor.syncEntry(position);
        } catch (RuntimeException ex) {
            return HtxTrendMirrorExecutor.SyncResult.failed("mirror entry exception: " + ex.getClass().getSimpleName());
        }
    }

    private HtxTrendMirrorExecutor.SyncResult syncMirrorPartialClose(HtxTrendPositionEntity position,
                                                                      BigDecimal filledContracts) {
        if (mirrorExecutor == null) {
            return HtxTrendMirrorExecutor.SyncResult.ok("mirror disabled");
        }
        try {
            return mirrorExecutor.syncPartialClose(position, filledContracts == null ? 0L
                    : filledContracts.setScale(0, RoundingMode.DOWN).longValue());
        } catch (RuntimeException ex) {
            return HtxTrendMirrorExecutor.SyncResult.failed("mirror partial close exception: "
                    + ex.getClass().getSimpleName());
        }
    }

    private HtxTrendMirrorExecutor.SyncResult syncMirrorClose(HtxTrendPositionEntity position) {
        if (mirrorExecutor == null) {
            return HtxTrendMirrorExecutor.SyncResult.ok("mirror disabled");
        }
        try {
            return mirrorExecutor.syncClose(position);
        } catch (RuntimeException ex) {
            return HtxTrendMirrorExecutor.SyncResult.failed("mirror close exception: " + ex.getClass().getSimpleName());
        }
    }

    private SignalSnapshot loadSignal(String contractCode) {
        HtxRestResponse<List<HtxSwapKline>> entryResponse = swap.historyKline(contractCode,
                properties.getPeriod(), properties.getKlineSize());
        if (entryResponse == null || !entryResponse.ok() || entryResponse.payload() == null) {
            return new SignalSnapshot(TrendSignal.skip("entry kline unavailable: " + error(entryResponse)), List.of(), List.of());
        }
        String trendPeriod = trendPeriod();
        HtxRestResponse<List<HtxSwapKline>> trendResponse = trendPeriod.equalsIgnoreCase(properties.getPeriod())
                ? entryResponse : swap.historyKline(contractCode, trendPeriod, properties.getTrendKlineSize());
        if (trendResponse == null || !trendResponse.ok() || trendResponse.payload() == null) {
            return new SignalSnapshot(TrendSignal.skip("trend kline unavailable: " + error(trendResponse)),
                    entryResponse.payload(), List.of());
        }
        TrendSignal signal = calculator.evaluate(entryResponse.payload(), trendResponse.payload(),
                properties, Instant.now().getEpochSecond());
        return new SignalSnapshot(signal, entryResponse.payload(), trendResponse.payload());
    }

    private void captureSignalWindows(String stage, String contractCode, SignalSnapshot snapshot, String runId,
                                      Long factorSnapshotId, Long positionId) {
        if (snapshot == null) {
            return;
        }
        Long targetBarTime = snapshot.signal() == null ? null : snapshot.signal().barTime();
        captureRuntimeKlines(stage, contractCode, properties.getPeriod(), targetBarTime, properties.getKlineSize(),
                runId, factorSnapshotId, positionId, snapshot.entryCandles());
        String trendPeriod = trendPeriod();
        if (!trendPeriod.equalsIgnoreCase(properties.getPeriod())) {
            captureRuntimeKlines(stage, contractCode, trendPeriod, targetBarTime, properties.getTrendKlineSize(),
                    runId, factorSnapshotId, positionId, snapshot.trendCandles());
        }
    }

    private void captureRuntimeKlines(String stage, String contractCode, String period, Long targetBarTime,
                                      int expectedWindowSize, String runId, Long factorSnapshotId, Long positionId,
                                      List<HtxSwapKline> candles) {
        if (!properties.isRuntimeKlineCaptureEnabled() || runtimeKlineRecorder == null || candles == null || candles.isEmpty()) {
            return;
        }
        long completedBefore = Instant.now().getEpochSecond() - Math.max(0L, properties.getCandleCloseGraceSeconds());
        List<HtxSwapKline> completedCandles = candles.stream()
                .filter(candle -> candle != null && candle.getId() != null)
                .filter(candle -> candle.getId() + periodSeconds(period) <= completedBefore)
                .sorted(Comparator.comparing(HtxSwapKline::getId))
                .toList();
        if (completedCandles.isEmpty()) {
            return;
        }
        Long completedTargetBarTime = completedCandles.get(completedCandles.size() - 1).getId();
        runtimeKlineRecorder.capture(new HtxTrendRuntimeKlineRecorder.CaptureRequest(stage, contractCode, period,
                completedTargetBarTime, expectedWindowSize, runId, factorSnapshotId, positionId, completedCandles));
    }

    private void recordSignal(String contractCode, TrendSignal signal) {
        HtxTrendSignalEntity entity = new HtxTrendSignalEntity();
        entity.setContractCode(contractCode);
        entity.setPeriod(properties.getPeriod());
        entity.setBarTime(signal.barTime());
        entity.setSignal(signal.side().name());
        entity.setClosePrice(signal.closePrice());
        entity.setEmaFast(signal.emaFast());
        entity.setEmaSlow(signal.emaSlow());
        entity.setBreakoutHigh(signal.breakoutHigh());
        entity.setBreakoutLow(signal.breakoutLow());
        entity.setAtr(signal.atr());
        entity.setAtrPct(signal.atrPct());
        entity.setAdx(signal.adx());
        entity.setReason(signal.reason());
        FundingMarket funding = currentFunding(contractCode);
        entity.setFundingRate(funding == null ? null : funding.fundingRate());
        TopOfBook book = topOfBook(contractCode);
        entity.setSpreadRate(book == null ? null : book.ask().subtract(book.bid()).divide(mid(book), 18, RoundingMode.HALF_UP));
        recorder.recordSignal(entity);
    }

    private HtxSwapContractInfo loadContract(String contractCode) {
        HtxRestResponse<List<HtxSwapContractInfo>> response = swap.contractInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null || response.payload().isEmpty()) {
            return null;
        }
        return response.payload().stream()
                .filter(item -> item != null && contractCode.equalsIgnoreCase(item.getContractCode()))
                .findFirst().orElse(response.payload().get(0));
    }

    private TopOfBook topOfBook(String contractCode) {
        return topOfBook(depthSnapshot(contractCode));
    }

    private HtxDepth depthSnapshot(String contractCode) {
        HtxRestResponse<HtxDepth> response = swap.depth(contractCode, contractProperties.getDepthStep());
        return response == null ? null : response.payload();
    }

    private TopOfBook topOfBook(HtxDepth depth) {
        if (depth == null || depth.getBids() == null || depth.getAsks() == null
                || depth.getBids().isEmpty() || depth.getAsks().isEmpty()) {
            return null;
        }
        HtxPriceLevel bid = depth.getBids().get(0);
        HtxPriceLevel ask = depth.getAsks().get(0);
        if (bid == null || ask == null || !positive(bid.getPrice()) || !positive(ask.getPrice())) {
            return null;
        }
        return new TopOfBook(bid.getPrice(), ask.getPrice());
    }

    private String liquidityGate(TrendSignal signal, List<HtxSwapKline> entryCandles, HtxDepth depth, long volume) {
        if (positive(properties.getMaxEntryAtrPct()) && positive(signal.atrPct())
                && signal.atrPct().compareTo(properties.getMaxEntryAtrPct()) > 0) {
            return "ATR too high for liquidity-adjusted entry atrPct=" + signal.atrPct()
                    + " max=" + properties.getMaxEntryAtrPct();
        }
        BigDecimal minTurnover = properties.getMinEntryAvgTurnoverUsdt();
        if (positive(minTurnover)) {
            BigDecimal avgTurnover = averageRecentTurnover(entryCandles, properties.getLiquidityLookbackBars());
            if (avgTurnover == null) {
                return "liquidity turnover unavailable";
            }
            if (avgTurnover.compareTo(minTurnover) < 0) {
                return "liquidity turnover too low avgTurnover=" + avgTurnover + " min=" + minTurnover;
            }
        }
        BigDecimal depthMultiplier = properties.getMinEntryDepthMultiplier();
        if (positive(depthMultiplier)) {
            int depthLevels = Math.max(1, properties.getEntryDepthLevels());
            List<HtxPriceLevel> levels = signal.side() == TrendSide.LONG ? depth.getAsks() : depth.getBids();
            BigDecimal availableContracts = sumTopLevels(levels, depthLevels);
            BigDecimal requiredContracts = BigDecimal.valueOf(volume).multiply(depthMultiplier);
            if (availableContracts.compareTo(requiredContracts) < 0) {
                return "liquidity depth too low side=" + signal.side() + " top" + depthLevels
                        + "Contracts=" + availableContracts + " requiredContracts=" + requiredContracts;
            }
        }
        return null;
    }

    private String openOrderGate(String contractCode) {
        HtxRestResponse<HtxSwapOpenOrders> response = swap.openOrders(contractCode);
        HtxSwapOpenOrders openOrders = response == null ? null : response.payload();
        if (response == null || !response.ok() || openOrders == null || openOrders.getOrders() == null) {
            return "contract open orders query failed: " + error(response);
        }
        if (!openOrders.getOrders().isEmpty()) {
            return "contract has open orders; wait count=" + openOrders.getOrders().size();
        }
        return null;
    }

    private String externalPositionGate(String contractCode) {
        HtxRestResponse<List<HtxSwapPosition>> response = swap.positionInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return "position query failed: " + error(response);
        }
        BigDecimal openVolume = response.payload().stream()
                .filter(item -> item != null && positive(item.getVolume()))
                .map(HtxSwapPosition::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (openVolume.signum() > 0) {
            return "external contract position exists; trend strategy will not take over volume=" + openVolume;
        }
        return null;
    }

    private ExchangePositionLookup exchangePosition(String contractCode, String side) {
        HtxRestResponse<List<HtxSwapPosition>> response = swap.positionInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return ExchangePositionLookup.unavailable(error(response));
        }
        String positionSide = LONG.equals(side) ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        Optional<HtxSwapPosition> position = response.payload().stream()
                .filter(item -> item != null && positionSide.equalsIgnoreCase(item.getPositionSide()))
                .filter(item -> positive(item.getVolume()))
                .findFirst();
        return ExchangePositionLookup.available(position);
    }

    /**
     * UNKNOWN is only resolved after a second successful position snapshot.  Time proximity is not
     * sufficient evidence: only HTX order/client identifiers may supply a close price and realized PnL.
     */
    private RunResult reconcileUnknownPosition(HtxTrendPositionEntity position) {
        ExchangePositionLookup lookup = exchangePosition(position.getContractCode(), position.getSide());
        if (!lookup.available()) {
            return RunResult.of(RunCode.WAITING, position.getId(), "unknown position reconciliation waiting: " + lookup.reason());
        }
        if (lookup.position().isPresent()) {
            recorder.markStatus(position.getId(), HtxTrendRecorder.ACTIVE,
                    "exchange reconciliation restored active position volume=" + lookup.position().get().getVolume());
            return RunResult.of(RunCode.ACTIVE, position.getId(), "exchange reconciliation restored active position");
        }
        Optional<HtxTrendOrderEntity> closeOrder = recorder.findLatestCloseOrder(position.getId(),
                assessmentAccountLabel(position));
        if (closeOrder.isPresent() && !isBlank(closeOrder.get().getClientOrderId())) {
            QueryOrderResult query = queryOrder(position.getContractCode(), closeOrder.get().getExchangeOrderId(),
                    closeOrder.get().getClientOrderId());
            if (query.found() && filled(query.order())) {
                HtxSwapOrder order = query.order();
                recorder.markOrderReconciled(position.getId(), HtxTrendRecorder.LEG_CLOSE, closeOrder.get().getClientOrderId(),
                        HtxTrendRecorder.ORDER_FILLED, order.getOrderId(), order.getClientOrderId(), exchangeState(order),
                        nz(order.getTradeVolume()), order.getTradeAveragePrice(), nz(order.getFee()), order.getFeeCurrency(),
                        order.getRealProfit(), "close fill recovered from HTX by order identifier");
                recorder.markCloseFilled(position.getId(), nz(order.getTradeVolume()), order.getTradeAveragePrice(),
                        nz(order.getFee()), order.getRealProfit(), "close fill recovered from HTX by order identifier");
                return RunResult.of(RunCode.DONE, position.getId(), "reconciled verified HTX close order=" + order.getOrderId());
            }
        }
        String reason = "exchange position absent in two successful snapshots; no HTX close fill linked by order/client identifier; "
                + "external close result unverified and excluded from strategy PnL";
        recorder.markReconciledClosed(position, closeOrder.orElse(null), null, "EXTERNAL_UNVERIFIED", reason);
        return RunResult.of(RunCode.DONE, position.getId(), reason);
    }

    private String switchLeverage(String contractCode, TrendSide side) {
        String positionSide = side == TrendSide.LONG ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        HtxRestResponse<?> response = swap.switchLeverage(contractCode, positionSide, properties.getLeverage());
        if (response == null || !response.ok() || response.getData() == null) {
            return "leverage switch failed: " + error(response);
        }
        return null;
    }

    private String marginGate(String contractCode, BigDecimal notional) {
        HtxRestResponse<List<HtxSwapAccount>> response = swap.accountInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return "contract margin unavailable: " + error(response);
        }
        BigDecimal margin = response.payload().stream()
                .filter(item -> item != null && item.getMarginAvailable() != null)
                .map(HtxSwapAccount::getMarginAvailable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal required = notional.divide(BigDecimal.valueOf(Math.max(1, properties.getLeverage())), 18, RoundingMode.UP)
                .multiply(contractProperties.getMarginSafetyRatio());
        if (required.compareTo(properties.getMinContractMarginAvailable()) < 0) {
            required = properties.getMinContractMarginAvailable();
        }
        if (margin.compareTo(required) < 0) {
            return "contract margin insufficient available=" + margin + " required=" + required;
        }
        return null;
    }

    private String cooldownGate(String contractCode, TrendSignal signal, MarketBreadthContext marketBreadth) {
        if (properties.getCooldownMs() <= 0) {
            return null;
        }
        Optional<HtxTrendPositionEntity> lastDone = recorder.findLastDone(contractCode);
        if (lastDone.isEmpty() || lastDone.get().getUpdatedAt() == null) {
            return null;
        }
        CooldownContext cooldown = cooldownContext(lastDone.get());
        long age = Duration.between(lastDone.get().getUpdatedAt(), LocalDateTime.now()).toMillis();
        if (age < 0) {
            age = 0;
        }
        if (cooldown.type() == CooldownType.PROFIT
                && age >= Math.max(0L, properties.getProfitCooldownStrongReentryAfterMs())
                && strongProfitReentrySignal(signal, marketBreadth)) {
            return null;
        }
        if (age < cooldown.durationMs()) {
            return cooldown.type().label() + " cooldown active remainingMs=" + (cooldown.durationMs() - age)
                    + " net=" + cooldown.netProfit().setScale(4, RoundingMode.HALF_UP)
                    + " lastDoneId=" + lastDone.get().getId();
        }
        return null;
    }

    private CooldownContext cooldownContext(HtxTrendPositionEntity lastDone) {
        BigDecimal netProfit = netRealizedProfit(lastDone);
        if (netProfit.signum() < 0) {
            return new CooldownContext(CooldownType.LOSS, effectiveCooldownMs(properties.getLossCooldownMs()), netProfit);
        }
        if (netProfit.compareTo(nonNegative(properties.getProfitCooldownMinNetProfitUsdt())) >= 0) {
            return new CooldownContext(CooldownType.PROFIT, effectiveCooldownMs(properties.getProfitCooldownMs()), netProfit);
        }
        return new CooldownContext(CooldownType.BREAKEVEN, effectiveCooldownMs(properties.getBreakevenCooldownMs()), netProfit);
    }

    private long effectiveCooldownMs(long configuredMs) {
        if (configuredMs < 0) {
            return Math.max(0L, properties.getCooldownMs());
        }
        return Math.max(0L, configuredMs);
    }

    private BigDecimal netRealizedProfit(HtxTrendPositionEntity position) {
        return nz(position.getRealizedProfit()).subtract(nz(position.getEntryFee())).subtract(nz(position.getCloseFee()));
    }

    private boolean strongProfitReentrySignal(TrendSignal signal, MarketBreadthContext marketBreadth) {
        if (signal == null || signal.side() == TrendSide.FLAT) {
            return false;
        }
        if (!strongBreakoutReason(signal.reason())) {
            return false;
        }
        if (signal.adx() == null || signal.adx().compareTo(nonNegative(properties.getProfitCooldownStrongReentryMinAdx())) < 0) {
            return false;
        }
        if (!positive(signal.atrPct())
                || signal.atrPct().compareTo(nonNegative(properties.getProfitCooldownStrongReentryMinAtrPct())) < 0) {
            return false;
        }
        return breadthAligned(signal.side(), marketBreadth);
    }

    private static boolean strongBreakoutReason(String reason) {
        if (reason == null) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("broke donchian") || normalized.contains("breakout");
    }

    private FundingMarket currentFunding(String contractCode) {
        HtxRestResponse<HtxSwapFundingRate> response = swap.fundingRate(contractCode);
        HtxSwapFundingRate funding = response == null ? null : response.payload();
        if (response == null || !response.ok() || funding == null || funding.getFundingRate() == null) {
            return null;
        }
        return new FundingMarket(funding.getFundingRate());
    }

    private String fundingCostGate(String contractCode, TrendSide side, BigDecimal maxCostRate) {
        if (maxCostRate == null || maxCostRate.signum() < 0) {
            return null;
        }
        FundingMarket funding = currentFunding(contractCode);
        if (funding == null || funding.fundingRate() == null) {
            return "funding unavailable; skip directional entry";
        }
        BigDecimal sideCost = side == TrendSide.LONG ? funding.fundingRate() : funding.fundingRate().negate();
        if (sideCost.compareTo(maxCostRate) > 0) {
            return "funding cost blocked side=" + side + " funding=" + funding.fundingRate()
                    + " sideCost=" + sideCost + " max=" + maxCostRate;
        }
        return null;
    }

    private String fundingCostExitReason(String contractCode, String side) {
        TrendSide trendSide = LONG.equals(side) ? TrendSide.LONG : TrendSide.SHORT;
        String gate = fundingCostGate(contractCode, trendSide, properties.getMaxHoldFundingCostRate());
        return gate == null ? null : exitEngineReason(EXIT_FUNDING_COST, "exit because " + gate);
    }

    private MarketBreadthContext marketBreadthContext() {
        if (!properties.isAdaptiveSizingEnabled()) {
            return MarketBreadthContext.neutral("adaptive sizing disabled");
        }
        int lookbackMinutes = Math.max(1, properties.getMarketBreadthLookbackMinutes());
        HtxTrendBreadthStats stats = recorder.latestBreadthStats(LocalDateTime.now().minusMinutes(lookbackMinutes));
        if (stats == null) {
            return MarketBreadthContext.neutral("breadth unavailable");
        }
        long total = nonNegativeLong(stats.getTotalCount());
        long minSymbols = Math.max(1, properties.getMarketBreadthMinSymbols());
        if (total < minSymbols) {
            return new MarketBreadthContext(MarketRegime.MIXED, total, nonNegativeLong(stats.getLongContextCount()),
                    nonNegativeLong(stats.getShortContextCount()), BigDecimal.ZERO, BigDecimal.ZERO,
                    "insufficient breadth symbols=" + total + " min=" + minSymbols);
        }
        long longCount = nonNegativeLong(stats.getLongContextCount());
        long shortCount = nonNegativeLong(stats.getShortContextCount());
        BigDecimal longRatio = ratio(longCount, total);
        BigDecimal shortRatio = ratio(shortCount, total);
        BigDecimal threshold = nonNegative(properties.getMarketBreadthThreshold());
        BigDecimal oppositeMax = nonNegative(properties.getMarketBreadthOppositeMax());
        if (longRatio.compareTo(threshold) >= 0 && shortRatio.compareTo(oppositeMax) <= 0) {
            return new MarketBreadthContext(MarketRegime.BROAD_UP, total, longCount, shortCount,
                    longRatio, shortRatio, "broad up");
        }
        if (shortRatio.compareTo(threshold) >= 0 && longRatio.compareTo(oppositeMax) <= 0) {
            return new MarketBreadthContext(MarketRegime.BROAD_DOWN, total, longCount, shortCount,
                    longRatio, shortRatio, "broad down");
        }
        return new MarketBreadthContext(MarketRegime.MIXED, total, longCount, shortCount,
                longRatio, shortRatio, "mixed");
    }

    private MarketAnchorContext marketAnchorContext() {
        if (!properties.isMarketAnchorEnabled()) {
            return MarketAnchorContext.neutral("market anchor disabled");
        }
        List<String> contracts = normalizedList(properties.getMarketAnchorContracts());
        List<String> periods = normalizedPeriods(properties.getMarketAnchorPeriods());
        if (contracts.isEmpty() || periods.isEmpty()) {
            return MarketAnchorContext.neutral("market anchor contracts or periods blank");
        }
        int longSignals = 0;
        int shortSignals = 0;
        int validSignals = 0;
        int longSymbols = 0;
        int shortSymbols = 0;
        List<String> parts = new ArrayList<>();
        for (String contractCode : contracts) {
            int symbolLong = 0;
            int symbolShort = 0;
            for (String period : periods) {
                TrendSide side = marketAnchorPeriodSide(contractCode, period);
                if (side == TrendSide.LONG) {
                    longSignals++;
                    symbolLong++;
                    validSignals++;
                } else if (side == TrendSide.SHORT) {
                    shortSignals++;
                    symbolShort++;
                    validSignals++;
                }
            }
            int minPeriods = Math.max(1, properties.getMarketAnchorMinAlignedPeriodsPerSymbol());
            if (symbolLong >= minPeriods && symbolLong > symbolShort) {
                longSymbols++;
            } else if (symbolShort >= minPeriods && symbolShort > symbolLong) {
                shortSymbols++;
            }
            parts.add(contractCode + "(long=" + symbolLong + ",short=" + symbolShort + ")");
        }
        int minSignals = Math.max(1, properties.getMarketAnchorMinAlignedSignals());
        int minSymbols = Math.max(1, properties.getMarketAnchorMinAlignedSymbols());
        int maxOpposite = Math.max(0, properties.getMarketAnchorMaxOppositeSignals());
        if (longSignals >= minSignals && longSymbols >= minSymbols && shortSignals <= maxOpposite) {
            return new MarketAnchorContext(MarketRegime.BROAD_UP, validSignals, longSignals, shortSignals,
                    longSymbols, shortSymbols, String.join(",", parts));
        }
        if (shortSignals >= minSignals && shortSymbols >= minSymbols && longSignals <= maxOpposite) {
            return new MarketAnchorContext(MarketRegime.BROAD_DOWN, validSignals, longSignals, shortSignals,
                    longSymbols, shortSymbols, String.join(",", parts));
        }
        return new MarketAnchorContext(MarketRegime.MIXED, validSignals, longSignals, shortSignals,
                longSymbols, shortSymbols, String.join(",", parts));
    }

    private TrendSide marketAnchorPeriodSide(String contractCode, String period) {
        HtxRestResponse<List<HtxSwapKline>> response = swap.historyKline(contractCode, period, properties.getMarketAnchorKlineSize());
        if (response == null || !response.ok() || response.payload() == null) {
            return TrendSide.FLAT;
        }
        List<HtxSwapKline> candles = completedCandles(response.payload(), period);
        Long targetBarTime = candles.isEmpty() ? null : candles.get(candles.size() - 1).getId();
        captureRuntimeKlines("MARKET_ANCHOR", contractCode, period, targetBarTime,
                properties.getMarketAnchorKlineSize(), null, null, null, response.payload());
        int slowPeriod = Math.max(2, properties.getMarketAnchorSlowEmaPeriod());
        if (candles.size() < slowPeriod + 2) {
            return TrendSide.FLAT;
        }
        List<BigDecimal> closes = new ArrayList<>(candles.size());
        for (HtxSwapKline candle : candles) {
            if (candle == null || !positive(candle.getClose())) {
                return TrendSide.FLAT;
            }
            closes.add(candle.getClose());
        }
        BigDecimal latest = closes.get(closes.size() - 1);
        BigDecimal previous = closes.get(closes.size() - 2);
        BigDecimal fast = ema(closes, Math.max(1, properties.getMarketAnchorFastEmaPeriod()));
        BigDecimal slow = ema(closes, slowPeriod);
        if (latest.compareTo(previous) > 0 && fast.compareTo(slow) > 0 && latest.compareTo(fast) >= 0) {
            return TrendSide.LONG;
        }
        if (latest.compareTo(previous) < 0 && fast.compareTo(slow) < 0 && latest.compareTo(fast) <= 0) {
            return TrendSide.SHORT;
        }
        return TrendSide.FLAT;
    }

    private List<HtxSwapKline> completedCandles(List<HtxSwapKline> rawCandles, String period) {
        if (rawCandles == null || rawCandles.isEmpty()) {
            return List.of();
        }
        long seconds = periodSeconds(period);
        long now = Instant.now().getEpochSecond();
        long grace = Math.max(0L, properties.getCandleCloseGraceSeconds());
        return rawCandles.stream()
                .filter(item -> item != null && item.getId() != null)
                .filter(item -> item.getId() + seconds + grace <= now)
                .sorted(Comparator.comparing(HtxSwapKline::getId))
                .toList();
    }

    private static BigDecimal ema(List<BigDecimal> values, int period) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal multiplier = TWO.divide(BigDecimal.valueOf(period + 1L), 18, RoundingMode.HALF_UP);
        BigDecimal ema = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            ema = values.get(i).subtract(ema).multiply(multiplier).add(ema);
        }
        return ema;
    }

    private static List<String> normalizedList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(HtxTrendFollowingService::normalize)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private static List<String> normalizedPeriods(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> !isBlank(value))
                .map(String::trim)
                .distinct()
                .toList();
    }

    private PerformanceContext performanceContext() {
        if (!properties.isAdaptiveSizingEnabled()) {
            return PerformanceContext.neutral("adaptive sizing disabled");
        }
        int lookbackHours = Math.max(1, properties.getQualityLookbackHours());
        int lookbackTrades = Math.max(1, properties.getQualityLookbackTrades());
        HtxTrendPerformanceStats stats = recorder.trendPerformanceSince(LocalDateTime.now().minusHours(lookbackHours),
                lookbackTrades);
        if (stats == null) {
            return PerformanceContext.neutral("performance unavailable");
        }
        long closed = nonNegativeLong(stats.getClosedCount());
        long wins = nonNegativeLong(stats.getWinCount());
        long losses = nonNegativeLong(stats.getLossCount());
        BigDecimal grossProfit = nonNegative(stats.getGrossProfit());
        BigDecimal grossLoss = nonNegative(stats.getGrossLoss());
        BigDecimal netProfit = nz(stats.getNetProfit());
        BigDecimal averageWin = average(grossProfit, wins);
        BigDecimal averageLoss = average(grossLoss, losses);
        BigDecimal payoffRatio = payoffRatio(averageWin, averageLoss);
        BigDecimal expectancy = closed > 0L ? netProfit.divide(BigDecimal.valueOf(closed), 18, RoundingMode.HALF_UP)
                : nz(stats.getExpectancy());
        BigDecimal profitFactor = profitFactor(grossProfit, grossLoss);
        if (closed < Math.max(1, properties.getQualityMinClosedTrades())) {
            return new PerformanceContext(QualityRegime.NEUTRAL, closed, wins, losses, profitFactor, payoffRatio,
                    averageWin, averageLoss, expectancy, netProfit, "insufficient closed trades=" + closed);
        }
        BigDecimal minPayoff = nonNegative(properties.getQualityMinPayoffRatio());
        boolean payoffTooSmall = losses > 0L && wins > 0L && payoffRatio.compareTo(minPayoff) < 0;
        if (wins <= losses || profitFactor.compareTo(nonNegative(properties.getQualityMinProfitFactor())) < 0
                || payoffTooSmall || expectancy.compareTo(nz(properties.getQualityMinExpectancyUsdt())) < 0) {
            return new PerformanceContext(QualityRegime.WEAK, closed, wins, losses, profitFactor, payoffRatio,
                    averageWin, averageLoss, expectancy, netProfit, "weak quality");
        }
        if (wins > losses && profitFactor.compareTo(nonNegative(properties.getQualityStrongProfitFactor())) >= 0
                && (losses == 0L || payoffRatio.compareTo(nonNegative(properties.getQualityStrongPayoffRatio())) >= 0)
                && expectancy.signum() > 0) {
            return new PerformanceContext(QualityRegime.STRONG, closed, wins, losses, profitFactor, payoffRatio,
                    averageWin, averageLoss, expectancy, netProfit, "strong quality");
        }
        return new PerformanceContext(QualityRegime.NEUTRAL, closed, wins, losses, profitFactor, payoffRatio,
                averageWin, averageLoss, expectancy, netProfit, "normal quality");
    }

    private SizingContext sizingContext(TrendSide side, MarketBreadthContext marketBreadth,
                                        DirectionConfidence confidence, boolean combinationEntry,
                                        CandidateTrendState candidateTrendState) {
        BigDecimal multiplier = BigDecimal.ONE;
        StringBuilder reason = new StringBuilder();
        if (candidateTrendState != null && candidateTrendState.strong()) {
            multiplier = multiplier.multiply(nonNegative(properties.getStrongQualityPositionMultiplier()));
            reason.append("candidateTrend=STRONG(score=")
                    .append(candidateTrendState.score().setScale(2, RoundingMode.HALF_UP)).append(") ");
        } else {
            reason.append("candidateTrend=")
                    .append(candidateTrendState == null ? "UNAVAILABLE" : candidateTrendState.regime()).append(" ");
        }
        if (marketBreadth != null && side == TrendSide.LONG && marketBreadth.regime() == MarketRegime.BROAD_UP) {
            multiplier = multiplier.multiply(nonNegative(properties.getMarketBreadthAlignedPositionMultiplier()));
            reason.append("breadth=BROAD_UP_ALIGNED ");
        } else if (marketBreadth != null && side == TrendSide.SHORT && marketBreadth.regime() == MarketRegime.BROAD_DOWN) {
            multiplier = multiplier.multiply(nonNegative(properties.getMarketBreadthAlignedPositionMultiplier()));
            reason.append("breadth=BROAD_DOWN_ALIGNED ");
        } else if (marketBreadth != null && side == TrendSide.SHORT && marketBreadth.regime() == MarketRegime.BROAD_UP) {
            multiplier = multiplier.multiply(nonNegative(properties.getMarketBreadthCounterPositionMultiplier()));
            reason.append("breadth=BROAD_UP_COUNTER ");
        } else if (marketBreadth != null && side == TrendSide.LONG && marketBreadth.regime() == MarketRegime.BROAD_DOWN) {
            multiplier = multiplier.multiply(nonNegative(properties.getMarketBreadthCounterPositionMultiplier()));
            reason.append("breadth=BROAD_DOWN_COUNTER ");
        } else {
            reason.append("breadth=MIXED ");
            if (combinationEntry) {
                multiplier = multiplier.multiply(nonNegative(properties.getCombinationMixedMarketPositionMultiplier()));
                reason.append("combinationMixedMarket=")
                        .append(properties.getCombinationMixedMarketPositionMultiplier()).append(" ");
            }
        }
        if (properties.isDirectionConfidenceGateEnabled() && properties.isConfidenceSizingEnabled()
                && confidence != null) {
            BigDecimal score = confidence.score();
            if (score.compareTo(nonNegative(properties.getStrongConfluenceSizingScore())) >= 0
                    && breadthAligned(side, marketBreadth) && candidateTrendState != null && candidateTrendState.strong()) {
                multiplier = multiplier.multiply(nonNegative(properties.getStrongConfluencePositionMultiplier()));
                reason.append("confidence=STRONG_CONFLUENCE(score=")
                        .append(score.setScale(2, RoundingMode.HALF_UP)).append(") ");
            } else if (score.compareTo(nonNegative(properties.getHighConfidenceSizingScore())) >= 0) {
                multiplier = multiplier.multiply(nonNegative(properties.getHighConfidencePositionMultiplier()));
                reason.append("confidence=HIGH(score=")
                        .append(score.setScale(2, RoundingMode.HALF_UP)).append(") ");
            } else {
                reason.append("confidence=NORMAL(score=")
                        .append(score.setScale(2, RoundingMode.HALF_UP)).append(") ");
            }
        }
        multiplier = clamp(multiplier, positive(properties.getMinAdaptivePositionMultiplier())
                        ? properties.getMinAdaptivePositionMultiplier() : BigDecimal.ONE,
                positive(properties.getMaxAdaptivePositionMultiplier())
                        ? properties.getMaxAdaptivePositionMultiplier() : BigDecimal.ONE);
        return new SizingContext(multiplier, reason.toString().trim());
    }

    private CandidateTrendState candidateTrendState(TrendSignal signal, CombinationContext combination,
                                                    MarketBreadthContext marketBreadth, boolean counterMarketQualified) {
        if (combination == null) {
            return CandidateTrendState.legacy();
        }
        HtxTrendStateEvaluator.EntryState state = trendStateEvaluator.evaluateEntry(new HtxTrendStateEvaluator.EntryInput(
                signal.side(), combination.selection().candidate().score(), combination.expectedRMultiple(),
                combination.momentumQualityScore(), signal.emaFast(), signal.emaSlow(),
                marketBreadth == null ? "MIXED" : marketBreadth.regime().name(),
                nonNegative(properties.getCandidateTrendStateMinScore()),
                nonNegative(properties.getCandidateTrendStateStrongScore()),
                nonNegative(properties.getCandidateTrendStateMinExpectedR()),
                nonNegative(properties.getCandidateTrendStateStrongExpectedR()),
                nonNegative(properties.getCandidateTrendStateMinMomentumScore()), counterMarketQualified));
        return new CandidateTrendState(state.regime(), state.tradable(), state.score(), state.rawScore(),
                state.breakdown(), state.reason());
    }

    private boolean mixedMarketCandidateEligible(CandidateTrendState state, CombinationContext combination) {
        if (state == null || !state.tradable() || combination == null) {
            return false;
        }
        return nonNegative(combination.selection().candidate().score())
                .compareTo(nonNegative(properties.getMixedMarketMinModelScore())) >= 0
                && nonNegative(combination.expectedRMultiple())
                .compareTo(nonNegative(properties.getMixedMarketMinExpectedR())) >= 0
                && nonNegative(combination.momentumQualityScore())
                .compareTo(nonNegative(properties.getMixedMarketMinMomentumScore())) >= 0;
    }

    private String mixedMarketCandidateReason(CombinationContext combination) {
        return "modelScore=" + combination.selection().candidate().score() + "/"
                + properties.getMixedMarketMinModelScore() + ", expectedR=" + combination.expectedRMultiple()
                + "/" + properties.getMixedMarketMinExpectedR() + ", momentum="
                + combination.momentumQualityScore() + "/" + properties.getMixedMarketMinMomentumScore();
    }

    private void recordEntryAssessment(String contractCode, TrendSignal signal, CombinationContext combination,
                                       MarketBreadthContext breadth, CandidateTrendState state,
                                       HtxTrendCounterMarketEntryGate.Decision counterMarketDecision) {
        if (combination == null || signal == null || signal.barTime() == null || state == null) return;
        HtxTrendAssessmentEntity assessment = new HtxTrendAssessmentEntity();
        assessment.setAssessmentKey("ENTRY:" + combination.runId() + ":" + contractCode);
        assessment.setAssessmentStage("ENTRY_ASSESSMENT"); assessment.setRunId(combination.runId());
        assessment.setAccountLabel("primary");
        assessment.setContractCode(contractCode); assessment.setSide(signal.side().name());
        assessment.setPrimaryModel(combination.primaryModel()); assessment.setTimeframeProfile("INTRADAY_15M");
        assessment.setBarTime(signal.barTime()); assessment.setTrendState(state.regime());
        assessment.setTransitionReason(state.reason()); assessment.setReferencePrice(signal.closePrice());
        assessment.setExpectedRMultiple(combination.expectedRMultiple());
        HtxTrendStateEvaluator.EntryScoreBreakdown breakdown = state.breakdown();
        assessment.setEvidenceJson("{\"modelScore\":" + combination.selection().candidate().score()
                + ",\"stateScore\":{\"capped\":" + state.score() + ",\"raw\":" + state.rawScore()
                + ",\"components\":{\"expectedR\":" + breakdown.expectedRAdjustment()
                + ",\"momentum\":" + breakdown.momentumAdjustment() + ",\"ema\":" + breakdown.emaAdjustment()
                + ",\"breadth\":" + breakdown.breadthAdjustment() + "}}"
                + ",\"calibratedQuality\":{\"status\":\"PENDING_FROZEN_REPLAY\",\"reason\":\"no completed same-source score-bucket calibration\"}"
                + ",\"breadth\":\"" + (breadth == null ? "MIXED" : breadth.regime()) + "\",\"counterMarket\":{\"applicable\":"
                + counterMarketDecision.counterMarket() + ",\"qualified\":" + counterMarketDecision.qualified()
                + ",\"reason\":\"" + jsonEscape(counterMarketDecision.reason()) + "\"},\"emaFast\":"
                + signal.emaFast() + ",\"emaSlow\":" + signal.emaSlow() + "}");
        recorder.recordAssessment(assessment);
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String assessmentAccountLabel(HtxTrendPositionEntity position) {
        return position == null || position.getAccountLabel() == null || position.getAccountLabel().isBlank()
                ? "primary" : position.getAccountLabel();
    }

    /**
     * Broad-market opposition is not an automatic veto. A counter candidate must prove local continuation
     * structure and is still sized with the existing counter-breadth multiplier.
     */
    private HtxTrendCounterMarketEntryGate.Decision counterMarketEntryDecision(TrendSignal signal,
                                                                                 CombinationContext combination,
                                                                                 MarketBreadthContext marketBreadth) {
        return HtxTrendCounterMarketEntryGate.evaluate(new HtxTrendCounterMarketEntryGate.Input(
                properties.isCounterMarketStrongCandidateEnabled(), signal.side(),
                marketBreadth == null ? "MIXED" : marketBreadth.regime().name(),
                combination.selection().candidate().score(), combination.expectedRMultiple(),
                combination.momentumQualityScore(), signal.adx(), signal.emaFast(), signal.emaSlow(),
                combination.confirmationModels(), nonNegative(properties.getCounterMarketMinModelScore()),
                nonNegative(properties.getCounterMarketMinExpectedR()),
                nonNegative(properties.getCounterMarketMinMomentumScore()),
                nonNegative(properties.getCounterMarketMinAdx())));
    }

    private QueryOrderResult queryOrder(String contractCode, Long orderId, String clientOrderId) {
        HtxRestResponse<List<HtxSwapOrder>> response = orderId != null
                ? swap.queryOrder(contractCode, orderId) : swap.queryOrderByClientOrderId(contractCode, clientOrderId);
        if (response == null || !response.ok() || response.payload() == null || response.payload().isEmpty()) {
            return QueryOrderResult.failed("order query failed: " + error(response));
        }
        return QueryOrderResult.found(response.payload().get(0));
    }

    private HtxSwapOrderRequest orderRequest(String contractCode, TrendSide trendSide, boolean reduceOnly,
                                             String clientOrderId, long volume) {
        boolean longSide = trendSide == TrendSide.LONG;
        String side = reduceOnly ? (longSide ? SIDE_SELL : SIDE_BUY) : (longSide ? SIDE_BUY : SIDE_SELL);
        String positionSide = longSide ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        return HtxUsdtSwapService.orderRequest(contractCode, Long.parseLong(clientOrderId), volume,
                side, positionSide, reduceOnly, null, ORDER_TYPE_MARKET);
    }

    private HtxTrendOrderEntity orderIntent(HtxTrendPositionEntity position, String leg, String trendSide,
                                            HtxSwapOrderRequest request, String reason) {
        HtxTrendOrderEntity entity = new HtxTrendOrderEntity();
        entity.setPositionId(position.getId());
        entity.setPositionKey(position.getPositionKey());
        entity.setRunId(position.getRunId());
        entity.setContractCode(request.getContractCode());
        entity.setLeg(leg);
        entity.setTrendSide(trendSide);
        entity.setOrderSide(request.getSide());
        entity.setPositionSide(request.getPositionSide());
        entity.setReduceOnly(Boolean.TRUE.equals(request.getReduceOnly()));
        entity.setClientOrderId(request.getClientOrderId());
        entity.setRequestVolume(Long.parseLong(request.getVolume()));
        entity.setFilledContracts(BigDecimal.ZERO);
        entity.setFee(BigDecimal.ZERO);
        entity.setStatus(HtxTrendRecorder.ORDER_INTENT);
        entity.setReason(clip(reason));
        entity.setMessage("prepared " + leg.toLowerCase(Locale.ROOT) + " order");
        entity.setAccountId(position.getAccountId());
        entity.setAccountLabel(position.getAccountLabel() == null ? "primary" : position.getAccountLabel());
        entity.setStrategyFamily(position.getStrategyFamily());
        entity.setStrategyName(position.getStrategyName());
        entity.setModelName(position.getModelName());
        entity.setFactorSnapshotId(position.getFactorSnapshotId());
        entity.setTimeframeProfile(position.getTimeframeProfile());
        entity.setFeeRateUsed(position.getEntryFeeRate());
        entity.setFeeProfileId(position.getEntryFeeProfileId());
        entity.setEstimatedOpenFee(position.getEstimatedEntryFee());
        entity.setEstimatedCloseFee(position.getEstimatedCloseFee());
        entity.setEstimatedFee(HtxTrendRecorder.LEG_CLOSE.equals(leg)
                ? position.getEstimatedCloseFee() : position.getEstimatedEntryFee());
        entity.setSizingMultiplier(position.getSizingMultiplier());
        entity.setSizingReason(clip(position.getSizingReason()));
        entity.setEntryGateReason(clip(position.getEntryGateReason(), 1024));
        return entity;
    }

    private EntryCostContext entryCostContext(TrendSignal signal, BigDecimal entryPrice, BigDecimal spreadRate,
                                              BigDecimal contractSize, long volume, BigDecimal takerFeeRate,
                                              DirectionConfidence confidence) {
        BigDecimal quantity = contractSize.multiply(BigDecimal.valueOf(volume));
        BigDecimal entryNotional = entryPrice.multiply(quantity);
        BigDecimal estimatedEntryFee = entryNotional.multiply(nonNegative(takerFeeRate));
        BigDecimal estimatedCloseFee = entryNotional.multiply(nonNegative(takerFeeRate));
        BigDecimal spreadCost = entryNotional.multiply(nonNegative(spreadRate));
        BigDecimal expectedMove = positive(signal.atr())
                ? signal.atr().multiply(nonNegative(properties.getMinExpectedMoveAtrMultiplier())) : BigDecimal.ZERO;
        BigDecimal expectedGross = expectedMove.multiply(quantity);
        BigDecimal expectedNet = expectedGross.subtract(estimatedEntryFee).subtract(estimatedCloseFee).subtract(spreadCost);
        BigDecimal requiredMove = entryPrice.multiply(roundTripCostRate(spreadRate, takerFeeRate));
        String confidencePart = confidence == null ? "directionConfidence=NA"
                : "directionConfidence=PASS score=" + confidence.score().setScale(2, RoundingMode.HALF_UP)
                + " required=" + confidence.requiredScore().setScale(2, RoundingMode.HALF_UP)
                + " detail=" + confidence.reason();
        String gateReason = confidencePart + "; expectedMoveGate=PASS expectedMove=" + expectedMove
                + " requiredMove=" + requiredMove + " spreadRate=" + nonNegative(spreadRate)
                + " feeRate=" + nonNegative(takerFeeRate);
        return new EntryCostContext(estimatedEntryFee, estimatedCloseFee, expectedNet, gateReason);
    }

    private void recordCombinationEntryCostEvidence(String contractCode, TrendSignal signal,
                                                    HtxSwapContractInfo contract, CombinationContext combination,
                                                    HtxDepth depth, TopOfBook book, long volume,
                                                    HtxSwapFeeRateResolver.FeeRates feeRates,
                                                    EntryCostContext entryCost, String liquidityGate,
                                                    String expectedMoveGate) {
        if (combinationService == null || combination == null || signal == null || contract == null || book == null
                || feeRates == null || entryCost == null) {
            return;
        }
        int depthLevels = Math.max(1, properties.getEntryDepthLevels());
        List<HtxPriceLevel> levels = signal.side() == TrendSide.LONG ? depth.getAsks() : depth.getBids();
        BigDecimal availableContracts = sumTopLevels(levels, depthLevels);
        BigDecimal multiplier = positive(properties.getMinEntryDepthMultiplier())
                ? properties.getMinEntryDepthMultiplier() : BigDecimal.ONE;
        BigDecimal requiredContracts = BigDecimal.valueOf(volume).multiply(multiplier);
        BigDecimal coverageRatio = positive(requiredContracts)
                ? availableContracts.divide(requiredContracts, 12, RoundingMode.HALF_UP) : null;
        BigDecimal entryPrice = signal.side() == TrendSide.LONG ? book.ask() : book.bid();
        BigDecimal notional = BigDecimal.valueOf(volume).multiply(contract.getContractSize()).multiply(entryPrice);
        BigDecimal expectedMove = positive(signal.atr())
                ? signal.atr().multiply(nonNegative(properties.getMinExpectedMoveAtrMultiplier())) : BigDecimal.ZERO;
        BigDecimal expectedGross = expectedMove.multiply(contract.getContractSize()).multiply(BigDecimal.valueOf(volume));
        BigDecimal spreadCost = notional.multiply(nonNegative(book.ask().subtract(book.bid())
                .divide(mid(book), 18, RoundingMode.HALF_UP)));
        BigDecimal requiredMove = entryPrice.multiply(roundTripCostRate(book.ask().subtract(book.bid())
                .divide(mid(book), 18, RoundingMode.HALF_UP), feeRates.taker()));
        String gateReason = liquidityGate != null ? liquidityGate
                : expectedMoveGate != null ? expectedMoveGate : "all cost gates passed";
        String gateResult = liquidityGate != null ? "REJECTED_LIQUIDITY"
                : expectedMoveGate != null ? "REJECTED_COST" : "PASSED";
        combinationService.recordEntryCostEvidence(contractCode, properties.getPeriod(), signal.barTime(),
                combination.policyVersion(), combination.marketRegime(),
                new HtxTrendCombinationService.EntryCostEvidence(combination.runId(), "primary",
                        feeRates.fallback() ? "CONFIG_FALLBACK" : "HTX_ACCOUNT",
                        feeRates.openTaker(), feeRates.closeTaker(), feeRates.taker(), book.bid(), book.ask(),
                        book.ask().subtract(book.bid()).divide(mid(book), 18, RoundingMode.HALF_UP), depthLevels,
                        availableContracts, requiredContracts, coverageRatio, signal.side().name(), entryPrice, volume,
                        notional, expectedMove, expectedGross, entryCost.estimatedEntryFee(),
                        entryCost.estimatedCloseFee(), spreadCost, entryCost.expectedNetAfterFees(), requiredMove,
                        gateResult, gateReason));
    }

    /** Baseline plan records the price actually used today; later limit-order experiments consume this contract. */
    private void recordCombinationPricePlan(String contractCode, TrendSignal signal, HtxSwapContractInfo contract,
                                            CombinationContext combination, BigDecimal entryPrice, BigDecimal stopPrice,
                                            TopOfBook book, long volume, HtxSwapFeeRateResolver.FeeRates feeRates,
                                            EntryCostContext entryCost) {
        if (combinationService == null || combination == null || signal == null || contract == null || book == null
                || feeRates == null || entryCost == null || !positive(entryPrice) || !positive(stopPrice)) {
            return;
        }
        BigDecimal riskDistance = entryPrice.subtract(stopPrice).abs();
        BigDecimal quantity = contract.getContractSize().multiply(BigDecimal.valueOf(volume));
        BigDecimal plannedRisk = riskDistance.multiply(quantity);
        BigDecimal oneR = signal.side() == TrendSide.LONG ? entryPrice.add(riskDistance) : entryPrice.subtract(riskDistance);
        BigDecimal twoR = signal.side() == TrendSide.LONG ? entryPrice.add(riskDistance.multiply(new BigDecimal("2")))
                : entryPrice.subtract(riskDistance.multiply(new BigDecimal("2")));
        BigDecimal structureTarget = signal.side() == TrendSide.LONG ? combination.targetZoneLow() : combination.targetZoneHigh();
        BigDecimal structureGross = positive(structureTarget)
                ? structureTarget.subtract(entryPrice).abs().multiply(quantity) : BigDecimal.ZERO;
        BigDecimal spreadCost = entryPrice.multiply(quantity).multiply(book.ask().subtract(book.bid())
                .divide(mid(book), 18, RoundingMode.HALF_UP));
        BigDecimal netR = plannedRisk.signum() > 0 ? structureGross.subtract(entryCost.estimatedEntryFee())
                .subtract(entryCost.estimatedCloseFee()).subtract(spreadCost)
                .divide(plannedRisk, 12, RoundingMode.HALF_UP) : null;
        Long validUntil = signal.barTime() == null ? null : signal.barTime() + periodSeconds(properties.getPeriod())
                + Math.max(0L, properties.getMaxEntrySignalAgeSeconds());
        combinationService.recordPricePlan(contractCode, properties.getPeriod(), signal.barTime(), combination.policyVersion(),
                combination.marketRegime(), new HtxTrendCombinationService.PricePlanEvidence(combination.runId(),
                timeframeProfile(properties.getPeriod()), signal.closePrice(), entryPrice, entryPrice, validUntil,
                combination.invalidationPrice(), stopPrice, scalePrice(oneR, contract), scalePrice(twoR, contract),
                structureTarget, plannedRisk, netR, signal.side().name(), contract.getContractSize(), volume,
                entryCost.estimatedEntryFee().add(entryCost.estimatedCloseFee()).add(spreadCost)));
    }

    private static String timeframeProfile(String period) {
        return HtxTrendTimeframeProfile.fromPeriod(period).name();
    }

    private BigDecimal atrStopMultiplier(HtxTrendTimeframeProfile profile) {
        if (profile == HtxTrendTimeframeProfile.SWING_4H || profile == HtxTrendTimeframeProfile.TREND_1D) {
            return nonNegative(properties.getSwingAtrStopMultiplier());
        }
        return nonNegative(properties.getAtrStopMultiplier());
    }

    private BigDecimal trailingAtrMultiplier(HtxTrendTimeframeProfile profile) {
        if (profile == HtxTrendTimeframeProfile.SWING_4H || profile == HtxTrendTimeframeProfile.TREND_1D) {
            return nonNegative(properties.getSwingTrailingAtrMultiplier());
        }
        return nonNegative(properties.getTrailingAtrMultiplier());
    }

    private BigDecimal takeProfitAtrMultiplier(HtxTrendTimeframeProfile profile) {
        if (profile == HtxTrendTimeframeProfile.SWING_4H || profile == HtxTrendTimeframeProfile.TREND_1D) {
            return nonNegative(properties.getSwingTakeProfitAtrMultiplier());
        }
        return nonNegative(properties.getTakeProfitAtrMultiplier());
    }

    private long targetVolume(BigDecimal entryPrice, BigDecimal atr, BigDecimal contractSize, SizingContext sizing) {
        if (!positive(entryPrice) || !positive(atr) || !positive(contractSize)) {
            return 0L;
        }
        BigDecimal multiplier = sizing == null ? BigDecimal.ONE : sizing.multiplier();
        BigDecimal stopDistance = atr.multiply(atrStopMultiplier(HtxTrendTimeframeProfile.fromPeriod(properties.getPeriod())));
        if (stopDistance.signum() <= 0) {
            return 0L;
        }
        BigDecimal riskSizedNotional = nonNegative(properties.getRiskPerTradeUsdt()).multiply(multiplier)
                .divide(stopDistance.divide(entryPrice, 18, RoundingMode.UP), 18, RoundingMode.DOWN);
        BigDecimal targetNotional = nonNegative(properties.getTargetQuoteNotional()).multiply(multiplier);
        BigDecimal maxNotional = nonNegative(properties.getMaxQuoteNotional());
        BigDecimal notional = min(targetNotional, maxNotional, riskSizedNotional);
        if (notional.compareTo(properties.getMinQuoteNotional()) < 0) {
            return 0L;
        }
        BigDecimal perContract = entryPrice.multiply(contractSize);
        return notional.divide(perContract, 0, RoundingMode.DOWN).longValue();
    }

    private long targetVolumeForNotional(BigDecimal entryPrice, BigDecimal contractSize, BigDecimal notional) {
        if (!positive(entryPrice) || !positive(contractSize) || !positive(notional)
                || notional.compareTo(properties.getMinQuoteNotional()) < 0) {
            return 0L;
        }
        return notional.divide(entryPrice.multiply(contractSize), 0, RoundingMode.DOWN).longValue();
    }

    private String expectedMoveGate(TrendSignal signal, BigDecimal entryPrice, BigDecimal spreadRate,
                                    BigDecimal takerFeeRate) {
        if (!properties.isFeeProtectionEnabled() || !positive(signal.atr()) || !positive(entryPrice)) {
            return null;
        }
        BigDecimal expectedMove = signal.atr().multiply(nonNegative(properties.getMinExpectedMoveAtrMultiplier()));
        BigDecimal requiredMove = entryPrice.multiply(roundTripCostRate(spreadRate, takerFeeRate));
        if (expectedMove.compareTo(requiredMove) < 0) {
            return "expected move too small after fees expectedMove=" + expectedMove
                    + " requiredMove=" + requiredMove + " atr=" + signal.atr()
                    + " feeRate=" + nonNegative(takerFeeRate);
        }
        return null;
    }

    private String entryChaseGate(TrendSignal signal, BigDecimal entryPrice) {
        if (signal == null || signal.side() == TrendSide.FLAT || !positive(entryPrice)
                || !positive(signal.closePrice())) {
            return null;
        }
        BigDecimal worseFill = signal.side() == TrendSide.LONG
                ? entryPrice.subtract(signal.closePrice())
                : signal.closePrice().subtract(entryPrice);
        if (worseFill.signum() <= 0) {
            return null;
        }
        BigDecimal maxChase = BigDecimal.ZERO;
        BigDecimal atrMultiplier = nonNegative(properties.getMaxEntrySignalChaseAtrMultiplier());
        if (atrMultiplier.signum() > 0 && positive(signal.atr())) {
            maxChase = signal.atr().multiply(atrMultiplier);
        }
        BigDecimal chaseRate = nonNegative(properties.getMaxEntrySignalChaseRate());
        if (chaseRate.signum() > 0) {
            BigDecimal rateLimit = signal.closePrice().multiply(chaseRate);
            maxChase = positive(maxChase) ? min(maxChase, rateLimit) : rateLimit;
        }
        if (!positive(maxChase) || worseFill.compareTo(maxChase) <= 0) {
            return null;
        }
        BigDecimal chaseRateValue = worseFill.divide(signal.closePrice(), 18, RoundingMode.HALF_UP);
        BigDecimal chaseAtr = positive(signal.atr())
                ? worseFill.divide(signal.atr(), 18, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        return "entry price chased signal too far entryPrice=" + entryPrice
                + " signalClose=" + signal.closePrice()
                + " chase=" + worseFill
                + " maxChase=" + maxChase
                + " chaseRate=" + chaseRateValue
                + " chaseAtr=" + chaseAtr;
    }

    private String entrySignalFreshnessGate(TrendSignal signal) {
        long maxAgeSeconds = properties.getMaxEntrySignalAgeSeconds();
        if (maxAgeSeconds <= 0L) {
            return null;
        }
        Long barTime = signal.barTime();
        if (barTime == null || barTime <= 0L) {
            return "entry signal bar time unavailable";
        }
        long closeEpoch = barTime + periodSeconds(properties.getPeriod());
        long ageSeconds = Instant.now().getEpochSecond() - closeEpoch;
        if (ageSeconds < 0L) {
            return "entry signal bar not closed ageSeconds=" + ageSeconds;
        }
        if (ageSeconds > maxAgeSeconds) {
            return "entry signal too stale ageSeconds=" + ageSeconds + " max=" + maxAgeSeconds;
        }
        return null;
    }

    private static long periodSeconds(String period) {
        String value = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith("min")) {
            return Long.parseLong(value.substring(0, value.length() - 3)) * 60L;
        }
        if (value.endsWith("hour")) {
            return Long.parseLong(value.substring(0, value.length() - 4)) * 3_600L;
        }
        if (value.endsWith("day")) {
            return Long.parseLong(value.substring(0, value.length() - 3)) * 86_400L;
        }
        throw new IllegalArgumentException("unsupported HTX trend period: " + period);
    }

    private BigDecimal initialStop(TrendSignal signal, String side, HtxSwapContractInfo contract,
                                   HtxTrendTimeframeProfile timeframeProfile) {
        BigDecimal distance = signal.atr().multiply(atrStopMultiplier(timeframeProfile));
        BigDecimal value = LONG.equals(side) ? signal.closePrice().subtract(distance) : signal.closePrice().add(distance);
        return scalePrice(value, contract);
    }

    private BigDecimal initialTakeProfit(TrendSignal signal, String side, HtxSwapContractInfo contract,
                                         HtxTrendTimeframeProfile timeframeProfile) {
        BigDecimal multiplier = takeProfitAtrMultiplier(timeframeProfile);
        if (multiplier.signum() <= 0) {
            return null;
        }
        BigDecimal distance = signal.atr().multiply(multiplier);
        BigDecimal value = LONG.equals(side) ? signal.closePrice().add(distance) : signal.closePrice().subtract(distance);
        return scalePrice(value, contract);
    }

    private StopContext stopContext(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference,
                                    BigDecimal highest, BigDecimal lowest, HtxSwapContractInfo contract,
                                    List<HtxSwapKline> entryCandles) {
        BigDecimal atrStop = atrTrailingStop(position, signal, reference, highest, lowest, contract);
        BigDecimal structureStop = structureStopCandidate(position, entryCandles, contract);
        if (properties.isStructureStopEnabled() && structureStopSelectable(position, reference, atrStop, structureStop)) {
            return new StopContext(structureStop, "STRUCTURE_STOP", atrStop, structureStop);
        }
        return new StopContext(atrStop, "ATR_TRAILING", atrStop, structureStop);
    }

    private boolean structureStopSelectable(HtxTrendPositionEntity position, BigDecimal reference, BigDecimal atrStop,
                                            BigDecimal structureStop) {
        if (position == null || !positive(reference) || !positive(atrStop) || !positive(structureStop)) {
            return false;
        }
        BigDecimal initialStop = position.getStopPrice();
        BigDecimal previousStop = firstPositive(position.getTrailingStopPrice(), initialStop);
        if (LONG.equals(position.getSide())) {
            return structureStop.compareTo(reference) < 0
                    && structureStop.compareTo(atrStop) < 0
                    && (!positive(initialStop) || structureStop.compareTo(initialStop) >= 0)
                    && (!positive(previousStop) || structureStop.compareTo(previousStop) >= 0);
        }
        return structureStop.compareTo(reference) > 0
                && structureStop.compareTo(atrStop) > 0
                && (!positive(initialStop) || structureStop.compareTo(initialStop) <= 0)
                && (!positive(previousStop) || structureStop.compareTo(previousStop) <= 0);
    }

    private BigDecimal atrTrailingStop(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference,
                                       BigDecimal highest, BigDecimal lowest, HtxSwapContractInfo contract) {
        HtxTrendTimeframeProfile timeframeProfile = HtxTrendTimeframeProfile.fromPersisted(
                position.getTimeframeProfile(), properties.getPeriod());
        BigDecimal trailingMultiplier = trailingAtrMultiplier(timeframeProfile);
        BigDecimal distance = positive(signal.atr()) ? signal.atr().multiply(trailingMultiplier)
                : reference.multiply(properties.getMinAtrPct()).multiply(trailingMultiplier);
        BigDecimal candidate = LONG.equals(position.getSide()) ? highest.subtract(distance) : lowest.add(distance);
        BigDecimal previous = firstPositive(position.getTrailingStopPrice(), position.getStopPrice(), candidate);
        if (LONG.equals(position.getSide())) {
            return scalePrice(max(previous, candidate), contract);
        }
        return scalePrice(min(previous, candidate), contract);
    }

    private BigDecimal structureStopCandidate(HtxTrendPositionEntity position, List<HtxSwapKline> entryCandles,
                                              HtxSwapContractInfo contract) {
        if (position == null || entryCandles == null || entryCandles.isEmpty()) {
            return null;
        }
        int lookback = Math.min(5, entryCandles.size());
        BigDecimal candidate = null;
        for (int i = entryCandles.size() - lookback; i < entryCandles.size(); i++) {
            HtxSwapKline candle = entryCandles.get(i);
            if (candle == null) {
                continue;
            }
            BigDecimal value = LONG.equals(position.getSide()) ? candle.getLow() : candle.getHigh();
            if (!positive(value)) {
                continue;
            }
            if (candidate == null) {
                candidate = value;
            } else if (LONG.equals(position.getSide())) {
                candidate = candidate.min(value);
            } else {
                candidate = candidate.max(value);
            }
        }
        return candidate == null ? null : scalePrice(candidate, contract);
    }

    private BigDecimal feeProtectedStop(HtxTrendPositionEntity position, BigDecimal highest, BigDecimal lowest,
                                        HtxSwapContractInfo contract) {
        if (!properties.isFeeProtectionEnabled() || position == null || !positive(position.getEntryAvgPrice())
                || !positive(position.getContractSize()) || position.getContractVolume() == null
                || position.getContractVolume() <= 0L) {
            return null;
        }
        long remaining = remainingContractVolume(position);
        if (remaining <= 0L) {
            return null;
        }
        BigDecimal quantity = position.getContractSize().multiply(BigDecimal.valueOf(remaining));
        if (!positive(quantity)) {
            return null;
        }
        BigDecimal entryPrice = position.getEntryAvgPrice();
        BigDecimal entryNotional = entryPrice.multiply(quantity);
        BigDecimal takerFeeRate = effectiveTakerFeeRate(position.getContractCode());
        BigDecimal entryFee = remainingEntryFee(position);
        if (!positive(entryFee)) {
            entryFee = entryNotional.multiply(takerFeeRate);
        }
        BigDecimal estimatedCloseFee = entryNotional.multiply(takerFeeRate);
        BigDecimal minProfit = entryNotional.multiply(nonNegative(properties.getMinNetProfitRate()))
                .max(nonNegative(properties.getMinFlatExitProfitUsdt()));
        BigDecimal priceDelta = entryFee.add(estimatedCloseFee).add(minProfit)
                .divide(quantity, 18, RoundingMode.UP);
        if (LONG.equals(position.getSide())) {
            BigDecimal protectedStop = scalePrice(entryPrice.add(priceDelta), contract, RoundingMode.UP);
            return highest.compareTo(protectedStop) >= 0 ? protectedStop : null;
        }
        BigDecimal protectedStop = scalePrice(entryPrice.subtract(priceDelta), contract, RoundingMode.DOWN);
        return lowest.compareTo(protectedStop) <= 0 ? protectedStop : null;
    }

    private String feeProtectionMessage(BigDecimal feeProtectedStop) {
        return feeProtectedStop == null ? "" : " feeProtectedStop=" + feeProtectedStop;
    }

    private String flatAfterFeeProtectionExitReason(HtxTrendPositionEntity position, TrendSignal signal,
                                                   BigDecimal reference, BigDecimal feeProtectedStop) {
        if (!properties.isExitFlatAfterFeeProtection() || feeProtectedStop == null
                || signal.side() != TrendSide.FLAT || !positive(reference)) {
            return null;
        }
        int requiredFlatBars = Math.max(1, properties.getFlatExitConfirmationBars());
        LocalDateTime since = position.getCreatedAt() == null ? SIGNAL_HISTORY_FLOOR : position.getCreatedAt();
        long flatBars = recorder.countRecentSignalsBySide(position.getContractCode(), TrendSide.FLAT.name(),
                since, requiredFlatBars);
        if (flatBars < requiredFlatBars) {
            return null;
        }
        if (LONG.equals(position.getSide()) && reference.compareTo(feeProtectedStop) >= 0) {
            return exitEngineReason(EXIT_FEE_PROTECTION,
                    "long exit on confirmed flat trend after fee protection ref=" + reference
                            + " stop=" + feeProtectedStop + " flatBars=" + flatBars + "/" + requiredFlatBars);
        }
        if (SHORT.equals(position.getSide()) && reference.compareTo(feeProtectedStop) <= 0) {
            return exitEngineReason(EXIT_FEE_PROTECTION,
                    "short exit on confirmed flat trend after fee protection ref=" + reference
                            + " stop=" + feeProtectedStop + " flatBars=" + flatBars + "/" + requiredFlatBars);
        }
        return null;
    }

    private String flatLossControlExitReason(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference) {
        if (position == null || signal.side() != TrendSide.FLAT || !positive(reference)) {
            return null;
        }
        int requiredFlatBars = Math.max(1, properties.getFlatLossExitConfirmationBars());
        long flatBars = countRecentFlatSignals(position, requiredFlatBars);
        if (flatBars < requiredFlatBars) {
            return null;
        }
        BigDecimal estimatedNet = estimatedCloseNetProfit(position, reference);
        if (estimatedNet == null || estimatedNet.signum() >= 0) {
            return null;
        }
        BigDecimal lossBudget = flatLossBudget(position);
        if (!positive(lossBudget) || estimatedNet.negate().compareTo(lossBudget) < 0) {
            return null;
        }
        return exitEngineReason(EXIT_TREND_EXHAUSTION,
                position.getSide().toLowerCase(Locale.ROOT) + " exit on confirmed flat trend loss control ref="
                        + reference + " estimatedNet=" + estimatedNet + " lossBudget=" + lossBudget
                        + " flatBars=" + flatBars + "/" + requiredFlatBars);
    }

    private ExitSnapshot exitSnapshot(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference,
                                      BigDecimal highest, BigDecimal lowest, BigDecimal trailingStop,
                                      BigDecimal takeProfit, StopContext stopContext) {
        BigDecimal currentNet = estimatedCloseNetProfit(position, reference);
        BigDecimal favorablePrice = LONG.equals(position.getSide()) ? highest : lowest;
        BigDecimal adversePrice = LONG.equals(position.getSide()) ? lowest : highest;
        BigDecimal mfe = estimatedCloseNetProfit(position, favorablePrice);
        BigDecimal mae = estimatedCloseNetProfit(position, adversePrice);
        BigDecimal giveback = givebackRate(currentNet, mfe);
        long bars = timeInPositionBars(position);
        String state = exitState(position.getSide(), signal, currentNet, mfe, giveback);
        String candidates = exitCandidateSummary(position, signal, reference, trailingStop, takeProfit, stopContext,
                bars, currentNet, mfe, giveback);
        return new ExitSnapshot(state, currentNet, mfe, mae, giveback, bars, candidates);
    }

    private String exitCandidateSummary(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference,
                                        BigDecimal trailingStop, BigDecimal takeProfit, StopContext stopContext,
                                        long bars, BigDecimal currentNet, BigDecimal mfe, BigDecimal giveback) {
        List<String> candidates = new ArrayList<>();
        if (positive(position.getInvalidationPrice())) {
            candidates.add("MODEL_INVALIDATION:" + triggerState(crossed(position.getSide(), reference,
                    position.getInvalidationPrice())) + "@" + position.getInvalidationPrice());
        }
        if (positive(position.getStopPrice())) {
            candidates.add("INITIAL_STOP:" + triggerState(crossed(position.getSide(), reference,
                    position.getStopPrice())) + "@" + position.getStopPrice());
        }
        if (positive(trailingStop)) {
            candidates.add("TRAILING_STOP:" + triggerState(crossed(position.getSide(), reference, trailingStop))
                    + "@" + trailingStop + "/" + stopContext.stopSource());
        }
        if (positive(takeProfit)) {
            candidates.add("TARGET_ZONE:" + triggerState(targetReached(position.getSide(), reference, takeProfit))
                    + "@" + takeProfit);
        }
        String partialTakeProfit = partialTakeProfitCandidate(position, reference, currentNet);
        if (partialTakeProfit != null) {
            candidates.add(partialTakeProfit);
        }
        String profitProtection = profitProtectionCandidate(currentNet, mfe, giveback);
        if (profitProtection != null) {
            candidates.add(profitProtection);
        }
        candidates.add(timeframeConfirmationCandidate(position));
        if (signal.side() == TrendSide.FLAT) {
            candidates.add("TREND_EXHAUSTION:watch:FLAT");
        } else if ((LONG.equals(position.getSide()) && signal.side() == TrendSide.SHORT)
                || (SHORT.equals(position.getSide()) && signal.side() == TrendSide.LONG)) {
            candidates.add("TREND_EXHAUSTION:trigger:" + signal.side());
        }
        candidates.add("TIME_STOP:observe:bars=" + bars);
        return String.join(",", candidates);
    }

    private String partialTakeProfitCandidate(HtxTrendPositionEntity position, BigDecimal reference, BigDecimal currentNet) {
        BigDecimal oneR = rTargetPrice(position, BigDecimal.ONE);
        BigDecimal twoR = rTargetPrice(position, new BigDecimal("2"));
        BigDecimal structuralTarget = structuralTargetPrice(position);
        if (!positive(oneR) && !positive(twoR) && !positive(structuralTarget)) {
            return null;
        }
        boolean oneRReached = positive(oneR) && targetReached(position.getSide(), reference, oneR);
        boolean twoRReached = positive(twoR) && targetReached(position.getSide(), reference, twoR);
        boolean structuralReached = positive(structuralTarget) && targetReached(position.getSide(), reference, structuralTarget);
        boolean profitable = currentNet != null && currentNet.signum() > 0;
        String state = profitable && (oneRReached || structuralReached) ? "simulate_trigger" : "observe";
        String suggestClose = twoRReached ? "50%" : oneRReached || structuralReached ? "33%" : "0%";
        return "PARTIAL_TAKE_PROFIT:" + state + ":1R@" + priceText(oneR)
                + ",2R@" + priceText(twoR) + ",structure@" + priceText(structuralTarget)
                + ",suggestClose=" + suggestClose;
    }

    private String profitProtectionCandidate(BigDecimal currentNet, BigDecimal mfe, BigDecimal giveback) {
        if (currentNet == null) {
            return null;
        }
        if (currentNet.signum() <= 0) {
            return "PROFIT_PROTECTION:inactive:net=" + moneyText(currentNet);
        }
        BigDecimal breakeven = nonNegative(properties.getMinFlatExitProfitUsdt());
        BigDecimal protectNet = positive(mfe) ? mfe.multiply(new BigDecimal("0.50")) : breakeven;
        protectNet = max(protectNet, breakeven);
        String state = positive(giveback) && giveback.compareTo(new BigDecimal("0.35")) >= 0
                ? "simulate_trigger" : "observe";
        return "PROFIT_PROTECTION:" + state + ":net=" + moneyText(currentNet)
                + ",mfe=" + moneyText(mfe) + ",giveback=" + pct(giveback)
                + ",protectNet=" + moneyText(protectNet);
    }

    private String timeframeConfirmationCandidate(HtxTrendPositionEntity position) {
        if (position == null || position.getContractCode() == null || position.getSide() == null) {
            return "TIMEFRAME_CONFIRMATION:unknown";
        }
        int aligned = 0;
        int opposite = 0;
        List<String> parts = new ArrayList<>(EXIT_CONFIRMATION_PERIODS.size());
        for (String period : EXIT_CONFIRMATION_PERIODS) {
            TrendSide side = marketAnchorPeriodSide(position.getContractCode(), period);
            if ((LONG.equals(position.getSide()) && side == TrendSide.LONG)
                    || (SHORT.equals(position.getSide()) && side == TrendSide.SHORT)) {
                aligned++;
            } else if ((LONG.equals(position.getSide()) && side == TrendSide.SHORT)
                    || (SHORT.equals(position.getSide()) && side == TrendSide.LONG)) {
                opposite++;
            }
            parts.add(periodLabel(period) + "=" + side);
        }
        String state;
        if (aligned == EXIT_CONFIRMATION_PERIODS.size()) {
            state = "hold";
        } else if (opposite >= 2) {
            state = "warn_major";
        } else if (opposite == 1) {
            state = "warn_short";
        } else {
            state = "mixed";
        }
        return "TIMEFRAME_CONFIRMATION:" + state + ":" + String.join(",", parts);
    }

    private static String periodLabel(String period) {
        String normalized = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("min")) {
            return normalized.substring(0, normalized.length() - 3) + "m";
        }
        if (normalized.endsWith("hour")) {
            return normalized.substring(0, normalized.length() - 4) + "h";
        }
        if (normalized.endsWith("day")) {
            return normalized.substring(0, normalized.length() - 3) + "d";
        }
        return normalized;
    }

    private BigDecimal rTargetPrice(HtxTrendPositionEntity position, BigDecimal multiple) {
        return position == null ? null : HtxTrendExitRules.rTargetPrice(position.getSide(), position.getEntryAvgPrice(),
                position.getStopPrice(), multiple);
    }

    private BigDecimal structuralTargetPrice(HtxTrendPositionEntity position) {
        if (position == null) {
            return null;
        }
        if (LONG.equals(position.getSide())) {
            return firstPositive(position.getTargetZoneLow(), position.getTargetZoneHigh(), position.getTakeProfitPrice());
        }
        return firstPositive(position.getTargetZoneHigh(), position.getTargetZoneLow(), position.getTakeProfitPrice());
    }

    private static String priceText(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static String moneyText(BigDecimal value) {
        return value == null ? "-" : value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static boolean crossed(String side, BigDecimal reference, BigDecimal price) {
        return HtxTrendExitRules.crossedStop(side, reference, price);
    }

    private static boolean targetReached(String side, BigDecimal reference, BigDecimal target) {
        return HtxTrendExitRules.targetReached(side, reference, target);
    }

    private static String triggerState(boolean triggered) {
        return triggered ? "trigger" : "watch";
    }

    private String exitState(String positionSide, TrendSignal signal, BigDecimal currentNet, BigDecimal mfe, BigDecimal giveback) {
        if (currentNet == null) {
            return "UNKNOWN";
        }
        if (currentNet.signum() < 0) {
            return "LOSS_ZONE";
        }
        BigDecimal breakeven = nonNegative(properties.getMinFlatExitProfitUsdt());
        if (currentNet.compareTo(breakeven) <= 0) {
            return "FEE_BREAKEVEN_ZONE";
        }
        if (positive(giveback) && giveback.compareTo(new BigDecimal("0.50")) >= 0) {
            return "EXHAUSTION_ZONE";
        }
        if (positive(mfe) && currentNet.signum() > 0 && signal != null
                && ((LONG.equals(positionSide) && signal.side() == TrendSide.LONG)
                || (SHORT.equals(positionSide) && signal.side() == TrendSide.SHORT))) {
            return "RUNNER_ZONE";
        }
        return "PROFIT_ZONE";
    }

    private BigDecimal givebackRate(BigDecimal currentNet, BigDecimal mfe) {
        if (currentNet == null || !positive(mfe)) {
            return BigDecimal.ZERO;
        }
        BigDecimal giveback = mfe.subtract(currentNet);
        if (giveback.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return giveback.divide(mfe, 18, RoundingMode.HALF_UP);
    }

    private long timeInPositionBars(HtxTrendPositionEntity position) {
        if (position == null || position.getCreatedAt() == null) {
            return 0L;
        }
        long seconds = Math.max(0L, Duration.between(position.getCreatedAt(), LocalDateTime.now()).getSeconds());
        HtxTrendTimeframeProfile profile = HtxTrendTimeframeProfile.fromPersisted(
                position.getTimeframeProfile(), properties.getPeriod());
        return seconds / Math.max(1L, periodSeconds(profile.entryPeriod()));
    }

    private BigDecimal estimatedCloseNetProfit(HtxTrendPositionEntity position, BigDecimal reference) {
        if (position == null || !positive(position.getEntryAvgPrice()) || !positive(position.getContractSize())
                || position.getContractVolume() == null || position.getContractVolume() <= 0L || !positive(reference)) {
            return null;
        }
        long remaining = remainingContractVolume(position);
        if (remaining <= 0L) {
            return null;
        }
        BigDecimal quantity = position.getContractSize().multiply(BigDecimal.valueOf(remaining));
        if (!positive(quantity)) {
            return null;
        }
        BigDecimal pricePnl = LONG.equals(position.getSide())
                ? reference.subtract(position.getEntryAvgPrice()).multiply(quantity)
                : position.getEntryAvgPrice().subtract(reference).multiply(quantity);
        BigDecimal takerFeeRate = effectiveTakerFeeRate(position.getContractCode());
        BigDecimal entryFee = remainingEntryFee(position);
        if (!positive(entryFee)) {
            entryFee = position.getEntryAvgPrice().multiply(quantity).multiply(takerFeeRate);
        }
        BigDecimal closeFee = reference.multiply(quantity).multiply(takerFeeRate);
        return pricePnl.subtract(entryFee).subtract(closeFee);
    }

    /** Allocates the one-time entry fee to contracts still open after completed partial exits. */
    private static BigDecimal remainingEntryFee(HtxTrendPositionEntity position) {
        if (position == null || !positive(position.getEntryFee()) || position.getContractVolume() == null
                || position.getContractVolume() <= 0L) {
            return BigDecimal.ZERO;
        }
        long remaining = remainingContractVolume(position);
        if (remaining <= 0L) {
            return BigDecimal.ZERO;
        }
        return position.getEntryFee().multiply(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(position.getContractVolume()), 18, RoundingMode.HALF_UP);
    }

    /** Uses the recorded opening fee, with a current account-rate estimate only for legacy rows without it. */
    private BigDecimal exitEngineEntryFee(HtxTrendPositionEntity position) {
        BigDecimal recorded = remainingEntryFee(position);
        if (positive(recorded) || position == null || !positive(position.getEntryAvgPrice())
                || !positive(position.getContractSize())) {
            return recorded;
        }
        long remaining = remainingContractVolume(position);
        if (remaining <= 0L) {
            return BigDecimal.ZERO;
        }
        return position.getEntryAvgPrice().multiply(position.getContractSize()).multiply(BigDecimal.valueOf(remaining))
                .multiply(effectiveTakerFeeRate(position.getContractCode()));
    }

    /** Uses remaining contracts so a post-partial runner is not charged as if its full original size still existed. */
    private BigDecimal estimatedCloseFee(HtxTrendPositionEntity position, BigDecimal reference) {
        if (position == null || !positive(position.getContractSize()) || !positive(reference)) {
            return BigDecimal.ZERO;
        }
        long remaining = remainingContractVolume(position);
        if (remaining <= 0L) {
            return BigDecimal.ZERO;
        }
        return reference.multiply(position.getContractSize()).multiply(BigDecimal.valueOf(remaining))
                .multiply(effectiveTakerFeeRate(position.getContractCode()));
    }

    private BigDecimal flatLossBudget(HtxTrendPositionEntity position) {
        if (position == null || !positive(position.getEntryAvgPrice()) || !positive(position.getContractSize())
                || position.getContractVolume() == null || position.getContractVolume() <= 0L) {
            return BigDecimal.ZERO;
        }
        BigDecimal quantity = position.getContractSize().multiply(BigDecimal.valueOf(position.getContractVolume()));
        BigDecimal entryNotional = position.getEntryAvgPrice().multiply(quantity);
        return nonNegative(properties.getMaxFlatExitLossUsdt())
                .max(entryNotional.multiply(nonNegative(properties.getMaxFlatExitLossRate())));
    }

    private long countRecentFlatSignals(HtxTrendPositionEntity position, int requiredFlatBars) {
        LocalDateTime since = position.getCreatedAt() == null ? SIGNAL_HISTORY_FLOOR : position.getCreatedAt();
        return recorder.countRecentSignalsBySide(position.getContractCode(), TrendSide.FLAT.name(),
                since, requiredFlatBars);
    }

    private long countConsecutiveFlatSignals(HtxTrendPositionEntity position, int requiredFlatBars) {
        LocalDateTime since = position.getCreatedAt() == null ? SIGNAL_HISTORY_FLOOR : position.getCreatedAt();
        return recorder.countConsecutiveSignalsBySide(position.getContractCode(), properties.getPeriod(),
                TrendSide.FLAT.name(), since, requiredFlatBars);
    }

    private BigDecimal roundTripCostRate(BigDecimal spreadRate, BigDecimal takerFeeRate) {
        return nonNegative(takerFeeRate).multiply(TWO)
                .add(nonNegative(properties.getMinNetProfitRate()))
                .add(nonNegative(spreadRate));
    }

    private BigDecimal effectiveTakerFeeRate(String contractCode) {
        return feeRateResolver.takerRate(contractCode);
    }

    private static BigDecimal averageRecentTurnover(List<HtxSwapKline> candles, int lookbackBars) {
        if (candles == null || candles.isEmpty()) {
            return null;
        }
        int remaining = Math.max(1, lookbackBars);
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int i = candles.size() - 1; i >= 0 && remaining > 0; i--) {
            HtxSwapKline candle = candles.get(i);
            BigDecimal turnover = candle == null ? null : candle.getTradeTurnover();
            if (positive(turnover)) {
                sum = sum.add(turnover);
                count++;
                remaining--;
            }
        }
        if (count == 0) {
            return null;
        }
        return sum.divide(BigDecimal.valueOf(count), 18, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumTopLevels(List<HtxPriceLevel> levels, int topN) {
        if (levels == null || levels.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int limit = Math.min(Math.max(1, topN), levels.size());
        for (int i = 0; i < limit; i++) {
            HtxPriceLevel level = levels.get(i);
            if (level != null && positive(level.getAmount())) {
                sum = sum.add(level.getAmount());
            }
        }
        return sum;
    }

    private PartialExitDecision partialExitDecision(HtxTrendPositionEntity position, BigDecimal reference,
                                                    BigDecimal currentNet) {
        int stage = position.getPartialExitStage() == null ? 0 : position.getPartialExitStage();
        BigDecimal oneR = rTargetPrice(position, BigDecimal.ONE);
        BigDecimal twoR = rTargetPrice(position, TWO);
        BigDecimal structuralTarget = structuralTargetPrice(position);
        boolean profitable = currentNet != null && currentNet.signum() > 0;
        if (stage < 1 && profitable && ((positive(oneR) && targetReached(position.getSide(), reference, oneR))
                || (positive(structuralTarget) && targetReached(position.getSide(), reference, structuralTarget)))) {
            return PartialExitDecision.trigger(1, properties.getPartialTakeProfitFirstRate(),
                    "1R/structure target reached oneR=" + priceText(oneR) + " structure=" + priceText(structuralTarget));
        }
        if (stage < 2 && profitable && positive(twoR) && targetReached(position.getSide(), reference, twoR)) {
            return PartialExitDecision.trigger(2, properties.getPartialTakeProfitSecondRate(),
                    "2R target reached twoR=" + priceText(twoR));
        }
        return PartialExitDecision.inactive(stage);
    }

    private boolean trailingExitAllowed(HtxTrendPositionEntity position, BigDecimal reference) {
        if (position == null) {
            return false;
        }
        return HtxTrendExitRules.trailingExitAllowed(position.getSide(), position.getEntryAvgPrice(),
                position.getStopPrice(), reference, nonNegative(properties.getTrailingActivationR()),
                properties.isRunnerTrailingRequireTimeframeExhaustion(), majorTimeframeOpposition(position),
                properties.getRunnerTimeframeMajorOppositeCount());
    }

    /**
     * The model-routed engine is deliberately isolated to Fibonacci positions. Other model types keep their legacy exits
     * while the same schema can accumulate observations for their eventual policies.
     */
    private HtxTrendExitDecisionEngine.Decision exitEngineDecision(HtxTrendPositionEntity position, TrendSignal signal,
                                                                    BigDecimal reference, BigDecimal highest,
                                                                    BigDecimal lowest, BigDecimal protectionPrice,
                                                                    ExitSnapshot snapshot, HoldingAssessment assessment) {
        if (!properties.isExitEngineEnabled() || position == null
                || !HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION.equals(position.getPrimaryModel())) {
            return null;
        }
        BigDecimal structuralTarget = structuralTargetPrice(position);
        int majorOpposition = majorTimeframeOpposition(position);
        boolean marketOpposes = againstBroadMarket(LONG.equals(position.getSide()) ? TrendSide.LONG : TrendSide.SHORT,
                marketBreadthContext());
        BigDecimal estimatedCloseFee = estimatedCloseFee(position, reference);
        int requiredFlatBars = Math.max(1, properties.getFlatLossExitConfirmationBars());
        int flatStreak = (int) countConsecutiveFlatSignals(position, requiredFlatBars);
        int requiredProtectionBreachBars = Math.max(1, properties.getIntradayProfitProtectionBreachConfirmationBars());
        int protectionBreachBars = (int) recorder.countConsecutiveProtectionBreaches(position.getContractCode(),
                properties.getPeriod(), position.getSide(), protectionPrice,
                position.getTrailingArmedAt() == null ? position.getCreatedAt() : position.getTrailingArmedAt(),
                requiredProtectionBreachBars);
        TrendSide positionSide = LONG.equals(position.getSide()) ? TrendSide.LONG : TrendSide.SHORT;
        // Pass the raw funding observation into v4.  Do not call the legacy exit-reason formatter here:
        // v4 owns the action and emits its own category and audit evidence.
        String fundingCostGate = fundingCostGate(position.getContractCode(), positionSide,
                properties.getMaxHoldFundingCostRate());
        return exitDecisionEngine.evaluate(new HtxTrendExitDecisionEngine.Input(position.getPrimaryModel(), position.getSide(),
                remainingContractVolume(position), position.getContractSize(), position.getEntryAvgPrice(), position.getStopPrice(),
                position.getInvalidationPrice(), structuralTarget, reference, protectionPrice, highest, lowest, signal.atr(),
                signal.side(), signal.emaFast(), signal.emaSlow(), majorOpposition, marketOpposes, snapshot.currentNet(),
                exitEngineEntryFee(position), estimatedCloseFee, position.getTrailingArmedAt(), position.getTrailingArmReason(),
                position.getMaxFavorableR(), LocalDateTime.now(), flatStreak, requiredFlatBars, flatLossBudget(position),
                new HtxTrendExitDecisionEngine.ProfitProtectionPolicy(
                        properties.getIntradayProfitArmAtrMultiplier(), properties.getIntradayProfitLockAtrMultiplier(),
                        properties.getIntradayProfitPartialAtrMultiplier(), properties.getIntradayProfitPullbackAtrMultiplier(),
                        properties.getIntradayProfitMinNetFeeMultiple(), requiredProtectionBreachBars), protectionBreachBars)
                .withFundingCostExitReason(fundingCostGate)
                .withAssessmentState(assessment == null ? null : assessment.state(),
                        assessment == null ? null : assessment.previousState(),
                        assessment == null ? null : assessment.key())
                .withObservedMfeNetPnl(snapshot == null ? null : snapshot.mfe()));
    }

    /** A durable profit lock may tighten a freshly calculated ATR stop but can never loosen it. */
    private static BigDecimal exitEngineProtectionPrice(HtxTrendPositionEntity position, BigDecimal calculatedProtection) {
        if (position == null) {
            return calculatedProtection;
        }
        BigDecimal protection = tighterExitProtection(position.getSide(), calculatedProtection, position.getProfitLockPrice());
        // The MFE recommendation is persisted independently so a restart must not discard a stricter lock.
        return tighterExitProtection(position.getSide(), protection, position.getProfitProtectionPrice());
    }

    /** The MFE planner and the exit engine share one non-loosening protection boundary. */
    private static BigDecimal tighterExitProtection(String side, BigDecimal current, BigDecimal candidate) {
        if (!positive(current)) {
            return candidate;
        }
        if (!positive(candidate)) {
            return current;
        }
        return LONG.equals(side) ? max(current, candidate) : min(current, candidate);
    }

    private void persistExitEngineState(HtxTrendPositionEntity position, HtxTrendExitDecisionEngine.Decision decision) {
        if (position == null || position.getId() == null || decision == null) {
            return;
        }
        recorder.updateExitEngineState(position.getId(), decision.phase(), decision.trailingArmedAt(),
                decision.trailingArmReason(), decision.maxFavorableR(), decision.protectionPrice(),
                HtxTrendExitDecisionEngine.VERSION);
    }

    /**
     * Writes one immutable, completed-bar mark-to-market fact for every active position.  It intentionally records
     * both live and observe-only decisions so later replay can separate model quality from execution policy.
     */
    private void recordPositionMarkSnapshot(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference,
                                            BigDecimal protectionPrice, ExitSnapshot snapshot,
                                            HtxTrendExitDecisionEngine.Decision decision, HoldingAssessment holding) {
        if (position == null || position.getId() == null || signal == null || signal.barTime() == null
                || !positive(reference) || snapshot == null) {
            return;
        }
        HtxTrendPositionMarkSnapshotEntity mark = new HtxTrendPositionMarkSnapshotEntity();
        mark.setSnapshotKey("position-mark-v1:" + position.getId() + ":" + signal.barTime());
        mark.setPositionId(position.getId());
        mark.setRunId(position.getRunId());
        mark.setAccountId(position.getAccountId());
        mark.setAccountLabel(assessmentAccountLabel(position));
        mark.setContractCode(position.getContractCode());
        mark.setSide(position.getSide());
        mark.setPrimaryModel(position.getPrimaryModel());
        mark.setTimeframeProfile(position.getTimeframeProfile());
        mark.setBarTime(signal.barTime());
        mark.setReferencePrice(reference);
        mark.setUnrealizedNetPnl(snapshot.currentNet());
        mark.setMfeNetPnl(snapshot.mfe());
        mark.setMaeNetPnl(snapshot.mae());
        mark.setGivebackRate(snapshot.giveback());
        mark.setProtectionPrice(decision == null ? protectionPrice : decision.protectionPrice());
        mark.setTrendState(holding == null ? null : holding.state());
        mark.setExitPhase(decision == null ? position.getExitPhase() : decision.phase());
        mark.setSuggestedAction(decision == null ? "LEGACY_HOLD" : decision.action().name());
        mark.setExecutionMode(decision == null ? "LEGACY_PATH"
                : exitEngineLiveFor(position) ? "EXIT_ENGINE_INPUT" : "OBSERVE_ONLY");
        mark.setEvidenceJson(decision == null ? "{\"schema\":\"position-mark-v1\",\"signal\":\""
                + signal.side() + "\",\"reason\":\"" + jsonEscape(signal.reason()) + "\"}"
                : decision.evidenceJson());
        recorder.recordPositionMark(mark);
    }

    private void recordExitEngineDecision(HtxTrendPositionEntity position, BigDecimal reference, BigDecimal protectionPrice,
                                          ExitSnapshot snapshot, HtxTrendExitDecisionEngine.Decision decision,
                                          HtxTrendProfitProtectionPlanner.Plan profitProtectionPlan,
                                          String executedAction, String executionBlockReason) {
        if (position == null || position.getId() == null || decision == null) {
            return;
        }
        HtxTrendExitDecisionEntity entity = new HtxTrendExitDecisionEntity();
        entity.setDecisionKey(position.getId() + ":" + UUID.randomUUID());
        entity.setPositionId(position.getId());
        entity.setRunId(position.getRunId());
        entity.setAccountId(position.getAccountId());
        entity.setAccountLabel(position.getAccountLabel() == null ? "primary" : position.getAccountLabel());
        entity.setContractCode(position.getContractCode());
        entity.setSide(position.getSide());
        entity.setPrimaryModel(position.getPrimaryModel());
        entity.setEngineVersion(HtxTrendExitDecisionEngine.VERSION);
        entity.setExitPhase(decision.phase());
        entity.setSuggestedAction(decision.action().name());
        entity.setExecutedAction(executedAction == null ? "NOT_EXECUTED" : executedAction);
        entity.setExecutionBlockReason(executionBlockReason);
        entity.setReferencePrice(reference);
        entity.setProtectionPrice(decision.protectionPrice() == null ? protectionPrice : decision.protectionPrice());
        entity.setExpectedTargetPrice(structuralTargetPrice(position));
        entity.setCurrentNetPnl(snapshot.currentNet());
        entity.setMfeNetPnl(snapshot.mfe());
        entity.setMaeNetPnl(snapshot.mae());
        entity.setGivebackRate(snapshot.giveback());
        entity.setMaxFavorableR(decision.maxFavorableR());
        if (profitProtectionPlan != null) {
            entity.setProfitProtectionMfePrice(profitProtectionPlan.mfePrice());
            entity.setProjectedLockedNetPnl(profitProtectionPlan.projectedLockedNetPnl());
            entity.setMfeCaptureRatio(profitProtectionPlan.mfeCaptureRatio());
            entity.setProtectionMode(profitProtectionPlan.mode());
            entity.setExecutionReadiness("OBSERVE".equals(executedAction) ? "OBSERVE_ONLY" : "EXIT_ENGINE_INPUT");
        }
        entity.setTargetRemainingContracts(decision.targetRemainingContracts() < 0 ? null
                : decision.targetRemainingContracts());
        entity.setEvidenceJson(decision.evidenceJson());
        recorder.recordExitDecision(entity);
    }

    /** Records a restart-safe MFE lock; the v4 exit engine consumes this price, but this component never submits orders itself. */
    private HtxTrendProfitProtectionPlanner.Plan observeProfitProtection(HtxTrendPositionEntity position,
                                                                           BigDecimal reference, BigDecimal highest,
                                                                           BigDecimal lowest, TrendSignal signal) {
        if (!properties.isProfitProtectionEnabled() || position == null || position.getId() == null
                || !HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION.equals(position.getPrimaryModel())) {
            return null;
        }
        HtxTrendProfitProtectionPlanner.Plan plan = profitProtectionPlanner.evaluate(new HtxTrendProfitProtectionPlanner.Input(
                position.getSide(), remainingContractVolume(position), position.getContractSize(), position.getEntryAvgPrice(),
                highest, lowest, reference, signal.atr(), exitEngineEntryFee(position), estimatedCloseFee(position, reference),
                position.getProfitProtectionPrice(), new HtxTrendProfitProtectionPlanner.Policy(
                properties.getIntradayProfitArmAtrMultiplier(), properties.getIntradayProfitLockAtrMultiplier(),
                properties.getIntradayProfitProtectionNoiseAtrMultiplier(), properties.getIntradayProfitMfeLockRatio(),
                properties.getIntradayProfitMinNetFeeMultiple())));
        if (!plan.armed()) {
            return plan;
        }
        boolean firstArm = !positive(position.getProfitProtectionPrice());
        boolean ratcheted = !firstArm && tighterProtection(position.getSide(), plan.protectionPrice(),
                position.getProfitProtectionPrice());
        recorder.updateProfitProtectionState(position.getId(), plan.mfePrice(), plan.mfeNetPnl(), plan.protectionPrice(),
                plan.projectedLockedNetPnl(), plan.mfeCaptureRatio(), plan.mode());
        if (firstArm || ratcheted) {
            HtxTrendProfitProtectionEventEntity event = new HtxTrendProfitProtectionEventEntity();
            event.setEventKey(position.getId() + ":" + UUID.randomUUID());
            event.setPositionId(position.getId());
            event.setRunId(position.getRunId());
            event.setAccountId(position.getAccountId());
            event.setAccountLabel(position.getAccountLabel() == null ? "primary" : position.getAccountLabel());
            event.setContractCode(position.getContractCode());
            event.setSide(position.getSide());
            event.setPrimaryModel(position.getPrimaryModel());
            event.setEventType(firstArm ? "ARMED" : "RATCHETED");
            event.setProtectionMode(plan.mode());
            event.setReferencePrice(reference);
            event.setMfePrice(plan.mfePrice());
            event.setMfeNetPnl(plan.mfeNetPnl());
            event.setProtectionPrice(plan.protectionPrice());
            event.setProjectedLockedNetPnl(plan.projectedLockedNetPnl());
            event.setMfeCaptureRatio(plan.mfeCaptureRatio());
            event.setExecutionReadiness("EXIT_ENGINE_INPUT");
            event.setReason("MFE protection price is consumed by the v4 exit engine; this recorder submits no standalone HTX order");
            event.setEvidenceJson(profitProtectionEvidence(plan));
            recorder.recordProfitProtectionEvent(event);
        }
        return plan;
    }

    private static boolean tighterProtection(String side, BigDecimal candidate, BigDecimal current) {
        if (!positive(candidate) || !positive(current)) {
            return false;
        }
        return LONG.equals(side) ? candidate.compareTo(current) > 0 : candidate.compareTo(current) < 0;
    }

    private static String profitProtectionEvidence(HtxTrendProfitProtectionPlanner.Plan plan) {
        return "{\"schema\":\"trend-profit-protection-v1\",\"armed\":" + plan.armed()
                + ",\"mfePrice\":" + fmt(plan.mfePrice()) + ",\"mfeNetPnl\":" + fmt(plan.mfeNetPnl())
                + ",\"costFloorPrice\":" + fmt(plan.costFloorPrice()) + ",\"mfeProtectionPrice\":"
                + fmt(plan.mfeProtectionPrice()) + ",\"protectionPrice\":" + fmt(plan.protectionPrice())
                + ",\"projectedLockedNetPnl\":" + fmt(plan.projectedLockedNetPnl()) + ",\"mfeCaptureRatio\":"
                + fmt(plan.mfeCaptureRatio()) + ",\"allowedGiveback\":" + fmt(plan.allowedGiveback())
                + ",\"mode\":\"" + plan.mode() + "\"}";
    }

    private static String exitEngineCategory(HtxTrendExitDecisionEngine.Decision decision) {
        if (decision == null) {
            return EXIT_TREND_EXHAUSTION;
        }
        if ("INVALIDATED".equals(decision.phase())) {
            return EXIT_MODEL_INVALIDATION;
        }
        if ("FUNDING_COST".equals(decision.phase())) {
            return EXIT_FUNDING_COST;
        }
        return "REVERSAL_CONFIRMED".equals(decision.phase()) || "THESIS_EXPIRED".equals(decision.phase())
                ? EXIT_TREND_EXHAUSTION : EXIT_TRAILING_STOP;
    }

    private boolean exitEngineLiveFor(HtxTrendPositionEntity position) {
        if (!properties.isExitEngineEnabled() || !properties.isExitEngineLiveEnabled() || position == null) {
            return false;
        }
        return !properties.isExitEnginePrimaryAccountOnly() || "primary".equalsIgnoreCase(position.getAccountLabel());
    }

    private String exitEngineBlockReason(HtxTrendPositionEntity position) {
        if (!properties.isExitEngineLiveEnabled()) {
            return "exit-engine-live-disabled";
        }
        if (properties.isExitEnginePrimaryAccountOnly()
                && (position == null || !"primary".equalsIgnoreCase(position.getAccountLabel()))) {
            return "exit-engine-primary-account-only";
        }
        return "exit-engine-not-eligible";
    }

    private PartialExitDecision dynamicPartialExitDecision(HtxTrendPositionEntity position,
                                                            HtxTrendExitDecisionEngine.Decision decision) {
        long remaining = remainingContractVolume(position);
        long targetRemaining = decision == null ? -1L : decision.targetRemainingContracts();
        if (targetRemaining < 0L || targetRemaining >= remaining || remaining <= 1L) {
            return PartialExitDecision.inactive(position == null || position.getPartialExitStage() == null
                    ? 0 : position.getPartialExitStage());
        }
        BigDecimal rate = BigDecimal.valueOf(remaining - targetRemaining).divide(BigDecimal.valueOf(remaining), 12,
                RoundingMode.DOWN);
        int stage = Math.min(2, (position.getPartialExitStage() == null ? 0 : position.getPartialExitStage()) + 1);
        return PartialExitDecision.trigger(stage, rate, "dynamic remaining=" + targetRemaining + " reason=" + decision.reason());
    }

    private int majorTimeframeOpposition(HtxTrendPositionEntity position) {
        int opposite = 0;
        for (String period : EXIT_CONFIRMATION_PERIODS) {
            TrendSide side = marketAnchorPeriodSide(position.getContractCode(), period);
            if ((LONG.equals(position.getSide()) && side == TrendSide.SHORT)
                    || (SHORT.equals(position.getSide()) && side == TrendSide.LONG)) {
                opposite++;
            }
        }
        return opposite;
    }

    private static long remainingContractVolume(HtxTrendPositionEntity position) {
        if (position == null) {
            return 0L;
        }
        Long remaining = position.getRemainingContractVolume();
        return remaining != null && remaining > 0L ? remaining
                : position.getContractVolume() == null ? 0L : position.getContractVolume();
    }

    private static long partialCloseVolume(long remaining, BigDecimal rate) {
        return HtxTrendExitRules.partialCloseVolume(remaining, rate);
    }

    private static int nextPartialExitStage(HtxTrendPositionEntity position) {
        int stage = position.getPartialExitStage() == null ? 0 : position.getPartialExitStage();
        return Math.min(2, stage + 1);
    }

    private String exitReason(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference,
                              BigDecimal trailingStop, BigDecimal takeProfit, String stopSource) {
        String side = position.getSide();
        if (LONG.equals(side)) {
            if (reference.compareTo(trailingStop) <= 0 && trailingExitAllowed(position, reference)) {
                return exitEngineReason(EXIT_TRAILING_STOP,
                        "long trailing stop hit ref=" + reference + " stop=" + trailingStop
                                + " stopSource=" + stopSource);
            }
            if (takeProfit != null && reference.compareTo(takeProfit) >= 0) {
                return exitEngineReason(EXIT_TARGET_ZONE,
                        "long take profit hit ref=" + reference + " target=" + takeProfit);
            }
            if (signal.side() == TrendSide.SHORT) {
                return exitEngineReason(EXIT_TREND_EXHAUSTION, "long exit on opposite trend signal");
            }
            return null;
        }
        if (reference.compareTo(trailingStop) >= 0 && trailingExitAllowed(position, reference)) {
            return exitEngineReason(EXIT_TRAILING_STOP,
                    "short trailing stop hit ref=" + reference + " stop=" + trailingStop
                            + " stopSource=" + stopSource);
        }
        if (takeProfit != null && reference.compareTo(takeProfit) <= 0) {
            return exitEngineReason(EXIT_TARGET_ZONE,
                    "short take profit hit ref=" + reference + " target=" + takeProfit);
        }
        if (signal.side() == TrendSide.LONG) {
            return exitEngineReason(EXIT_TREND_EXHAUSTION, "short exit on opposite trend signal");
        }
        return null;
    }


    private List<String> liveContractCodes() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (properties.getContractCodes() != null) {
            for (String code : properties.getContractCodes()) {
                String normalized = normalize(code);
                if (!normalized.isEmpty()) {
                    result.add(normalized);
                }
            }
        }
        String legacy = normalize(properties.getContractCode());
        if (result.isEmpty() && !legacy.isEmpty()) {
            result.add(legacy);
        }
        return new ArrayList<>(result);
    }

    private static RunResult tag(String contractCode, RunResult result) {
        if (result == null) {
            return RunResult.of(RunCode.SKIPPED, null, contractCode + ": null result");
        }
        return RunResult.of(result.code(), result.positionId(), contractCode + ": " + result.message());
    }

    private static boolean opensPosition(RunResult result) {
        return result != null && result.code() == RunCode.ENTRY_SENT;
    }

    private static int rank(RunCode code) {
        if (code == RunCode.ENTRY_SENT || code == RunCode.CLOSE_SENT || code == RunCode.MANUAL_REVIEW
                || code == RunCode.REJECTED) {
            return 4;
        }
        if (code == RunCode.ACTIVE || code == RunCode.DONE || code == RunCode.WAITING) {
            return 3;
        }
        if (code == RunCode.OBSERVED) {
            return 2;
        }
        if (code == RunCode.SKIPPED) {
            return 1;
        }
        return 0;
    }

    private static String gateMessage(String stage, String reason) {
        String safeStage = stage == null ? "gate" : stage;
        String safeReason = reason == null ? "unknown" : reason;
        return safeStage + ": " + safeReason;
    }

    private static String exitEngineReason(String category, String reason) {
        String safeCategory = category == null ? "UNKNOWN" : category;
        String safeReason = reason == null ? "unknown" : reason;
        return gateMessage(EXIT_ENGINE, safeCategory + ": " + safeReason);
    }

    private String configGate(String contractCode) {
        if (!properties.isEnabled()) {
            return gateMessage(MODE_GATE, "trend strategy disabled");
        }
        if (isBlank(contractCode)) {
            return gateMessage(MODE_GATE, "contract code is blank");
        }
        if (!contractProperties.isEnabled()) {
            return gateMessage(MODE_GATE, "contract module disabled");
        }
        if (!properties.isObserveOnly() && marketMakerProperties != null && marketMakerProperties.isEnabled()) {
            return gateMessage(EXECUTION_PREFLIGHT, "trend exclusive mode blocked: market-maker enabled");
        }
        if (!properties.isObserveOnly() && fundingArbitrageProperties != null && fundingArbitrageProperties.isEnabled()) {
            return gateMessage(EXECUTION_PREFLIGHT, "trend exclusive mode blocked: funding arbitrage enabled");
        }
        if (!properties.isObserveOnly() && (contractProperties.isDryRun() || !contractProperties.isLiveEnabled())) {
            return gateMessage(EXECUTION_PREFLIGHT, "trend live mode requires contract dry-run=false and live-enabled=true");
        }
        if (!properties.isObserveOnly()
                && (properties.getLeverage() <= 0 || properties.getLeverage() > contractProperties.getMaxLeverage())) {
            return gateMessage(EXECUTION_PREFLIGHT, "trend leverage outside configured max leverage");
        }
        return null;
    }

    private static boolean filled(HtxSwapOrder order) {
        return order != null && ("filled".equalsIgnoreCase(exchangeState(order)) || Integer.valueOf(6).equals(order.getStatus()))
                && nz(order.getTradeVolume()).signum() > 0;
    }

    private static boolean hasTradeFill(HtxSwapOrder order) {
        return order != null && nz(order.getTradeVolume()).signum() > 0;
    }

    private static String fillStatus(HtxSwapOrder order) {
        return filled(order) ? HtxTrendRecorder.ORDER_FILLED : HtxTrendRecorder.ORDER_PARTIALLY_FILLED;
    }

    private static boolean terminal(HtxSwapOrder order) {
        String state = exchangeState(order);
        return "filled".equalsIgnoreCase(state)
                || "partially_canceled".equalsIgnoreCase(state)
                || "canceled".equalsIgnoreCase(state)
                || "rejected".equalsIgnoreCase(state)
                || Integer.valueOf(6).equals(order.getStatus())
                || Integer.valueOf(5).equals(order.getStatus())
                || Integer.valueOf(7).equals(order.getStatus());
    }

    private static String exchangeState(HtxSwapOrder order) {
        if (order == null) {
            return null;
        }
        if (!isBlank(order.getState())) {
            return order.getState();
        }
        if (Integer.valueOf(6).equals(order.getStatus())) {
            return "filled";
        }
        if (Integer.valueOf(5).equals(order.getStatus())) {
            return "canceled";
        }
        if (Integer.valueOf(7).equals(order.getStatus())) {
            return "rejected";
        }
        return "new";
    }

    private static BigDecimal mid(TopOfBook book) {
        return book.bid().add(book.ask()).divide(TWO, 18, RoundingMode.HALF_UP);
    }

    private BigDecimal scalePrice(BigDecimal value, HtxSwapContractInfo contract) {
        return scalePrice(value, contract, RoundingMode.HALF_UP);
    }

    private BigDecimal scalePrice(BigDecimal value, HtxSwapContractInfo contract, RoundingMode mode) {
        if (value == null) {
            return null;
        }
        return value.setScale(priceScale(contract), mode);
    }

    private int priceScale(HtxSwapContractInfo contract) {
        BigDecimal priceTick = contract == null ? null : contract.getPriceTick();
        if (positive(priceTick)) {
            return Math.max(0, priceTick.stripTrailingZeros().scale());
        }
        return Math.max(0, properties.getPriceScale());
    }

    private String trendPeriod() {
        String configured = properties.getTrendPeriod();
        if (isBlank(configured)) {
            return properties.getPeriod();
        }
        return configured.trim();
    }

    private String positionKey(String contractCode) {
        return properties.getClientOrderIdPrefix() + "-" + contractCode.replace("-", "") + "-" + System.currentTimeMillis();
    }

    private long numericClientOrderId() {
        long random = ThreadLocalRandom.current().nextLong(100L, 999L);
        return (System.currentTimeMillis() % 10_000_000_000L) * 1_000L + random;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static long nonNegativeLong(Long value) {
        return value == null || value < 0L ? 0L : value;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0L || numerator <= 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 18, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(BigDecimal value, long count) {
        if (count <= 0L) {
            return BigDecimal.ZERO;
        }
        return nz(value).divide(BigDecimal.valueOf(count), 18, RoundingMode.HALF_UP);
    }

    private static BigDecimal payoffRatio(BigDecimal averageWin, BigDecimal averageLoss) {
        if (!positive(averageLoss)) {
            return positive(averageWin) ? new BigDecimal("999") : BigDecimal.ONE;
        }
        return nonNegative(averageWin).divide(averageLoss, 18, RoundingMode.HALF_UP);
    }

    private static BigDecimal profitFactor(BigDecimal grossProfit, BigDecimal grossLoss) {
        if (!positive(grossLoss)) {
            return positive(grossProfit) ? new BigDecimal("999") : BigDecimal.ONE;
        }
        return nonNegative(grossProfit).divide(grossLoss, 18, RoundingMode.HALF_UP);
    }

    private static BigDecimal firstPositive(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (positive(value)) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static BigDecimal min(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static BigDecimal min(BigDecimal first, BigDecimal second, BigDecimal third) {
        return min(min(first, second), third);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal minValue, BigDecimal maxValue) {
        BigDecimal result = value == null ? BigDecimal.ONE : value;
        BigDecimal lower = minValue == null ? BigDecimal.ZERO : minValue;
        BigDecimal upper = maxValue == null ? result : maxValue;
        if (upper.compareTo(lower) < 0) {
            upper = lower;
        }
        if (result.compareTo(lower) < 0) {
            return lower;
        }
        if (result.compareTo(upper) > 0) {
            return upper;
        }
        return result;
    }

    private static String error(HtxRestResponse<?> response) {
        if (response == null) {
            return "null response";
        }
        if (!isBlank(response.getErrCode()) || !isBlank(response.getErrMsg())) {
            return response.getErrCode() + ":" + response.getErrMsg();
        }
        if (response.getCode() != null || !isBlank(response.getMessage())) {
            return response.getCode() + ":" + response.getMessage();
        }
        return "status=" + response.getStatus();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        if (current == null || isBlank(current.getMessage())) {
            return throwable == null ? "unknown" : throwable.getClass().getSimpleName();
        }
        return current.getMessage();
    }

    private static String clip(String value) {
        return clip(value, 512);
    }

    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String gateOutcomeSummary() {
        return entryGateOutcomeCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue().get())
                .collect(Collectors.joining(","));
    }

    private record HoldingAssessment(String state, String previousState, String key) {
        static HoldingAssessment unavailable() { return new HoldingAssessment(null, null, null); }
    }

    private record PartialExitDecision(boolean triggered, int stage, BigDecimal rate, String reason) {
        static PartialExitDecision trigger(int stage, BigDecimal rate, String reason) {
            return new PartialExitDecision(true, stage, rate, reason);
        }

        static PartialExitDecision inactive(int stage) {
            return new PartialExitDecision(false, stage, BigDecimal.ZERO, "inactive");
        }

        String summary() {
            return triggered ? "trigger stage=" + stage + " rate=" + rate + " reason=" + reason
                    : "inactive stage=" + stage;
        }
    }

    public enum RunCode {
        OBSERVED, NO_SIGNAL, ENTRY_SENT, ACTIVE, PARTIAL_CLOSE_SENT, CLOSE_SENT, DONE, WAITING, SKIPPED, REJECTED, MANUAL_REVIEW
    }


    private enum CooldownType {
        LOSS("loss"), BREAKEVEN("breakeven"), PROFIT("profit");

        private final String label;

        CooldownType(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private enum MarketRegime {
        BROAD_UP, BROAD_DOWN, MIXED
    }

    private enum QualityRegime {
        WEAK, NEUTRAL, STRONG
    }

    /** Separate from QualityRegime so sparse buckets are never mislabeled as weak strategy evidence. */
    private enum DirectionalQualityRegime {
        INSUFFICIENT_SAMPLE, WEAK, NEUTRAL, STRONG, UNAVAILABLE
    }


    private record CooldownContext(CooldownType type, long durationMs, BigDecimal netProfit) {
    }

    private record MarketBreadthContext(MarketRegime regime, long total, long longCount, long shortCount,
                                        BigDecimal longRatio, BigDecimal shortRatio, String reason) {
        static MarketBreadthContext neutral(String reason) {
            return new MarketBreadthContext(MarketRegime.MIXED, 0L, 0L, 0L,
                    BigDecimal.ZERO, BigDecimal.ZERO, reason);
        }

        String summary() {
            return regime + "(long=" + longCount + "/" + total + "=" + pct(longRatio)
                    + ",short=" + shortCount + "/" + total + "=" + pct(shortRatio)
                    + ",reason=" + reason + ")";
        }
    }

    private record MarketAnchorContext(MarketRegime regime, int validSignals, int longSignals, int shortSignals,
                                       int longSymbols, int shortSymbols, String reason) {
        static MarketAnchorContext neutral(String reason) {
            return new MarketAnchorContext(MarketRegime.MIXED, 0, 0, 0, 0, 0, reason);
        }

        String summary() {
            return regime + "(longSignals=" + longSignals + ",shortSignals=" + shortSignals
                    + ",validSignals=" + validSignals + ",longSymbols=" + longSymbols
                    + ",shortSymbols=" + shortSymbols + ",reason=" + reason + ")";
        }
    }

    private record DirectionalQualityContext(DirectionalQualityRegime regime, long closedCount, long winCount,
                                             long lossCount, BigDecimal profitFactor, BigDecimal payoffRatio,
                                             BigDecimal averageWin, BigDecimal averageLoss, BigDecimal expectancy,
                                             BigDecimal netProfit, String reason) {
        static DirectionalQualityContext unavailable(String reason) {
            return new DirectionalQualityContext(DirectionalQualityRegime.UNAVAILABLE, 0L, 0L, 0L,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, reason);
        }
    }

    private record PerformanceContext(QualityRegime regime, long closedCount, long winCount, long lossCount,
                                      BigDecimal profitFactor, BigDecimal payoffRatio, BigDecimal averageWin,
                                      BigDecimal averageLoss, BigDecimal expectancy, BigDecimal netProfit,
                                      String reason) {
        static PerformanceContext neutral(String reason) {
            return new PerformanceContext(QualityRegime.NEUTRAL, 0L, 0L, 0L, BigDecimal.ONE, BigDecimal.ONE,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, reason);
        }

        String summary() {
            return regime + "(closed=" + closedCount + ",wins=" + winCount + ",losses=" + lossCount
                    + ",pf=" + profitFactor.setScale(2, RoundingMode.HALF_UP)
                    + ",payoff=" + payoffRatio.setScale(2, RoundingMode.HALF_UP)
                    + ",avgWin=" + averageWin.setScale(4, RoundingMode.HALF_UP)
                    + ",avgLoss=" + averageLoss.setScale(4, RoundingMode.HALF_UP)
                    + ",expectancy=" + expectancy.setScale(4, RoundingMode.HALF_UP)
                    + ",net=" + netProfit.setScale(4, RoundingMode.HALF_UP)
                    + ",reason=" + reason + ")";
        }
    }

    private record SizingContext(BigDecimal multiplier, String reason) {
        String summary() {
            return "sizingMultiplier=" + multiplier.setScale(2, RoundingMode.HALF_UP) + " " + reason;
        }
    }

    private record EntryCostContext(BigDecimal estimatedEntryFee, BigDecimal estimatedCloseFee,
                                    BigDecimal expectedNetAfterFees, String entryGateReason) {
    }

    private record DirectionConfidence(BigDecimal score, BigDecimal requiredScore, String reason) {
    }

    private static String pct(BigDecimal ratio) {
        return ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private static String fmt(BigDecimal value) {
        if (value == null) {
            return "NA";
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public record RunResult(RunCode code, Long positionId, String message) {
        public static RunResult of(RunCode code, Long positionId, String message) {
            return new RunResult(code, positionId, message);
        }
    }

    private record SignalSnapshot(TrendSignal signal, List<HtxSwapKline> entryCandles, List<HtxSwapKline> trendCandles) {
    }

    private record CandidateScanResult(String contractCode, CandidateEvaluation evaluation) {
    }

    private record CandidateEvaluation(RunResult result, EntryCandidate candidate) {
    }

    private record FactorSnapshotCaptureResult(String contractCode, int availablePeriods, int requestedPeriods,
                                               int modelCoverageOutcomes) {
    }

    private record RuntimeKlineWindow(String period, Long targetBarTime, List<HtxSwapKline> candles,
                                      int expectedWindowSize) {
    }

    private record FactorSnapshotCaptureSummary(int contracts, int completeContracts, int availablePeriods,
                                                int requestedPeriods, int modelCoverageOutcomes,
                                                long durationMs, String status) {
        static FactorSnapshotCaptureSummary notRun() {
            return new FactorSnapshotCaptureSummary(0, 0, 0, 0, 0, 0L, "not-run");
        }

        static FactorSnapshotCaptureSummary skipped(String reason) {
            return new FactorSnapshotCaptureSummary(0, 0, 0, 0, 0, 0L, "skipped:" + reason);
        }

        static FactorSnapshotCaptureSummary from(List<FactorSnapshotCaptureResult> results, int requestedPeriods) {
            int contracts = results == null ? 0 : results.size();
            int completeContracts = 0;
            int availablePeriods = 0;
            int modelCoverageOutcomes = 0;
            if (results != null) {
                for (FactorSnapshotCaptureResult result : results) {
                    if (result == null) {
                        continue;
                    }
                    availablePeriods += result.availablePeriods();
                    modelCoverageOutcomes += result.modelCoverageOutcomes();
                    if (result.availablePeriods() == requestedPeriods) {
                        completeContracts++;
                    }
                }
            }
            return new FactorSnapshotCaptureSummary(contracts, completeContracts, availablePeriods,
                    contracts * requestedPeriods, modelCoverageOutcomes, 0L, "complete");
        }

        FactorSnapshotCaptureSummary withDuration(long durationMs) {
            return new FactorSnapshotCaptureSummary(contracts, completeContracts, availablePeriods,
                    requestedPeriods, modelCoverageOutcomes, durationMs, status);
        }

        String summary() {
            return status + " contracts=" + contracts + " completeContracts=" + completeContracts
                    + " periods=" + availablePeriods + "/" + requestedPeriods
                    + " modelCoverageOutcomes=" + modelCoverageOutcomes + " durationMs=" + durationMs;
        }
    }

    private record EntryCandidate(String contractCode, TrendSignal signal, List<HtxSwapKline> entryCandles,
                                  HtxSwapContractInfo contract, BigDecimal score, CombinationContext combination) {
    }

    /** Current-candidate evidence used for real entry eligibility and sizing; never derived from recent PnL. */
    private record CandidateTrendState(String regime, boolean tradable, BigDecimal score, BigDecimal rawScore,
                                       HtxTrendStateEvaluator.EntryScoreBreakdown breakdown, String reason) {
        static CandidateTrendState legacy() {
            HtxTrendStateEvaluator.EntryScoreBreakdown breakdown = new HtxTrendStateEvaluator.EntryScoreBreakdown(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO);
            return new CandidateTrendState("LEGACY", true, BigDecimal.ZERO, BigDecimal.ZERO, breakdown,
                    "no combination candidate evidence");
        }
        boolean strong() { return "STRONG".equals(regime); }
    }

    private record CombinationContext(HtxTrendCombinationService.Selection selection, BigDecimal signalVolumeRatio) {
        String runId() { return selection.runId(); }
        String primaryModel() { return selection.candidate().primaryModel(); }
        String confirmationModels() { return selection.candidate().confirmationModels(); }
        String policyVersion() { return selection.policy().getPolicyVersion(); }
        String marketRegime() { return selection.candidate().marketRegime(); }
        Long factorSnapshotId() { return selection.factorSnapshotId(); }
        String hypothesis() { return selection.candidate().hypothesis(); }
        BigDecimal invalidationPrice() { return selection.candidate().invalidationPrice(); }
        BigDecimal plannedNotionalUsdt() { return selection.plannedNotionalUsdt(); }
        BigDecimal targetZoneLow() { return selection.candidate().targetZoneLow(); }
        BigDecimal targetZoneHigh() { return selection.candidate().targetZoneHigh(); }
        BigDecimal expectedRMultiple() { return selection.candidate().expectedRMultiple(); }
        BigDecimal momentumQualityScore() { return selection.candidate().momentumQualityScore(); }
        String structureKey(String contractCode) {
            HtxTrendCombinationCalculator.FibonacciEvidence fib = selection.candidate().fibonacciEvidence();
            if (!HtxTrendStructureLifecycleService.FIB_PULLBACK_CONTINUATION.equals(primaryModel())
                    || fib == null || fib.breakoutBarTime() == null) {
                return null;
            }
            return HtxTrendStructureLifecycleService.entryCycleKey(contractCode,
                    selection.candidate().direction().name(), primaryModel(), runId());
        }
    }

    private record TopOfBook(BigDecimal bid, BigDecimal ask) {
    }

    private record FundingMarket(BigDecimal fundingRate) {
    }

    private record ExitSnapshot(String state, BigDecimal currentNet, BigDecimal mfe, BigDecimal mae,
                                BigDecimal giveback, long bars, String candidates) {
        private String summary() {
            return "exitSnapshot state=" + state
                    + " net=" + fmt(currentNet)
                    + " mfe=" + fmt(mfe)
                    + " mae=" + fmt(mae)
                    + " giveback=" + pct(nonNegative(giveback))
                    + " bars=" + bars
                    + " candidates=[" + candidates + "]";
        }
    }

    private record StopContext(BigDecimal effectiveStop, String stopSource, BigDecimal atrStop,
                               BigDecimal structureStopCandidate) {
        private StopContext withEffectiveStop(BigDecimal stop, String source) {
            return new StopContext(stop, source, atrStop, structureStopCandidate);
        }

        private String summary() {
            return "stopSource=" + stopSource
                    + " atrStop=" + atrStop
                    + " structureStopCandidate=" + (structureStopCandidate == null ? "NA" : structureStopCandidate);
        }
    }

    private record QueryOrderResult(boolean found, HtxSwapOrder order, String message) {
        static QueryOrderResult found(HtxSwapOrder order) {
            return new QueryOrderResult(true, order, null);
        }

        static QueryOrderResult failed(String message) {
            return new QueryOrderResult(false, null, message);
        }
    }

    /** Separates an authoritative empty HTX position list from a failed HTX query. */
    private record ExchangePositionLookup(boolean available, Optional<HtxSwapPosition> position, String reason) {
        static ExchangePositionLookup available(Optional<HtxSwapPosition> position) {
            return new ExchangePositionLookup(true, position == null ? Optional.empty() : position, null);
        }

        static ExchangePositionLookup unavailable(String reason) {
            return new ExchangePositionLookup(false, Optional.empty(), reason == null ? "unknown" : reason);
        }
    }
}
