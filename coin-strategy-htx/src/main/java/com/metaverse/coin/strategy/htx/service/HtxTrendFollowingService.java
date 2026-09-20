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
    private static final LocalDateTime SIGNAL_HISTORY_FLOOR = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final HtxUsdtSwapService swap;
    private final HtxContractProperties contractProperties;
    private final HtxFundingArbitrageProperties fundingArbitrageProperties;
    private final HtxMarketMakerProperties marketMakerProperties;
    private final HtxTrendProperties properties;
    private final HtxTrendRecorder recorder;
    private final HtxTrendSignalCalculator calculator;
    private final HtxTrendMirrorExecutor mirrorExecutor;
    private final Map<String, EntryCandidate> pendingEntryCandidates = new ConcurrentHashMap<>();
    private volatile long lastCandidateScanCloseEpoch = Long.MIN_VALUE;

    @Autowired
    public HtxTrendFollowingService(HtxUsdtSwapApiService api,
                                    HtxContractProperties contractProperties,
                                    HtxFundingArbitrageProperties fundingArbitrageProperties,
                                    HtxMarketMakerProperties marketMakerProperties,
                                    HtxTrendProperties properties,
                                    HtxTrendRecorder recorder,
                                    HtxTrendMirrorExecutor mirrorExecutor) {
        this(api.getSwapService(), contractProperties, fundingArbitrageProperties, marketMakerProperties,
                properties, recorder, new HtxTrendSignalCalculator(), mirrorExecutor);
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
        this.swap = swap;
        this.contractProperties = contractProperties;
        this.fundingArbitrageProperties = fundingArbitrageProperties;
        this.marketMakerProperties = marketMakerProperties;
        this.properties = properties;
        this.recorder = recorder;
        this.calculator = calculator;
        this.mirrorExecutor = mirrorExecutor;
    }

    public RunResult runOnce() {
        return runOnce(true);
    }

    public boolean hasOpenLikePositions() {
        return recorder.countOpenLike() > 0L;
    }

    RunResult runOnce(boolean entryWindowAllowsNewEntries) {
        List<String> contractCodes = liveContractCodes();
        if (contractCodes.isEmpty()) {
            return RunResult.of(RunCode.SKIPPED, null, "trend contract list is blank");
        }
        long candidateScanCloseEpoch = currentStableCandleCloseEpoch();
        boolean scanEntryCandidates = markCandidateScanStarted(candidateScanCloseEpoch);
        long openLikePositions = recorder.countOpenLike();
        int maxActivePositions = Math.max(0, properties.getMaxActivePositions());
        MarketBreadthContext marketBreadth = marketBreadthContext();
        PerformanceContext performance = performanceContext();
        List<String> summaries = new ArrayList<>();
        Map<String, EntryCandidate> entryCandidates = new HashMap<>();
        long candidateScanDurationMs = 0L;
        int candidateScanConcurrency = candidateScanConcurrency(contractCodes.size());
        int candidateScanContracts = scanEntryCandidates ? contractCodes.size() : 0;
        RunResult selected = null;

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

        if (scanEntryCandidates) {
            long scanStartedAt = System.nanoTime();
            for (CandidateScanResult scanResult : evaluateEntryCandidates(contractCodes, marketBreadth, candidateScanConcurrency)) {
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
                    "candidate scan already completed closeEpoch=" + candidateScanCloseEpoch);
            selected = select(selected, tag("ENTRY_SCAN", scanSkipped));
            summaries.add("ENTRY_SCAN=" + scanSkipped.code() + ":" + scanSkipped.message());
        }

        long remainingSlots = Math.max(0L, maxActivePositions - openLikePositions);
        List<EntryCandidate> rankedCandidates = entryCandidates.values().stream()
                .sorted(Comparator.comparing(EntryCandidate::score).reversed())
                .toList();
        if (!rankedCandidates.isEmpty()) {
            if (!properties.isEntryEnabled()) {
                RunResult blocked = RunResult.of(RunCode.SKIPPED, null, "new trend entries disabled");
                selected = select(selected, tag("ENTRY", blocked));
                summaries.add("ENTRY=" + blocked.code() + ":" + blocked.message());
            } else if (!entryWindowAllowsNewEntries && !hasFreshCarryoverCandidate) {
                RunResult blocked = RunResult.of(RunCode.SKIPPED, null, "entry window closed; candidates cached");
                selected = select(selected, tag("ENTRY", blocked));
                summaries.add("ENTRY=" + blocked.code() + ":" + blocked.message());
            } else if (remainingSlots <= 0L) {
                RunResult blocked = RunResult.of(RunCode.SKIPPED, null, "max active trend positions reached");
                selected = select(selected, tag("ENTRY", blocked));
                summaries.add("ENTRY=" + blocked.code() + ":" + blocked.message());
            } else {
                long opened = 0L;
                for (EntryCandidate candidate : rankedCandidates) {
                    if (opened >= remainingSlots) {
                        break;
                    }
                    RunResult result = openIfEligible(candidate.contractCode(), candidate.signal(),
                            candidate.entryCandles(), candidate.contract(), marketBreadth, performance);
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

        if (selected == null) {
            return RunResult.of(RunCode.SKIPPED, null, "no trend contracts evaluated");
        }
        return RunResult.of(selected.code(), selected.positionId(), selected.message()
                + "; market=" + marketBreadth.summary()
                + "; quality=" + performance.summary()
                + "; candidates=" + rankedCandidates.size()
                + "; pendingCandidates=" + pendingEntryCandidates.size()
                + "; candidateScanCloseEpoch=" + candidateScanCloseEpoch
                + "; candidateScanConcurrency=" + candidateScanConcurrency
                + "; candidateScanContracts=" + candidateScanContracts
                + "; candidateScanDurationMs=" + candidateScanDurationMs
                + "; remainingSlots=" + remainingSlots
                + "; liveContracts=" + String.join(" | ", summaries));
    }

    private List<CandidateScanResult> evaluateEntryCandidates(List<String> contractCodes,
                                                              MarketBreadthContext marketBreadth,
                                                              int concurrency) {
        if (contractCodes.size() <= 1 || concurrency <= 1) {
            List<CandidateScanResult> results = new ArrayList<>(contractCodes.size());
            for (String contractCode : contractCodes) {
                results.add(scanEntryCandidate(contractCode, marketBreadth));
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
                tasks.add(() -> scanEntryCandidate(contractCode, marketBreadth));
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
                        RunResult.of(RunCode.SKIPPED, null, "candidate scan interrupted"), null)));
            }
            return interrupted;
        } catch (ExecutionException ex) {
            return List.of(new CandidateScanResult("ENTRY_SCAN", new CandidateEvaluation(
                    RunResult.of(RunCode.SKIPPED, null, "candidate scan failed: " + rootMessage(ex)), null)));
        } finally {
            executor.shutdownNow();
        }
    }

    private CandidateScanResult scanEntryCandidate(String contractCode, MarketBreadthContext marketBreadth) {
        try {
            return new CandidateScanResult(contractCode, evaluateEntryCandidate(contractCode, marketBreadth));
        } catch (RuntimeException ex) {
            log.warn("[HTX-TREND] candidate scan failed contract={}", contractCode, ex);
            return new CandidateScanResult(contractCode, new CandidateEvaluation(
                    RunResult.of(RunCode.SKIPPED, null, "candidate scan failed: " + rootMessage(ex)), null));
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
            return RunResult.of(RunCode.SKIPPED, null, "contract info unavailable");
        }
        SignalSnapshot snapshot = loadSignal(contractCode);
        TrendSignal rawSignal = snapshot.signal();
        recordSignal(contractCode, rawSignal);
        return advance(active.get(), rawSignal, contract);
    }

    private CandidateEvaluation evaluateEntryCandidate(String contractCode, MarketBreadthContext marketBreadth) {
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
            return new CandidateEvaluation(RunResult.of(RunCode.SKIPPED, null, "contract info unavailable"), null);
        }
        SignalSnapshot snapshot = loadSignal(contractCode);
        TrendSignal rawSignal = snapshot.signal();
        TrendSignal signal = entrySignalWithCarryContext(contractCode, rawSignal, marketBreadth);
        recordSignal(contractCode, signal);
        if (signal.side() == TrendSide.FLAT) {
            return new CandidateEvaluation(RunResult.of(RunCode.NO_SIGNAL, null, signal.reason()), null);
        }
        BigDecimal score = entryCandidateScore(signal, marketBreadth);
        EntryCandidate candidate = new EntryCandidate(contractCode, signal, snapshot.entryCandles(), contract, score);
        return new CandidateEvaluation(RunResult.of(RunCode.OBSERVED, null,
                "entry candidate queued side=" + signal.side() + " score=" + score.setScale(2, RoundingMode.HALF_UP)
                        + " reason=" + signal.reason()), candidate);
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

    private BigDecimal entryCandidateScore(TrendSignal signal, MarketBreadthContext marketBreadth) {
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
        if (breadthAligned(signal.side(), marketBreadth)) {
            score = score.add(new BigDecimal("10"));
        }
        return score;
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
                                     PerformanceContext performance) {
        if (signal.side() == TrendSide.FLAT) {
            return RunResult.of(RunCode.NO_SIGNAL, null, signal.reason());
        }
        String fundingCostGate = fundingCostGate(contractCode, signal.side(), properties.getMaxEntryFundingCostRate());
        if (fundingCostGate != null) {
            RunCode code = properties.isObserveOnly() ? RunCode.OBSERVED : RunCode.SKIPPED;
            return RunResult.of(code, null, fundingCostGate);
        }
        if (properties.isObserveOnly()) {
            return RunResult.of(RunCode.OBSERVED, null, "observe-only signal=" + signal.side()
                    + " reason=" + signal.reason());
        }
        String freshnessGate = entrySignalFreshnessGate(signal);
        if (freshnessGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, freshnessGate);
        }
        String cooldownGate = cooldownGate(contractCode, signal, marketBreadth);
        if (cooldownGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, cooldownGate);
        }
        HtxDepth depth = depthSnapshot(contractCode);
        TopOfBook book = topOfBook(depth);
        if (book == null) {
            return RunResult.of(RunCode.SKIPPED, null, "depth unavailable");
        }
        BigDecimal spreadRate = book.ask().subtract(book.bid()).divide(mid(book), 18, RoundingMode.HALF_UP);
        if (spreadRate.compareTo(properties.getMaxEntrySpreadRate()) > 0) {
            return RunResult.of(RunCode.SKIPPED, null, "spread too wide: " + spreadRate);
        }
        String openOrderGate = openOrderGate(contractCode);
        if (openOrderGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, openOrderGate);
        }
        String externalPositionGate = externalPositionGate(contractCode);
        if (externalPositionGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, externalPositionGate);
        }
        BigDecimal entryPrice = signal.side() == TrendSide.LONG ? book.ask() : book.bid();
        String chaseGate = entryChaseGate(signal, entryPrice);
        if (chaseGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, chaseGate);
        }
        SizingContext sizing = sizingContext(signal.side(), marketBreadth, performance);
        long volume = targetVolume(entryPrice, signal.atr(), contract.getContractSize(), sizing);
        if (volume <= 0) {
            return RunResult.of(RunCode.SKIPPED, null, "target volume below minimum " + sizing.summary());
        }
        String liquidityGate = liquidityGate(signal, entryCandles, depth, volume);
        if (liquidityGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, liquidityGate);
        }
        String expectedMoveGate = expectedMoveGate(signal, entryPrice, spreadRate);
        if (expectedMoveGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, expectedMoveGate);
        }
        BigDecimal notional = BigDecimal.valueOf(volume).multiply(contract.getContractSize()).multiply(entryPrice);
        String marginGate = marginGate(contractCode, notional);
        if (marginGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, marginGate);
        }
        String leverageGate = switchLeverage(contractCode, signal.side());
        if (leverageGate != null) {
            return RunResult.of(RunCode.SKIPPED, null, leverageGate);
        }
        String side = signal.side() == TrendSide.LONG ? LONG : SHORT;
        BigDecimal stopPrice = initialStop(signal, side, contract);
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
        long id = recorder.createIntent(entity);
        entity.setId(id);
        HtxSwapOrderRequest request = orderRequest(contractCode, signal.side(), false, clientOrderId, volume);
        recorder.createOrderIntent(orderIntent(entity, HtxTrendRecorder.LEG_ENTRY, signal.side().name(), request,
                "entry signal: " + signal.reason() + "; " + sizing.summary()));
        HtxRestResponse<HtxSwapOrderResult> response = swap.placeOrder(request);
        HtxSwapOrderResult accepted = response == null ? null : response.payload();
        Long orderId = accepted == null ? null : accepted.getOrderId();
        if (response == null || !response.ok() || orderId == null) {
            recorder.markOrderRejected(id, HtxTrendRecorder.LEG_ENTRY, clientOrderId,
                    "entry rejected: " + error(response));
            recorder.markStatus(id, HtxTrendRecorder.REJECTED, "entry rejected: " + error(response));
            return RunResult.of(RunCode.REJECTED, id, "entry rejected: " + error(response));
        }
        recorder.markOrderAccepted(id, HtxTrendRecorder.LEG_ENTRY, clientOrderId, orderId,
                accepted.getClientOrderId(), "entry accepted by exchange");
        recorder.markEntryAccepted(id, orderId, "entry sent side=" + side + " volume=" + volume);
        log.info("[HTX-TREND] entry sent id={} contract={} side={} orderId={} volume={} close={} atr={} adx={} reason={} sizing={}",
                id, contractCode, side, orderId, volume, signal.closePrice(), signal.atr(), signal.adx(),
                signal.reason(), sizing.summary());
        return RunResult.of(RunCode.ENTRY_SENT, id, "entry order sent " + sizing.summary());
    }

    private RunResult advance(HtxTrendPositionEntity position, TrendSignal signal, HtxSwapContractInfo contract) {
        if (HtxTrendRecorder.ENTRY_SENT.equals(position.getStatus())) {
            return reconcileEntry(position);
        }
        if (HtxTrendRecorder.ACTIVE.equals(position.getStatus())) {
            return monitorActive(position, signal, contract);
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

    private RunResult monitorActive(HtxTrendPositionEntity position, TrendSignal signal, HtxSwapContractInfo contract) {
        Optional<HtxSwapPosition> exchangePosition = exchangePosition(position.getContractCode(), position.getSide());
        if (exchangePosition.isEmpty()) {
            recorder.markStatus(position.getId(), HtxTrendRecorder.UNKNOWN,
                    "managed position missing on exchange; manual review");
            return RunResult.of(RunCode.MANUAL_REVIEW, position.getId(), "managed position missing on exchange");
        }
        BigDecimal reference = firstPositive(exchangePosition.get().getMarkPrice(), exchangePosition.get().getLastPrice(),
                signal.closePrice(), position.getEntryAvgPrice());
        BigDecimal highest = max(firstPositive(position.getHighestPrice(), reference), reference);
        BigDecimal lowest = min(firstPositive(position.getLowestPrice(), reference), reference);
        BigDecimal trailingStop = trailingStop(position, signal, reference, highest, lowest, contract);
        BigDecimal feeProtectedStop = feeProtectedStop(position, highest, lowest, contract);
        if (feeProtectedStop != null) {
            trailingStop = LONG.equals(position.getSide())
                    ? max(trailingStop, feeProtectedStop) : min(trailingStop, feeProtectedStop);
        }
        BigDecimal takeProfit = position.getTakeProfitPrice();
        recorder.updateRiskLevels(position.getId(), position.getStopPrice(), trailingStop, takeProfit, highest, lowest,
                "holding signal=" + signal.side() + " ref=" + reference + feeProtectionMessage(feeProtectedStop));
        String exitReason = flatAfterFeeProtectionExitReason(position, signal, reference, feeProtectedStop);
        if (exitReason == null) {
            exitReason = flatLossControlExitReason(position, signal, reference);
        }
        if (exitReason == null) {
            exitReason = exitReason(position.getSide(), signal, reference, trailingStop, takeProfit);
        }
        if (exitReason == null) {
            exitReason = fundingExitReason(position.getContractCode(), position.getSide());
        }
        if (exitReason == null) {
            return RunResult.of(RunCode.ACTIVE, position.getId(), "holding ref=" + reference
                    + " signal=" + signal.side() + " trailingStop=" + trailingStop);
        }
        return closePosition(position, exitReason);
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
            recorder.markOrderRejected(position.getId(), HtxTrendRecorder.LEG_CLOSE, clientOrderId,
                    "close rejected: " + error(response));
            return RunResult.of(RunCode.WAITING, position.getId(), "close rejected: " + error(response));
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

    private String fundingExitReason(String contractCode, String side) {
        TrendSide trendSide = LONG.equals(side) ? TrendSide.LONG : TrendSide.SHORT;
        String gate = fundingCostGate(contractCode, trendSide, properties.getMaxHoldFundingCostRate());
        return gate == null ? null : "exit because " + gate;
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
        BigDecimal profitFactor = profitFactor(grossProfit, grossLoss);
        if (closed < Math.max(1, properties.getQualityMinClosedTrades())) {
            return new PerformanceContext(QualityRegime.NEUTRAL, closed, wins, losses, profitFactor, netProfit,
                    "insufficient closed trades=" + closed);
        }
        if (wins <= losses || profitFactor.compareTo(nonNegative(properties.getQualityMinProfitFactor())) < 0
                || netProfit.signum() < 0) {
            return new PerformanceContext(QualityRegime.WEAK, closed, wins, losses, profitFactor, netProfit,
                    "weak quality");
        }
        if (wins > losses && profitFactor.compareTo(nonNegative(properties.getQualityStrongProfitFactor())) >= 0
                && netProfit.signum() > 0) {
            return new PerformanceContext(QualityRegime.STRONG, closed, wins, losses, profitFactor, netProfit,
                    "strong quality");
        }
        return new PerformanceContext(QualityRegime.NEUTRAL, closed, wins, losses, profitFactor, netProfit,
                "normal quality");
    }

    private SizingContext sizingContext(TrendSide side, MarketBreadthContext marketBreadth,
                                        PerformanceContext performance) {
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
        return entity;
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
        BigDecimal maxNotional = nonNegative(properties.getMaxQuoteNotional()).multiply(multiplier);
        BigDecimal notional = min(targetNotional, maxNotional, riskSizedNotional);
        if (notional.compareTo(properties.getMinQuoteNotional()) < 0) {
            return 0L;
        }
        BigDecimal perContract = entryPrice.multiply(contractSize);
        return notional.divide(perContract, 0, RoundingMode.DOWN).longValue();
    }

    private String expectedMoveGate(TrendSignal signal, BigDecimal entryPrice, BigDecimal spreadRate) {
        if (!properties.isFeeProtectionEnabled() || !positive(signal.atr()) || !positive(entryPrice)) {
            return null;
        }
        BigDecimal expectedMove = signal.atr().multiply(nonNegative(properties.getMinExpectedMoveAtrMultiplier()));
        BigDecimal requiredMove = entryPrice.multiply(roundTripCostRate(spreadRate));
        if (expectedMove.compareTo(requiredMove) < 0) {
            return "expected move too small after fees expectedMove=" + expectedMove
                    + " requiredMove=" + requiredMove + " atr=" + signal.atr();
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

    private BigDecimal trailingStop(HtxTrendPositionEntity position, TrendSignal signal, BigDecimal reference,
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
        BigDecimal entryFee = positive(position.getEntryFee())
                ? position.getEntryFee() : entryNotional.multiply(nonNegative(properties.getEstimatedTakerFeeRate()));
        BigDecimal estimatedCloseFee = entryNotional.multiply(nonNegative(properties.getEstimatedTakerFeeRate()));
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
            return "long exit on confirmed flat trend after fee protection ref=" + reference
                    + " stop=" + feeProtectedStop + " flatBars=" + flatBars + "/" + requiredFlatBars;
        }
        if (SHORT.equals(position.getSide()) && reference.compareTo(feeProtectedStop) <= 0) {
            return "short exit on confirmed flat trend after fee protection ref=" + reference
                    + " stop=" + feeProtectedStop + " flatBars=" + flatBars + "/" + requiredFlatBars;
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
        return position.getSide().toLowerCase(Locale.ROOT) + " exit on confirmed flat trend loss control ref="
                + reference + " estimatedNet=" + estimatedNet + " lossBudget=" + lossBudget
                + " flatBars=" + flatBars + "/" + requiredFlatBars;
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
        BigDecimal entryFee = positive(position.getEntryFee())
                ? position.getEntryFee() : position.getEntryAvgPrice().multiply(quantity)
                .multiply(nonNegative(properties.getEstimatedTakerFeeRate()));
        BigDecimal closeFee = reference.multiply(quantity).multiply(nonNegative(properties.getEstimatedTakerFeeRate()));
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

    private BigDecimal roundTripCostRate(BigDecimal spreadRate) {
        return nonNegative(properties.getEstimatedTakerFeeRate()).multiply(TWO)
                .add(nonNegative(properties.getMinNetProfitRate()))
                .add(nonNegative(spreadRate));
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
                              BigDecimal trailingStop, BigDecimal takeProfit) {
        if (LONG.equals(side)) {
            if (reference.compareTo(trailingStop) <= 0) {
                return "long trailing stop hit ref=" + reference + " stop=" + trailingStop;
            }
            if (takeProfit != null && reference.compareTo(takeProfit) >= 0) {
                return "long take profit hit ref=" + reference + " target=" + takeProfit;
            }
            if (signal.side() == TrendSide.SHORT) {
                return "long exit on opposite trend signal";
            }
            return null;
        }
        if (reference.compareTo(trailingStop) >= 0) {
            return "short trailing stop hit ref=" + reference + " stop=" + trailingStop;
        }
        if (takeProfit != null && reference.compareTo(takeProfit) <= 0) {
            return "short take profit hit ref=" + reference + " target=" + takeProfit;
        }
        if (signal.side() == TrendSide.LONG) {
            return "short exit on opposite trend signal";
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

    private String configGate(String contractCode) {
        if (!properties.isEnabled()) {
            return "trend strategy disabled";
        }
        if (isBlank(contractCode)) {
            return "contract code is blank";
        }
        if (!contractProperties.isEnabled()) {
            return "contract module disabled";
        }
        if (!properties.isObserveOnly() && marketMakerProperties != null && marketMakerProperties.isEnabled()) {
            return "trend exclusive mode blocked: market-maker enabled";
        }
        if (!properties.isObserveOnly() && fundingArbitrageProperties != null && fundingArbitrageProperties.isEnabled()) {
            return "trend exclusive mode blocked: funding arbitrage enabled";
        }
        if (!properties.isObserveOnly() && (contractProperties.isDryRun() || !contractProperties.isLiveEnabled())) {
            return "trend live mode requires contract dry-run=false and live-enabled=true";
        }
        if (properties.getLeverage() <= 0 || properties.getLeverage() > contractProperties.getMaxLeverage()) {
            return "trend leverage outside configured max leverage";
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
        if (value == null || value.length() <= 512) {
            return value;
        }
        return value.substring(0, 512);
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

    private record PerformanceContext(QualityRegime regime, long closedCount, long winCount, long lossCount,
                                      BigDecimal profitFactor, BigDecimal netProfit, String reason) {
        static PerformanceContext neutral(String reason) {
            return new PerformanceContext(QualityRegime.NEUTRAL, 0L, 0L, 0L,
                    BigDecimal.ONE, BigDecimal.ZERO, reason);
        }

        String summary() {
            return regime + "(closed=" + closedCount + ",wins=" + winCount + ",losses=" + lossCount
                    + ",pf=" + profitFactor.setScale(2, RoundingMode.HALF_UP)
                    + ",net=" + netProfit.setScale(4, RoundingMode.HALF_UP)
                    + ",reason=" + reason + ")";
        }
    }

    private record SizingContext(BigDecimal multiplier, String reason) {
        String summary() {
            return "sizingMultiplier=" + multiplier.setScale(2, RoundingMode.HALF_UP) + " " + reason;
        }
    }

    private static String pct(BigDecimal ratio) {
        return ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP) + "%";
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

    private record EntryCandidate(String contractCode, TrendSignal signal, List<HtxSwapKline> entryCandles,
                                  HtxSwapContractInfo contract, BigDecimal score) {
    }

    private record TopOfBook(BigDecimal bid, BigDecimal ask) {
    }

    private record FundingMarket(BigDecimal fundingRate) {
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
