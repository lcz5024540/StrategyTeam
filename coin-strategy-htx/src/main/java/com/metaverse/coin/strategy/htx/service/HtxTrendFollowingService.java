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
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

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
    private static final List<String> EXIT_CONFIRMATION_PERIODS = List.of("30min", "60min", "4hour");

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
    private HtxTrendCombinationService combinationService;
    private final Map<String, EntryCandidate> pendingEntryCandidates = new ConcurrentHashMap<>();
    private volatile long lastCandidateScanCloseEpoch = Long.MIN_VALUE;

    @Autowired
    public HtxTrendFollowingService(HtxUsdtSwapApiService api,
                                    HtxContractProperties contractProperties,
                                    HtxFundingArbitrageProperties fundingArbitrageProperties,
                                    HtxMarketMakerProperties marketMakerProperties,
                                    HtxTrendProperties properties,
                                    HtxTrendRecorder recorder,
                                    HtxTrendMirrorExecutor mirrorExecutor,
                                    HtxTrendEnsembleShadowProperties ensembleProperties) {
        this(api.getSwapService(), contractProperties, fundingArbitrageProperties, marketMakerProperties,
                properties, recorder, new HtxTrendSignalCalculator(), ensembleProperties,
                new HtxTrendEnsembleSignalCalculator(), mirrorExecutor);
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
                properties::getFeeRateCacheMs, "primary");
    }

    public RunResult runOnce() {
        return runOnce(true);
    }

    @Autowired(required = false)
    void setCombinationService(HtxTrendCombinationService combinationService) {
        this.combinationService = combinationService;
    }

    public boolean hasOpenLikePositions() {
        return recorder.countOpenLike() > 0L;
    }

    RunResult runOnce(boolean entryWindowAllowsNewEntries) {
        List<String> contractCodes = liveContractCodes();
        if (contractCodes.isEmpty()) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(MODE_GATE, "trend contract list is blank"));
        }
        long candidateScanCloseEpoch = currentStableCandleCloseEpoch();
        boolean scanEntryCandidates = markCandidateScanStarted(candidateScanCloseEpoch);
        long openLikePositions = recorder.countOpenLike();
        int maxActivePositions = Math.max(0, properties.getMaxActivePositions());
        MarketBreadthContext marketBreadth = marketBreadthContext();
        MarketAnchorContext marketAnchor = MarketAnchorContext.neutral("not evaluated");
        PerformanceContext performance = performanceContext();
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
        if (!rankedCandidates.isEmpty()) {
            if (!properties.isEntryEnabled()) {
                RunResult blocked = RunResult.of(RunCode.SKIPPED, null,
                        gateMessage(EXECUTION_PREFLIGHT, "new trend entries disabled"));
                selected = select(selected, tag("ENTRY", blocked));
                summaries.add("ENTRY=" + blocked.code() + ":" + blocked.message());
            } else if (!entryWindowAllowsNewEntries && !hasFreshCarryoverCandidate) {
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
                            candidate.entryCandles(), candidate.contract(), marketBreadth, marketAnchor, performance,
                            candidate.combination());
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

        if (selected == null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(CANDIDATE_SCAN, "no trend contracts evaluated"));
        }
        return RunResult.of(selected.code(), selected.positionId(), selected.message()
                + "; market=" + marketBreadth.summary()
                + "; anchor=" + marketAnchor.summary()
                + "; quality=" + performance.summary()
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
            if (signal.barTime() != null && signal.barTime() > 0L) {
                available++;
            }
        }
        combinationService.recordMultiTimeframeSnapshot(contractCode, properties.getPeriod(), entryBarTime,
                marketBreadth == null ? "UNKNOWN" : marketBreadth.regime().name(), signals);
        return new FactorSnapshotCaptureResult(contractCode, available, periods.size());
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
        HtxSwapContractInfo contract = loadContract(contractCode);
        if (contract == null || !positive(contract.getContractSize())) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(EXECUTION_PREFLIGHT, "contract info unavailable"));
        }
        SignalSnapshot snapshot = loadSignal(contractCode);
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
                snapshot.entryCandles(), ensemble);
        if (!selection.accepted()) {
            recordSignal(contractCode, snapshot.signal());
            return new CandidateEvaluation(RunResult.of(RunCode.NO_SIGNAL, null, selection.rejection()), null);
        }
        TrendSide side = trendSide(selection.candidate().direction());
        TrendSignal signal = TrendSignal.of(side, ensemble.barTime(), ensemble.closePrice(), ensemble.emaFast(),
                ensemble.emaSlow(), ensemble.breakoutHigh(), ensemble.breakoutLow(), ensemble.atr(), ensemble.atrPct(),
                ensemble.adx(), selection.candidate().hypothesis() + "; policy=" + selection.policy().getPolicyCode());
        recordSignal(contractCode, signal);
        BigDecimal score = selection.candidate().score();
        CombinationContext context = new CombinationContext(selection);
        EntryCandidate candidate = new EntryCandidate(contractCode, signal, snapshot.entryCandles(), contract, score, context);
        return new CandidateEvaluation(RunResult.of(RunCode.OBSERVED, null,
                "combination candidate queued policy=" + selection.policy().getPolicyCode() + " score=" + score), candidate);
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
                                                    MarketBreadthContext marketBreadth, MarketAnchorContext marketAnchor,
                                                    PerformanceContext performance) {
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
        if (performance != null && performance.regime() == QualityRegime.STRONG) {
            score = score.add(new BigDecimal("5"));
            reason.append(" quality+");
        } else if (performance != null && performance.regime() == QualityRegime.WEAK) {
            score = score.subtract(new BigDecimal("10"));
            reason.append(" quality-");
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
                                     MarketAnchorContext marketAnchor, PerformanceContext performance,
                                     CombinationContext combination) {
        if (signal.side() == TrendSide.FLAT) {
            return RunResult.of(RunCode.NO_SIGNAL, null, signal.reason());
        }
        if (properties.isObserveOnly()) {
            return RunResult.of(RunCode.OBSERVED, null, gateMessage(MODE_GATE, "observe-only signal=" + signal.side()
                    + "; live entry gates bypassed reason=" + signal.reason()));
        }
        String fundingCostGate = fundingCostGate(contractCode, signal.side(), properties.getMaxEntryFundingCostRate());
        if (fundingCostGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, fundingCostGate));
        }
        String freshnessGate = entrySignalFreshnessGate(signal);
        if (freshnessGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, freshnessGate));
        }
        String cooldownGate = cooldownGate(contractCode, signal, marketBreadth);
        if (cooldownGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, cooldownGate));
        }
        DirectionConfidence confidence = directionConfidence(signal, entryCandles, marketBreadth, marketAnchor, performance);
        String directionGate = directionConfidenceGate(signal, confidence);
        if (directionGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, directionGate));
        }
        HtxDepth depth = depthSnapshot(contractCode);
        TopOfBook book = topOfBook(depth);
        if (book == null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, "depth unavailable"));
        }
        BigDecimal spreadRate = book.ask().subtract(book.bid()).divide(mid(book), 18, RoundingMode.HALF_UP);
        if (spreadRate.compareTo(properties.getMaxEntrySpreadRate()) > 0) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, "spread too wide: " + spreadRate));
        }
        String openOrderGate = openOrderGate(contractCode);
        if (openOrderGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, openOrderGate));
        }
        String externalPositionGate = externalPositionGate(contractCode);
        if (externalPositionGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, externalPositionGate));
        }
        BigDecimal entryPrice = signal.side() == TrendSide.LONG ? book.ask() : book.bid();
        String chaseGate = entryChaseGate(signal, entryPrice);
        if (chaseGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, chaseGate));
        }
        SizingContext sizing = sizingContext(signal.side(), marketBreadth, performance, confidence);
        long volume = combination == null ? targetVolume(entryPrice, signal.atr(), contract.getContractSize(), sizing)
                : targetVolumeForNotional(entryPrice, contract.getContractSize(), combination.plannedNotionalUsdt());
        if (volume <= 0) {
            return RunResult.of(RunCode.SKIPPED, null,
                    gateMessage(ENTRY_GATE, "target volume below minimum " + sizing.summary()));
        }
        String liquidityGate = liquidityGate(signal, entryCandles, depth, volume);
        if (liquidityGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, liquidityGate));
        }
        BigDecimal takerFeeRate = effectiveTakerFeeRate(contractCode);
        String expectedMoveGate = expectedMoveGate(signal, entryPrice, spreadRate, takerFeeRate);
        if (expectedMoveGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(ENTRY_GATE, expectedMoveGate));
        }
        EntryCostContext entryCost = entryCostContext(signal, entryPrice, spreadRate, contract.getContractSize(),
                volume, takerFeeRate, confidence);
        BigDecimal notional = BigDecimal.valueOf(volume).multiply(contract.getContractSize()).multiply(entryPrice);
        String marginGate = marginGate(contractCode, notional);
        if (marginGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(EXECUTION_PREFLIGHT, marginGate));
        }
        String leverageGate = switchLeverage(contractCode, signal.side());
        if (leverageGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, gateMessage(EXECUTION_PREFLIGHT, leverageGate));
        }
        String side = signal.side() == TrendSide.LONG ? LONG : SHORT;
        BigDecimal stopPrice = combination == null ? initialStop(signal, side, contract)
                : scalePrice(combination.invalidationPrice(), contract);
        BigDecimal takeProfitPrice = initialTakeProfit(signal, side, contract);
        String positionKey = positionKey(contractCode);
        String clientOrderId = Long.toString(numericClientOrderId());
        HtxTrendPositionEntity entity = new HtxTrendPositionEntity();
        entity.setPositionKey(positionKey);
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
        if (combination != null) {
            entity.setPrimaryModel(combination.primaryModel());
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
        entity.setEstimatedEntryFee(entryCost.estimatedEntryFee());
        entity.setEstimatedCloseFee(entryCost.estimatedCloseFee());
        entity.setExpectedNetAfterFees(entryCost.expectedNetAfterFees());
        entity.setSizingMultiplier(sizing.multiplier());
        entity.setSizingReason(clip(sizing.reason()));
        entity.setEntryGateReason(clip(entryCost.entryGateReason(), 1024));
        long id = recorder.createIntent(entity);
        entity.setId(id);
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
            return RunResult.of(RunCode.REJECTED, id, rejectionReason);
        }
        recorder.markOrderAccepted(id, HtxTrendRecorder.LEG_ENTRY, clientOrderId, orderId,
                accepted.getClientOrderId(), "entry accepted by exchange");
        recorder.markEntryAccepted(id, orderId, "entry sent side=" + side + " volume=" + volume);
        log.info("[HTX-TREND] entry sent id={} contract={} side={} orderId={} volume={} close={} atr={} adx={} feeRate={} reason={} sizing={}",
                id, contractCode, side, orderId, volume, signal.closePrice(), signal.atr(), signal.adx(),
                takerFeeRate, signal.reason(), sizing.summary());
        return RunResult.of(RunCode.ENTRY_SENT, id, "entry order sent " + sizing.summary());
    }

    private RunResult advance(HtxTrendPositionEntity position, TrendSignal signal, HtxSwapContractInfo contract,
                              List<HtxSwapKline> entryCandles) {
        if (HtxTrendRecorder.ENTRY_SENT.equals(position.getStatus())) {
            return reconcileEntry(position);
        }
        if (HtxTrendRecorder.ACTIVE.equals(position.getStatus())) {
            return monitorActive(position, signal, contract, entryCandles);
        }
        if (HtxTrendRecorder.CLOSE_SENT.equals(position.getStatus())) {
            return reconcileClose(position);
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
        Optional<HtxSwapPosition> exchangePosition = exchangePosition(position.getContractCode(), position.getSide());
        if (exchangePosition.isEmpty()) {
            String reviewReason = exitEngineReason(EXIT_HARD_RISK, "managed position missing on exchange");
            recorder.markStatus(position.getId(), HtxTrendRecorder.UNKNOWN,
                    reviewReason + "; manual review");
            return RunResult.of(RunCode.MANUAL_REVIEW, position.getId(), reviewReason);
        }
        BigDecimal reference = firstPositive(exchangePosition.get().getMarkPrice(), exchangePosition.get().getLastPrice(),
                signal.closePrice(), position.getEntryAvgPrice());
        BigDecimal highest = max(firstPositive(position.getHighestPrice(), reference), reference);
        BigDecimal lowest = min(firstPositive(position.getLowestPrice(), reference), reference);
        StopContext stopContext = stopContext(position, signal, reference, highest, lowest, contract, entryCandles);
        BigDecimal trailingStop = stopContext.effectiveStop();
        BigDecimal feeProtectedStop = feeProtectedStop(position, highest, lowest, contract);
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
        String exitReason = combinationInvalidationExitReason(position, reference);
        if (exitReason == null) {
            exitReason = flatAfterFeeProtectionExitReason(position, signal, reference, feeProtectedStop);
        }
        if (exitReason == null) {
            exitReason = flatLossControlExitReason(position, signal, reference);
        }
        if (exitReason == null) {
            exitReason = exitReason(position.getSide(), signal, reference, trailingStop, takeProfit,
                    stopContext.stopSource());
        }
        if (exitReason == null) {
            exitReason = fundingCostExitReason(position.getContractCode(), position.getSide());
        }
        if (exitReason == null) {
            return RunResult.of(RunCode.ACTIVE, position.getId(), clip("holding ref=" + reference
                    + " signal=" + signal.side() + " trailingStop=" + trailingStop
                    + " " + stopContext.summary() + " " + exitSnapshot.summary()));
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
                position.getContractVolume());
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
                position.getContractVolume(), exitReason);
        return RunResult.of(RunCode.CLOSE_SENT, position.getId(), exitReason);
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
            return new SignalSnapshot(TrendSignal.skip("entry kline unavailable: " + error(entryResponse)), List.of());
        }
        String trendPeriod = trendPeriod();
        HtxRestResponse<List<HtxSwapKline>> trendResponse = trendPeriod.equalsIgnoreCase(properties.getPeriod())
                ? entryResponse : swap.historyKline(contractCode, trendPeriod, properties.getTrendKlineSize());
        if (trendResponse == null || !trendResponse.ok() || trendResponse.payload() == null) {
            return new SignalSnapshot(TrendSignal.skip("trend kline unavailable: " + error(trendResponse)),
                    entryResponse.payload());
        }
        TrendSignal signal = calculator.evaluate(entryResponse.payload(), trendResponse.payload(),
                properties, Instant.now().getEpochSecond());
        return new SignalSnapshot(signal, entryResponse.payload());
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

    private Optional<HtxSwapPosition> exchangePosition(String contractCode, String side) {
        HtxRestResponse<List<HtxSwapPosition>> response = swap.positionInfo(contractCode);
        if (response == null || !response.ok() || response.payload() == null) {
            return Optional.empty();
        }
        String positionSide = LONG.equals(side) ? POSITION_SIDE_LONG : POSITION_SIDE_SHORT;
        return response.payload().stream()
                .filter(item -> item != null && positionSide.equalsIgnoreCase(item.getPositionSide()))
                .filter(item -> positive(item.getVolume()))
                .findFirst();
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
                                        PerformanceContext performance, DirectionConfidence confidence) {
        BigDecimal multiplier = BigDecimal.ONE;
        StringBuilder reason = new StringBuilder();
        if (performance != null && performance.regime() == QualityRegime.WEAK) {
            multiplier = multiplier.multiply(nonNegative(properties.getWeakQualityPositionMultiplier()));
            reason.append("quality=WEAK ");
        } else if (performance != null && performance.regime() == QualityRegime.STRONG) {
            multiplier = multiplier.multiply(nonNegative(properties.getStrongQualityPositionMultiplier()));
            reason.append("quality=STRONG ");
        } else {
            reason.append("quality=NEUTRAL ");
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
        }
        if (properties.isDirectionConfidenceGateEnabled() && properties.isConfidenceSizingEnabled()
                && confidence != null && performance != null && performance.regime() != QualityRegime.WEAK) {
            BigDecimal score = confidence.score();
            if (score.compareTo(nonNegative(properties.getStrongConfluenceSizingScore())) >= 0
                    && breadthAligned(side, marketBreadth) && performance.regime() == QualityRegime.STRONG) {
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
        } else if (properties.isConfidenceSizingEnabled() && performance != null && performance.regime() == QualityRegime.WEAK
                && confidence != null) {
            reason.append("confidence=HELD_BY_WEAK_QUALITY(score=")
                    .append(confidence.score().setScale(2, RoundingMode.HALF_UP)).append(") ");
        }
        if (performance != null && performance.regime() == QualityRegime.WEAK
                && positive(properties.getWeakQualityMaxPositionMultiplier())
                && multiplier.compareTo(properties.getWeakQualityMaxPositionMultiplier()) > 0) {
            multiplier = properties.getWeakQualityMaxPositionMultiplier();
            reason.append("qualityCap=WEAK_MAX ");
        }
        multiplier = clamp(multiplier, positive(properties.getMinAdaptivePositionMultiplier())
                        ? properties.getMinAdaptivePositionMultiplier() : BigDecimal.ONE,
                positive(properties.getMaxAdaptivePositionMultiplier())
                        ? properties.getMaxAdaptivePositionMultiplier() : BigDecimal.ONE);
        return new SizingContext(multiplier, reason.toString().trim());
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
        entity.setAccountLabel(position.getAccountLabel() == null ? "primary" : position.getAccountLabel());
        entity.setFeeRateUsed(position.getEntryFeeRate());
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

    private long targetVolume(BigDecimal entryPrice, BigDecimal atr, BigDecimal contractSize, SizingContext sizing) {
        if (!positive(entryPrice) || !positive(atr) || !positive(contractSize)) {
            return 0L;
        }
        BigDecimal multiplier = sizing == null ? BigDecimal.ONE : sizing.multiplier();
        BigDecimal stopDistance = atr.multiply(properties.getAtrStopMultiplier());
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

    private BigDecimal initialStop(TrendSignal signal, String side, HtxSwapContractInfo contract) {
        BigDecimal distance = signal.atr().multiply(properties.getAtrStopMultiplier());
        BigDecimal value = LONG.equals(side) ? signal.closePrice().subtract(distance) : signal.closePrice().add(distance);
        return scalePrice(value, contract);
    }

    private BigDecimal initialTakeProfit(TrendSignal signal, String side, HtxSwapContractInfo contract) {
        if (properties.getTakeProfitAtrMultiplier() == null || properties.getTakeProfitAtrMultiplier().signum() <= 0) {
            return null;
        }
        BigDecimal distance = signal.atr().multiply(properties.getTakeProfitAtrMultiplier());
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
        BigDecimal distance = positive(signal.atr()) ? signal.atr().multiply(properties.getTrailingAtrMultiplier())
                : reference.multiply(properties.getMinAtrPct()).multiply(properties.getTrailingAtrMultiplier());
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
        BigDecimal quantity = position.getContractSize().multiply(BigDecimal.valueOf(position.getContractVolume()));
        if (!positive(quantity)) {
            return null;
        }
        BigDecimal entryPrice = position.getEntryAvgPrice();
        BigDecimal entryNotional = entryPrice.multiply(quantity);
        BigDecimal takerFeeRate = effectiveTakerFeeRate(position.getContractCode());
        BigDecimal entryFee = positive(position.getEntryFee())
                ? position.getEntryFee() : entryNotional.multiply(takerFeeRate);
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
        return normalized;
    }

    private BigDecimal rTargetPrice(HtxTrendPositionEntity position, BigDecimal multiple) {
        if (position == null || !positive(position.getEntryAvgPrice()) || !positive(position.getStopPrice())
                || multiple == null || multiple.signum() <= 0) {
            return null;
        }
        BigDecimal riskDistance = LONG.equals(position.getSide())
                ? position.getEntryAvgPrice().subtract(position.getStopPrice())
                : position.getStopPrice().subtract(position.getEntryAvgPrice());
        if (!positive(riskDistance)) {
            return null;
        }
        BigDecimal targetDistance = riskDistance.multiply(multiple);
        return LONG.equals(position.getSide())
                ? position.getEntryAvgPrice().add(targetDistance)
                : position.getEntryAvgPrice().subtract(targetDistance);
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
        if (!positive(reference) || !positive(price)) {
            return false;
        }
        return LONG.equals(side) ? reference.compareTo(price) <= 0 : reference.compareTo(price) >= 0;
    }

    private static boolean targetReached(String side, BigDecimal reference, BigDecimal target) {
        if (!positive(reference) || !positive(target)) {
            return false;
        }
        return LONG.equals(side) ? reference.compareTo(target) >= 0 : reference.compareTo(target) <= 0;
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
        long period = Math.max(1L, periodSeconds(properties.getPeriod()));
        return seconds / period;
    }

    private BigDecimal estimatedCloseNetProfit(HtxTrendPositionEntity position, BigDecimal reference) {
        if (position == null || !positive(position.getEntryAvgPrice()) || !positive(position.getContractSize())
                || position.getContractVolume() == null || position.getContractVolume() <= 0L || !positive(reference)) {
            return null;
        }
        BigDecimal quantity = position.getContractSize().multiply(BigDecimal.valueOf(position.getContractVolume()));
        if (!positive(quantity)) {
            return null;
        }
        BigDecimal pricePnl = LONG.equals(position.getSide())
                ? reference.subtract(position.getEntryAvgPrice()).multiply(quantity)
                : position.getEntryAvgPrice().subtract(reference).multiply(quantity);
        BigDecimal takerFeeRate = effectiveTakerFeeRate(position.getContractCode());
        BigDecimal entryFee = positive(position.getEntryFee())
                ? position.getEntryFee() : position.getEntryAvgPrice().multiply(quantity).multiply(takerFeeRate);
        BigDecimal closeFee = reference.multiply(quantity).multiply(takerFeeRate);
        return pricePnl.subtract(entryFee).subtract(closeFee);
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

    private String exitReason(String side, TrendSignal signal, BigDecimal reference,
                              BigDecimal trailingStop, BigDecimal takeProfit, String stopSource) {
        if (LONG.equals(side)) {
            if (reference.compareTo(trailingStop) <= 0) {
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
        if (reference.compareTo(trailingStop) >= 0) {
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

    public enum RunCode {
        OBSERVED, NO_SIGNAL, ENTRY_SENT, ACTIVE, CLOSE_SENT, DONE, WAITING, SKIPPED, REJECTED, MANUAL_REVIEW
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

    private record SignalSnapshot(TrendSignal signal, List<HtxSwapKline> entryCandles) {
    }

    private record CandidateScanResult(String contractCode, CandidateEvaluation evaluation) {
    }

    private record CandidateEvaluation(RunResult result, EntryCandidate candidate) {
    }

    private record FactorSnapshotCaptureResult(String contractCode, int availablePeriods, int requestedPeriods) {
    }

    private record FactorSnapshotCaptureSummary(int contracts, int completeContracts, int availablePeriods,
                                                int requestedPeriods, long durationMs, String status) {
        static FactorSnapshotCaptureSummary notRun() {
            return new FactorSnapshotCaptureSummary(0, 0, 0, 0, 0L, "not-run");
        }

        static FactorSnapshotCaptureSummary skipped(String reason) {
            return new FactorSnapshotCaptureSummary(0, 0, 0, 0, 0L, "skipped:" + reason);
        }

        static FactorSnapshotCaptureSummary from(List<FactorSnapshotCaptureResult> results, int requestedPeriods) {
            int contracts = results == null ? 0 : results.size();
            int completeContracts = 0;
            int availablePeriods = 0;
            if (results != null) {
                for (FactorSnapshotCaptureResult result : results) {
                    if (result == null) {
                        continue;
                    }
                    availablePeriods += result.availablePeriods();
                    if (result.availablePeriods() == requestedPeriods) {
                        completeContracts++;
                    }
                }
            }
            return new FactorSnapshotCaptureSummary(contracts, completeContracts, availablePeriods,
                    contracts * requestedPeriods, 0L, "complete");
        }

        FactorSnapshotCaptureSummary withDuration(long durationMs) {
            return new FactorSnapshotCaptureSummary(contracts, completeContracts, availablePeriods,
                    requestedPeriods, durationMs, status);
        }

        String summary() {
            return status + " contracts=" + contracts + " completeContracts=" + completeContracts
                    + " periods=" + availablePeriods + "/" + requestedPeriods + " durationMs=" + durationMs;
        }
    }

    private record EntryCandidate(String contractCode, TrendSignal signal, List<HtxSwapKline> entryCandles,
                                  HtxSwapContractInfo contract, BigDecimal score, CombinationContext combination) {
    }

    private record CombinationContext(HtxTrendCombinationService.Selection selection) {
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
}
