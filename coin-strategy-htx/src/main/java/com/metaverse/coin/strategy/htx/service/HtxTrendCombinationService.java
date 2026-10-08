package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCandidateSignalRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCombinationRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendCandidateSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStrategyPolicyEntity;
import com.metaverse.coin.strategy.htx.service.HtxTrendCombinationCalculator.CombinationCandidate;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Resolves the one highest-ranked DB-authorized combination for a symbol and candle. */
@Service
@RequiredArgsConstructor
public class HtxTrendCombinationService {
    /** K-line values on HTX have materially lower precision; cap evidence only to keep JSON reviewable. */
    private static final int EVIDENCE_FRACTION_SCALE = 12;

    private final HtxTrendStrategyPolicyService policyService;
    private final HtxTrendCombinationRecorder combinationRecorder;
    private final HtxTrendCandidateSignalRecorder candidateRecorder;
    private final HtxTrendCombinationCalculator calculator = new HtxTrendCombinationCalculator();
    private final HtxTrendModelCoverageCalculator coverageCalculator = new HtxTrendModelCoverageCalculator();
    private final HtxTrendModelAnalyzer modelAnalyzer = new HtxTrendModelAnalyzer();

    public Selection select(String contractCode, String period, List<HtxSwapKline> candles,
                            TrendCandidateSignal signal) {
        return select(contractCode, period, candles, signal, MarketEntryRank.neutral());
    }

    /** Market state adjusts cross-symbol candidate order; it never overrides a model's own policy threshold. */
    public Selection select(String contractCode, String period, List<HtxSwapKline> candles,
                            TrendCandidateSignal signal, MarketEntryRank marketRank) {
        return select(contractCode, period, candles, signal, marketRank, true);
    }

    /**
     * The default preserves the trending-regime precondition.  A caller may disable it only via
     * the explicit HTX trend configuration switch for controlled ablation/gray-release work.
     */
    public Selection select(String contractCode, String period, List<HtxSwapKline> candles,
                            TrendCandidateSignal signal, MarketEntryRank marketRank,
                            boolean requireTrendingRegime) {
        recordModelCoverage(contractCode, period, candles, signal);
        List<HtxTrendStrategyPolicyEntity> policies = policyService.enabledPolicies();
        if (policies.isEmpty()) {
            return reject(contractCode, period, signal, HtxTrendCombinationGateReason.NO_ENABLED_POLICY);
        }
        List<CombinationCandidate> candidates = calculator.evaluate(candles, signal,
                policies.stream().map(HtxTrendStrategyPolicyEntity::getBoxWindowBars)
                        .filter(value -> value != null && value > 1).findFirst().orElse(32),
                requireTrendingRegime);
        if (candidates.isEmpty()) {
            return reject(contractCode, period, signal, HtxTrendCombinationGateReason.classifyNoCandidate(signal));
        }
        MarketEntryRank ranking = marketRank == null ? MarketEntryRank.neutral() : marketRank;
        List<ResolvedCandidate> resolved = candidates.stream()
                .map(candidate -> resolve(candidate, policies, ranking))
                .filter(item -> item != null)
                .sorted(Comparator.comparing((ResolvedCandidate item) -> item.candidate().score()).reversed())
                .toList();
        if (resolved.isEmpty()) {
            return reject(contractCode, period, signal, HtxTrendCombinationGateReason.POLICY_NOT_MATCHED);
        }
        ResolvedCandidate selected = resolved.get(0);
        Long snapshotId = recordSnapshot(contractCode, period, signal, selected);
        recordMarketEntryRankSnapshot(contractCode, period, signal, selected, ranking);
        if (snapshotId == null) {
            return Selection.rejected("factor snapshot persistence failed");
        }
        String runId = runId(contractCode, period, signal.barTime());
        for (ResolvedCandidate item : resolved) {
            recordCandidate(contractCode, period, signal, item, snapshotId,
                    runId, item == selected ? "PASSED" : "REJECTED", item == selected ? "PASSED" : HtxTrendCombinationGateReason.LOWER_PRIORITY_FOR_SYMBOL.code());
        }
        BigDecimal multiplier = sizingMultiplier(selected.baseScore(), selected.candidate().marketRegime());
        BigDecimal notional = selected.policy().getBaseNotionalUsdt().multiply(multiplier)
                .min(selected.policy().getMaxNotionalUsdt());
        return Selection.selected(selected.candidate(), selected.policy(), snapshotId, multiplier, notional, runId);
    }

    /**
     * Persists an immutable, read-only model map for every evaluated bar.  This is intentionally
     * outside policy selection, score ranking, sizing and the live order path.
     */
    private void recordModelCoverage(String contractCode, String period, List<HtxSwapKline> candles,
                                     TrendCandidateSignal signal) {
        if (signal == null || signal.barTime() == null) {
            return;
        }
        HtxTrendModelCoverageCalculator.Coverage coverage = coverageCalculator.evaluate(candles, signal);
        recordModelCoverageSnapshot(contractCode, period, signal, coverage);
        recordModelAnalysisSnapshot(contractCode, period, signal, modelAnalyzer.analyze(signal, coverage));
    }

    private void recordModelCoverageSnapshot(String contractCode, String period, TrendCandidateSignal signal,
                                             HtxTrendModelCoverageCalculator.Coverage coverage) {
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + signal.barTime() + ":model-coverage-v1");
        snapshot.setContractCode(contractCode);
        snapshot.setPeriod(period);
        snapshot.setBarTime(signal.barTime());
        snapshot.setMarketRegime(signal.regime().name());
        snapshot.setFactorsJson(modelCoverageJson(signal, coverage));
        combinationRecorder.recordSnapshot(snapshot);
    }

    private static String modelCoverageJson(TrendCandidateSignal signal, HtxTrendModelCoverageCalculator.Coverage coverage) {
        return "{\"schema\":\"trend-model-coverage-v1\",\"readOnly\":true"
                + ",\"barDirection\":" + jsonText(signal.direction().name())
                + ",\"contextDirection\":" + jsonText(coverage.direction().name())
                + ",\"activeModels\":" + jsonTextArray(coverage.activeModels())
                + ",\"available\":" + coverage.available()
                + ",\"models\":{\"donchian\":" + coverage.donchian()
                + ",\"fibonacci\":" + coverage.fibonacci()
                + ",\"emaVwap\":" + coverage.emaVwap()
                + ",\"momentum\":" + coverage.momentum()
                + ",\"squeeze\":" + coverage.squeeze()
                + ",\"structure\":" + coverage.structure() + "}"
                + ",\"vwap\":" + jsonNumber(coverage.vwap())
                + ",\"close\":" + jsonNumber(signal.closePrice())
                + ",\"atr\":" + jsonNumber(signal.atr())
                + ",\"adx\":" + jsonNumber(signal.adx())
                + ",\"momentumPct\":" + jsonNumber(signal.momentumPct()) + "}";
    }

    /**
     * Persists all active-model evidence for the same K-line window.  It is explicitly
     * observational: no field from this snapshot is read by policy selection, the final gate,
     * sizing or exchange execution.
     */
    private void recordModelAnalysisSnapshot(String contractCode, String period, TrendCandidateSignal signal,
                                             HtxTrendModelAnalyzer.ModelAnalysis analysis) {
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + signal.barTime() + ":model-analysis-v2");
        snapshot.setContractCode(contractCode);
        snapshot.setPeriod(period);
        snapshot.setBarTime(signal.barTime());
        snapshot.setMarketRegime(signal.regime().name());
        snapshot.setFactorsJson(modelAnalysisJson(signal, analysis));
        combinationRecorder.recordSnapshot(snapshot);
    }

    private static String modelAnalysisJson(TrendCandidateSignal signal, HtxTrendModelAnalyzer.ModelAnalysis analysis) {
        return "{\"schema\":\"trend-model-analysis-v2\",\"readOnly\":true"
                + ",\"executionBoundary\":" + jsonText(analysis.executionBoundary())
                + ",\"analysisAvailable\":" + analysis.available()
                + ",\"direction\":" + jsonText(analysis.direction().name())
                + ",\"timeframeProfile\":" + jsonText(analysis.timeframeProfile())
                + ",\"marketRegime\":" + jsonText(signal.regime().name())
                + ",\"activeModels\":" + jsonModelEvidenceArray(analysis.activeModels())
                + ",\"contextEvidence\":" + jsonTextArray(analysis.contextEvidence())
                + ",\"conflicts\":" + jsonTextArray(analysis.conflicts())
                + ",\"risks\":" + jsonTextArray(analysis.risks())
                + ",\"reason\":" + jsonText(analysis.reason()) + "}";
    }

    /**
     * Persists post-signal model outcomes only after the source candle and the requested forward
     * horizon are both complete.  It deliberately lives in the P2 observation lane and creates
     * new immutable rows rather than rewriting the signal-time snapshot.
     */
    public int recordMaturedModelCoverageOutcomes(String contractCode, String period, List<HtxSwapKline> rawCandles,
                                                   HtxTrendEnsembleSignalCalculator ensembleCalculator,
                                                   HtxTrendEnsembleShadowProperties ensembleProperties,
                                                   ModelCoverageFeeContext feeContext) {
        if (contractCode == null || contractCode.isBlank() || period == null || period.isBlank()
                || rawCandles == null || rawCandles.isEmpty() || ensembleCalculator == null || ensembleProperties == null) {
            return 0;
        }
        long completedBefore = Instant.now().getEpochSecond() - periodSeconds(period)
                - Math.max(0L, ensembleProperties.getCandleCloseGraceSeconds());
        List<HtxSwapKline> candles = rawCandles.stream().filter(HtxTrendCombinationService::validCoverageCandle)
                .filter(candle -> candle.getId() <= completedBefore)
                .sorted(Comparator.comparing(HtxSwapKline::getId)).toList();
        if (candles.size() < 2) {
            return 0;
        }
        ModelCoverageFeeContext fees = feeContext == null ? ModelCoverageFeeContext.unavailable() : feeContext;
        int recorded = 0;
        for (int horizonBars : List.of(1, 4, 8, 16)) {
            int sourceIndex = candles.size() - 1 - horizonBars;
            if (sourceIndex < 0) {
                continue;
            }
            List<HtxSwapKline> sourceCandles = candles.subList(0, sourceIndex + 1);
            HtxSwapKline source = sourceCandles.get(sourceCandles.size() - 1);
            long sourceCompletedAt = source.getId() + periodSeconds(period)
                    + Math.max(0L, ensembleProperties.getCandleCloseGraceSeconds());
            TrendCandidateSignal signal = ensembleCalculator.evaluate(sourceCandles, ensembleProperties, period, sourceCompletedAt);
            HtxTrendModelCoverageCalculator.Coverage coverage = coverageCalculator.evaluate(sourceCandles, signal);
            if (!coverage.available()) {
                continue;
            }
            recordModelCoverageSnapshot(contractCode, period, signal, coverage);
            HtxTrendModelAnalyzer.ModelAnalysis analysis = modelAnalyzer.analyze(signal, coverage);
            recordModelAnalysisSnapshot(contractCode, period, signal, analysis);
            HtxSwapKline future = candles.get(sourceIndex + horizonBars);
            for (HtxTrendModelCoverageCalculator.ForwardOutcome outcome : coverageCalculator.evaluateForwardOutcomes(
                    coverage, source.getClose(), future.getClose(), horizonBars,
                    fees.openTakerFeeRate(), fees.closeTakerFeeRate())) {
                HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
                snapshot.setSnapshotKey(contractCode + ":" + period + ":" + signal.barTime()
                        + ":model-coverage-outcome-v2:" + outcome.model() + ":" + horizonBars);
                snapshot.setContractCode(contractCode);
                snapshot.setPeriod(period);
                snapshot.setBarTime(signal.barTime());
                snapshot.setMarketRegime(signal.regime().name());
                snapshot.setFactorsJson(modelCoverageOutcomeJson(signal, coverage, analysis, outcome, fees));
                if (combinationRecorder.recordSnapshot(snapshot) != null) {
                    recorded++;
                }
            }
        }
        return recorded;
    }

    private static String modelCoverageOutcomeJson(TrendCandidateSignal signal,
                                                   HtxTrendModelCoverageCalculator.Coverage coverage,
                                                   HtxTrendModelAnalyzer.ModelAnalysis analysis,
                                                   HtxTrendModelCoverageCalculator.ForwardOutcome outcome,
                                                   ModelCoverageFeeContext fees) {
        String sourceKey = "" + signal.period() + ":" + signal.barTime() + ":model-coverage-v1";
        return "{\"schema\":\"trend-model-coverage-outcome-v2\",\"readOnly\":true"
                + ",\"sourceCoverageSuffix\":" + jsonText(sourceKey)
                + ",\"model\":" + jsonText(outcome.model())
                + ",\"direction\":" + jsonText(outcome.direction().name())
                + ",\"marketRegime\":" + jsonText(signal.regime().name())
                + ",\"coverageModels\":" + jsonTextArray(coverage.activeModels())
                + ",\"activeModels\":" + jsonModelEvidenceArray(analysis.activeModels())
                + ",\"analysisBoundary\":" + jsonText(analysis.executionBoundary())
                + ",\"horizonBars\":" + outcome.horizonBars()
                + ",\"entryPrice\":" + jsonNumber(outcome.entryPrice())
                + ",\"futureClose\":" + jsonNumber(outcome.futureClose())
                + ",\"grossReturnRate\":" + jsonNumber(outcome.grossReturnRate())
                + ",\"roundTripFeeRate\":" + jsonNumber(outcome.roundTripFeeRate())
                + ",\"netReturnRate\":" + jsonNumber(outcome.netReturnRate())
                + ",\"fee\":{\"accountLabel\":" + jsonText(fees.accountLabel())
                + ",\"openTakerRate\":" + jsonNumber(fees.openTakerFeeRate())
                + ",\"closeTakerRate\":" + jsonNumber(fees.closeTakerFeeRate())
                + ",\"source\":" + jsonText(fees.source())
                + ",\"fallback\":" + fees.fallback() + "}}";
    }

    private static long periodSeconds(String period) {
        String value = period == null ? "" : period.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.endsWith("min")) return Long.parseLong(value.substring(0, value.length() - 3)) * 60L;
        if (value.endsWith("hour")) return Long.parseLong(value.substring(0, value.length() - 4)) * 3_600L;
        if (value.endsWith("day")) return Long.parseLong(value.substring(0, value.length() - 3)) * 86_400L;
        return 0L;
    }

    private static boolean validCoverageCandle(HtxSwapKline candle) {
        return candle != null && candle.getId() != null && candle.getClose() != null && candle.getClose().signum() > 0;
    }

    /** Records the scan-level market context once per completed entry candle for later model attribution. */
    public void recordMarketSnapshot(long barTime, String breadthRegime, long breadthTotal, long breadthLong,
                                     long breadthShort, String breadthReason, String anchorRegime,
                                     int anchorValidSignals, int anchorLongSignals, int anchorShortSignals,
                                     String anchorReason) {
        if (barTime <= 0L) {
            return;
        }
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey("MARKET:15min:" + barTime + ":trend-market-v1");
        snapshot.setContractCode("MARKET");
        snapshot.setPeriod("15min");
        snapshot.setBarTime(barTime);
        snapshot.setMarketRegime(breadthRegime);
        snapshot.setFactorsJson("{\"schema\":\"trend-market-v1\",\"breadth\":{\"regime\":"
                + jsonText(breadthRegime) + ",\"total\":" + breadthTotal + ",\"long\":" + breadthLong
                + ",\"short\":" + breadthShort + ",\"reason\":" + jsonText(breadthReason)
                + "},\"anchor\":{\"regime\":" + jsonText(anchorRegime) + ",\"validSignals\":"
                + anchorValidSignals + ",\"longSignals\":" + anchorLongSignals + ",\"shortSignals\":"
                + anchorShortSignals + ",\"reason\":" + jsonText(anchorReason) + "}}");
        combinationRecorder.recordSnapshot(snapshot);
    }

    /**
     * Records the read-only P2 evidence for every configured timeframe after live entry decisions have finished.
     * It is deliberately separate from the immutable entry-decision snapshot: capturing more market data must not
     * delay a fresh signal, while both records remain queryable by contract and completed entry candle.
     */
    public Long recordMultiTimeframeSnapshot(String contractCode, String entryPeriod, long entryBarTime,
                                             String marketRegime, List<TrendCandidateSignal> timeframeSignals) {
        return recordMultiTimeframeSnapshot(contractCode, entryPeriod, entryBarTime, marketRegime, timeframeSignals, Map.of());
    }

    /**
     * Persists an execution-neutral multi-timeframe evidence snapshot. Indicator observations are
     * supplied by the post-entry scan and are never read by the live decision path.
     */
    public Long recordMultiTimeframeSnapshot(String contractCode, String entryPeriod, long entryBarTime,
                                             String marketRegime, List<TrendCandidateSignal> timeframeSignals,
                                             Map<String, HtxTrendIndicatorObservationCalculator.Observation> indicatorObservations) {
        if (contractCode == null || contractCode.isBlank() || entryBarTime <= 0L) {
            return null;
        }
        String period = entryPeriod == null || entryPeriod.isBlank() ? "15min" : entryPeriod;
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + entryBarTime + ":trend-factor-multi-v1");
        snapshot.setContractCode(contractCode);
        snapshot.setPeriod(period);
        snapshot.setBarTime(entryBarTime);
        snapshot.setMarketRegime(marketRegime == null ? "UNKNOWN" : marketRegime);
        snapshot.setFactorsJson(multiTimeframeSnapshotJson(timeframeSignals, indicatorObservations));
        return combinationRecorder.recordSnapshot(snapshot);
    }

    /**
     * Records the executable cost evidence after a combination candidate reaches the live preflight stage.
     * This is intentionally a separate immutable record: market structure is captured at signal time, while
     * account fees and the order book are only meaningful at the later execution attempt.
     */
    public void recordEntryCostEvidence(String contractCode, String period, Long barTime, String policyVersion,
                                        String marketRegime, EntryCostEvidence evidence) {
        if (contractCode == null || contractCode.isBlank() || barTime == null || barTime <= 0L
                || policyVersion == null || policyVersion.isBlank() || evidence == null) {
            return;
        }
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        String key = contractCode + ":" + period + ":" + barTime + ":" + policyVersion + ":entry-cost-v1";
        if (evidence.runId() != null && !evidence.runId().isBlank()) {
            key += ":" + evidence.runId() + ":" + evidence.gateResult();
        }
        snapshot.setSnapshotKey(key);
        snapshot.setContractCode(contractCode);
        snapshot.setPeriod(period);
        snapshot.setBarTime(barTime);
        snapshot.setMarketRegime(marketRegime == null ? "UNKNOWN" : marketRegime);
        snapshot.setFactorsJson(entryCostEvidenceJson(evidence));
        combinationRecorder.recordSnapshot(snapshot);
    }

    public void recordExecutionGate(String runId, String contractCode, String result, String reason) {
        candidateRecorder.markExecutionGate(runId, contractCode, result, reason);
    }

    /**
     * Persists quality evidence for exactly the candidate bucket being evaluated. This observation is intentionally
     * separate from the mutable execution gate: a weak or sparse bucket is evidence for later review, not a new
     * reason to block a live order in this release.
     */
    public void recordDirectionalQualityEvidence(String contractCode, String period, Long barTime, String policyVersion,
                                                 String marketRegime, DirectionalQualityEvidence evidence) {
        if (contractCode == null || contractCode.isBlank() || period == null || period.isBlank()
                || barTime == null || barTime <= 0L || policyVersion == null || policyVersion.isBlank()
                || evidence == null || evidence.runId() == null || evidence.runId().isBlank()) {
            return;
        }
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + barTime + ":" + policyVersion
                + ":trend-directional-quality-v1:" + evidence.runId());
        snapshot.setContractCode(contractCode);
        snapshot.setPeriod(period);
        snapshot.setBarTime(barTime);
        snapshot.setMarketRegime(marketRegime == null ? "UNKNOWN" : marketRegime);
        snapshot.setFactorsJson(directionalQualityEvidenceJson(evidence));
        combinationRecorder.recordSnapshot(snapshot);
    }

    /** Persists an execution-neutral price contract after all current live preflight gates have passed. */
    public void recordPricePlan(String contractCode, String period, Long barTime, String policyVersion,
                                String marketRegime, PricePlanEvidence plan) {
        if (contractCode == null || contractCode.isBlank() || barTime == null || barTime <= 0L
                || policyVersion == null || policyVersion.isBlank() || plan == null || plan.runId() == null) {
            return;
        }
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + barTime + ":" + policyVersion
                + ":price-plan-v1:" + plan.runId());
        snapshot.setContractCode(contractCode);
        snapshot.setPeriod(period);
        snapshot.setBarTime(barTime);
        snapshot.setMarketRegime(marketRegime == null ? "UNKNOWN" : marketRegime);
        snapshot.setFactorsJson(pricePlanJson(plan));
        combinationRecorder.recordSnapshot(snapshot);
        recordExternalSignal(contractCode, period, barTime, policyVersion, marketRegime, plan);
    }

    /** Emits only an immutable signal contract.  It never calls an exchange client or exposes account credentials. */
    public HtxTrendFactorSnapshotEntity latestExternalSignal(String contractCode, String period, Long barTime,
                                                              String policyVersion, String runId) {
        if (contractCode == null || period == null || barTime == null || policyVersion == null || runId == null) {
            return null;
        }
        return combinationRecorder.findLatestSnapshot(contractCode + ":" + period + ":" + barTime + ":"
                + policyVersion + ":signal-outbox-v1:ENTRY_READY:" + runId);
    }

    /** Reads the immutable plan paired with an ENTRY_READY outbox event. */
    public HtxTrendFactorSnapshotEntity latestPricePlan(String contractCode, String period, Long barTime,
                                                         String policyVersion, String runId) {
        if (contractCode == null || period == null || barTime == null || policyVersion == null || runId == null) {
            return null;
        }
        return combinationRecorder.findLatestSnapshot(contractCode + ":" + period + ":" + barTime + ":"
                + policyVersion + ":price-plan-v1:" + runId);
    }

    /**
     * Freezes the exit prices recalculated from a simulated fill. The compact key avoids exceeding the database
     * key limit while the JSON preserves the full run and execution identifiers for replay.
     */
    public Long recordPaperExitPlan(String contractCode, String period, Long barTime, String policyVersion,
                                    String marketRegime, PaperExitPlanEvidence plan) {
        if (contractCode == null || contractCode.isBlank() || period == null || period.isBlank() || barTime == null
                || barTime <= 0L || policyVersion == null || policyVersion.isBlank() || plan == null
                || plan.runId() == null || plan.runId().isBlank() || plan.executionKey() == null
                || plan.executionKey().isBlank()) {
            return null;
        }
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey("PAPER_EXIT:" + stableKey(plan.runId(), plan.executionKey()));
        snapshot.setContractCode(contractCode);
        snapshot.setPeriod(period);
        snapshot.setBarTime(barTime);
        snapshot.setMarketRegime(marketRegime == null ? "UNKNOWN" : marketRegime);
        snapshot.setFactorsJson(paperExitPlanJson(contractCode, period, barTime, policyVersion, plan));
        return combinationRecorder.recordSnapshot(snapshot);
    }

    private void recordExternalSignal(String contractCode, String period, Long barTime, String policyVersion,
                                      String marketRegime, PricePlanEvidence plan) {
        HtxTrendFactorSnapshotEntity event = new HtxTrendFactorSnapshotEntity();
        event.setSnapshotKey(contractCode + ":" + period + ":" + barTime + ":" + policyVersion
                + ":signal-outbox-v1:ENTRY_READY:" + plan.runId());
        event.setContractCode(contractCode); event.setPeriod(period); event.setBarTime(barTime);
        event.setMarketRegime(marketRegime == null ? "UNKNOWN" : marketRegime);
        event.setFactorsJson(externalSignalJson(contractCode, plan));
        combinationRecorder.recordSnapshot(event);
    }

    /** Persist a negative decision too, so a later review can distinguish missing structure from execution rejection. */
    private Selection reject(String contractCode, String period, TrendCandidateSignal signal,
                             HtxTrendCombinationGateReason reason) {
        if (signal == null || signal.barTime() == null) {
            return Selection.rejected(reason.code());
        }
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + signal.barTime()
                + ":combo-v2:REJECTED:evidence-v3");
        snapshot.setContractCode(contractCode); snapshot.setPeriod(period); snapshot.setBarTime(signal.barTime());
        snapshot.setMarketRegime(signal.regime().name());
        snapshot.setFactorsJson(factorSnapshotJson(signal, null));
        Long snapshotId = combinationRecorder.recordSnapshot(snapshot);
        HtxTrendCandidateSignalEntity entity = new HtxTrendCandidateSignalEntity();
        entity.setContractCode(contractCode); entity.setPeriod(period); entity.setBarTime(signal.barTime());
        entity.setDirection(signal.direction().name()); entity.setRegime(signal.regime().name());
        entity.setSignalScore(signal.signalScore()); entity.setClosePrice(signal.closePrice()); entity.setAdx(signal.adx());
        entity.setAtr(signal.atr()); entity.setAtrPct(signal.atrPct()); entity.setMomentumPct(signal.momentumPct());
        entity.setVolumeRatio(signal.volumeRatio()); entity.setEntryReason(signal.entryReason());
        entity.setInvalidationReason(signal.invalidationReason()); entity.setShadow(false);
        entity.setModelVersion("combo-v2:evidence-v3:COMBINATION_GATE"); entity.setPrimaryModel("NONE");
        entity.setPolicyVersion("combo-v2"); entity.setFactorSnapshotId(snapshotId);
        entity.setRunId(runId(contractCode, period, signal.barTime()));
        entity.setGateResult("REJECTED"); entity.setGateReason(reason.code());
        candidateRecorder.record(entity);
        return Selection.rejected(reason.code());
    }

    private void recordMarketEntryRankSnapshot(String contractCode, String period, TrendCandidateSignal signal,
                                                ResolvedCandidate candidate, MarketEntryRank ranking) {
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + signal.barTime() + ":trend-market-entry-rank-v1");
        snapshot.setContractCode(contractCode); snapshot.setPeriod(period); snapshot.setBarTime(signal.barTime());
        snapshot.setMarketRegime(ranking.marketRegime());
        snapshot.setFactorsJson("{\"schema\":\"trend-market-entry-rank-v1\",\"baseScore\":"
                + jsonNumber(candidate.baseScore()) + ",\"effectiveScore\":" + jsonNumber(candidate.candidate().score())
                + ",\"primaryModel\":" + jsonText(candidate.candidate().primaryModel()) + ",\"direction\":"
                + jsonText(candidate.candidate().direction().name()) + ",\"marketRegime\":" + jsonText(ranking.marketRegime())
                + ",\"breadthRegime\":" + jsonText(ranking.breadthRegime()) + ",\"anchorRegime\":"
                + jsonText(ranking.anchorRegime()) + ",\"adjustment\":" + jsonNumber(candidate.marketAdjustment()) + "}");
        combinationRecorder.recordSnapshot(snapshot);
    }

    private ResolvedCandidate resolve(CombinationCandidate candidate, List<HtxTrendStrategyPolicyEntity> policies,
                                      MarketEntryRank ranking) {
        BigDecimal adjustment = ranking.adjustment(candidate.direction().name(), candidate.primaryModel());
        CombinationCandidate effectiveCandidate = candidate.withScore(candidate.score().add(adjustment)
                .max(BigDecimal.ZERO).min(new BigDecimal("100")));
        return policies.stream()
                .filter(policy -> matches(policy, candidate))
                .filter(policy -> candidate.score().compareTo(policy.getScoreThreshold()) >= 0)
                .map(policy -> new ResolvedCandidate(effectiveCandidate, policy, candidate.score(), adjustment))
                .findFirst().orElse(null);
    }

    private static boolean matches(HtxTrendStrategyPolicyEntity policy, CombinationCandidate candidate) {
        return policy.getPrimaryModel().equals(candidate.primaryModel())
                && policy.getConfirmationModels().equals(candidate.confirmationModels())
                && policy.getBaseNotionalUsdt() != null && policy.getBaseNotionalUsdt().signum() > 0
                && policy.getMaxNotionalUsdt() != null && policy.getMaxNotionalUsdt().signum() > 0
                && policy.getScoreThreshold() != null;
    }

    private Long recordSnapshot(String contractCode, String period, TrendCandidateSignal signal, ResolvedCandidate resolved) {
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + signal.barTime() + ":"
                + resolved.policy().getPolicyVersion() + ":" + resolved.policy().getPolicyCode() + ":evidence-v3");
        snapshot.setContractCode(contractCode);
        snapshot.setPeriod(period);
        snapshot.setBarTime(signal.barTime());
        snapshot.setMarketRegime(resolved.candidate().marketRegime());
        snapshot.setFactorsJson(factorSnapshotJson(signal, resolved.candidate()));
        return combinationRecorder.recordSnapshot(snapshot);
    }

    /**
     * Stores all factors that actually participated in this entry decision. In particular, a Fibonacci
     * candidate keeps the prior breakout candle and impulse range rather than only the derived 0.382/0.618 zone.
     */
    static String factorSnapshotJson(TrendCandidateSignal signal, CombinationCandidate candidate) {
        HtxTrendCombinationCalculator.FibonacciEvidence fibonacci = candidate == null
                ? HtxTrendCombinationCalculator.FibonacciEvidence.notApplicable(0, 0)
                : candidate.fibonacciEvidence();
        return "{\"schema\":\"trend-factor-v3\",\"period\":" + jsonText(signal.period())
                + ",\"direction\":" + jsonText(signal.direction().name())
                + ",\"regime\":" + jsonText(signal.regime().name())
                + ",\"signalScore\":" + jsonNumber(signal.signalScore())
                + ",\"longScore\":" + jsonNumber(signal.longScore())
                + ",\"shortScore\":" + jsonNumber(signal.shortScore())
                + ",\"scoreGap\":" + jsonNumber(signal.scoreGap())
                + ",\"close\":" + jsonNumber(signal.closePrice())
                + ",\"ema\":{\"fast\":" + jsonNumber(signal.emaFast())
                + ",\"medium\":" + jsonNumber(signal.emaMedium())
                + ",\"slow\":" + jsonNumber(signal.emaSlow()) + "}"
                + ",\"volatility\":{\"atr\":" + jsonNumber(signal.atr())
                + ",\"atrPct\":" + jsonNumber(signal.atrPct())
                + ",\"adx\":" + jsonNumber(signal.adx())
                + ",\"momentumPct\":" + jsonNumber(signal.momentumPct())
                + ",\"volumeRatio\":" + jsonNumber(signal.volumeRatio()) + "}"
                + ",\"donchian\":{\"high\":" + jsonNumber(signal.breakoutHigh())
                + ",\"low\":" + jsonNumber(signal.breakoutLow()) + "}"
                + ",\"structure\":{\"boxHigh\":" + jsonNumber(candidate == null ? null : candidate.boxHigh())
                + ",\"boxLow\":" + jsonNumber(candidate == null ? null : candidate.boxLow())
                + ",\"swingHigh\":" + jsonNumber(candidate == null ? null : candidate.swingHigh())
                + ",\"swingLow\":" + jsonNumber(candidate == null ? null : candidate.swingLow())
                + ",\"invalidationPrice\":" + jsonNumber(candidate == null ? null : candidate.invalidationPrice()) + "}"
                + ",\"fibonacci\":{\"applicable\":" + (candidate != null
                && "FIB_PULLBACK_CONTINUATION".equals(candidate.primaryModel()))
                + ",\"priorBreakoutBarTime\":" + jsonNumber(fibonacci.breakoutBarTime())
                + ",\"impulseHigh\":" + jsonNumber(fibonacci.impulseHigh())
                + ",\"impulseLow\":" + jsonNumber(fibonacci.impulseLow())
                + ",\"retracementLower\":" + jsonNumber(fibonacci.retracementLower())
                + ",\"retracementUpper\":" + jsonNumber(fibonacci.retracementUpper())
                + ",\"emaReclaimConfirmed\":" + fibonacci.emaReclaimConfirmed()
                + ",\"boxWindowBars\":" + fibonacci.boxWindowBars()
                + ",\"sourceLookbackBars\":" + fibonacci.sourceLookbackBars() + "}"
                + ",\"targets\":{\"zoneLow\":" + jsonNumber(candidate == null ? null : candidate.targetZoneLow())
                + ",\"zoneHigh\":" + jsonNumber(candidate == null ? null : candidate.targetZoneHigh())
                + ",\"boxMeasuredMove\":" + jsonNumber(candidate == null ? null : candidate.boxMeasuredMoveTarget())
                + ",\"fib1272\":" + jsonNumber(candidate == null ? null : candidate.fibExtension1272())
                + ",\"fib1618\":" + jsonNumber(candidate == null ? null : candidate.fibExtension1618())
                + ",\"expectedR\":" + jsonNumber(candidate == null ? null : candidate.expectedRMultiple()) + "}"
                + ",\"trendState\":" + trendStateJson(signal, candidate)
                + ",\"entryCandleQuality\":" + entryCandleQualityJson(candidate)
                + ",\"modelEvidence\":" + modelEvidenceJson(candidate, fibonacci)
                + "}";
    }

    /** Captured for replay only; live policy and sizing deliberately do not consume these fields yet. */
    private static String entryCandleQualityJson(CombinationCandidate candidate) {
        HtxTrendCombinationCalculator.EntryCandleQuality quality = candidate == null ? null : candidate.entryCandleQuality();
        if (quality == null || quality.close() == null) {
            return "{\"status\":\"NOT_AVAILABLE\",\"reason\":\"entry candle OHLC unavailable\"}";
        }
        return "{\"status\":\"AVAILABLE\",\"open\":" + jsonNumber(quality.open())
                + ",\"high\":" + jsonNumber(quality.high()) + ",\"low\":" + jsonNumber(quality.low())
                + ",\"close\":" + jsonNumber(quality.close()) + ",\"bodyToRange\":" + jsonNumber(quality.bodyToRange())
                + ",\"upperWickToRange\":" + jsonNumber(quality.upperWickToRange())
                + ",\"lowerWickToRange\":" + jsonNumber(quality.lowerWickToRange())
                + ",\"rangeToAtr\":" + jsonNumber(quality.rangeToAtr())
                + ",\"closeLocation\":" + jsonNumber(quality.closeLocation()) + "}";
    }

    /**
     * Read-only market-state evidence.  This intentionally does not create a new score or gate: it makes the
     * facts and unavailable inputs explicit so later policy changes can be replayed against the original snapshot.
     */
    private static String trendStateJson(TrendCandidateSignal signal, CombinationCandidate candidate) {
        String structureStatus = candidate == null ? "NOT_EVALUATED" : "AVAILABLE";
        return "{\"direction\":{" + statusJson("AVAILABLE", null) + ",\"value\":"
                + jsonText(signal.direction() == null ? null : signal.direction().name()) + "}"
                + ",\"regime\":{" + statusJson("AVAILABLE", null) + ",\"value\":"
                + jsonText(signal.regime() == null ? null : signal.regime().name()) + "}"
                + ",\"trendStrength\":{" + statusJson("AVAILABLE", null) + ",\"adx\":"
                + jsonNumber(signal.adx()) + ",\"emaAlignment\":" + emaAlignmentJson(signal) + "}"
                + ",\"structureQuality\":{" + statusJson(structureStatus,
                candidate == null ? "combination structure was not evaluated" : null) + ",\"boxHigh\":"
                + jsonNumber(candidate == null ? null : candidate.boxHigh()) + ",\"boxLow\":"
                + jsonNumber(candidate == null ? null : candidate.boxLow()) + ",\"swingHigh\":"
                + jsonNumber(candidate == null ? null : candidate.swingHigh()) + ",\"swingLow\":"
                + jsonNumber(candidate == null ? null : candidate.swingLow()) + "}"
                + ",\"momentumQuality\":{" + statusJson("AVAILABLE", null) + ",\"roc\":"
                + jsonNumber(signal.momentumPct()) + ",\"scoreGap\":" + jsonNumber(signal.scoreGap())
                + ",\"modelMomentumScore\":" + jsonNumber(candidate == null ? null : candidate.momentumQualityScore()) + "}"
                + ",\"participationQuality\":{" + statusJson("AVAILABLE", null) + ",\"volumeRatio\":"
                + jsonNumber(signal.volumeRatio()) + "}"
                + ",\"derivativesQuality\":{" + statusJson("NOT_AVAILABLE",
                "funding, open-interest and liquidation inputs are not in this snapshot") + "}"
                + ",\"marketAlignment\":{" + statusJson("AVAILABLE", null) + ",\"marketRegime\":"
                + jsonText(candidate == null ? signal.regime().name() : candidate.marketRegime()) + "}"
                + ",\"crowdingRisk\":{" + statusJson("NOT_AVAILABLE",
                "crowding data source is not connected") + "}"
                + ",\"executionQuality\":{" + statusJson("NOT_EVALUATED",
                "entry cost evidence is captured only after execution preflight") + "}}";
    }

    /**
     * Describes model roles without changing combination scores.  BOX, Donchian and Fibonacci can refer to the
     * same breakout/retest structure, so confirmations in the same group are not independent alpha sources.
     */
    private static String modelEvidenceJson(CombinationCandidate candidate,
                                            HtxTrendCombinationCalculator.FibonacciEvidence fibonacci) {
        if (candidate == null) {
            return "{\"status\":\"NOT_EVALUATED\",\"reason\":\"no combination candidate\","
                    + "\"independentAlphaCount\":0,\"structureCorrelation\":[]}";
        }
        String correlationGroup = structureCorrelationGroup(candidate, fibonacci);
        String primaryRole = "PRIMARY_ALPHA";
        String confirmations = confirmationEvidenceJson(candidate.confirmationModels(), correlationGroup);
        return "{\"status\":\"AVAILABLE\",\"primary\":{\"model\":" + jsonText(candidate.primaryModel())
                + ",\"role\":" + jsonText(primaryRole) + ",\"correlationGroup\":" + jsonText(correlationGroup)
                + "},\"confirmations\":" + confirmations + ",\"conflicts\":[],\"independentAlphaCount\":1,"
                + "\"structureCorrelation\":[{\"group\":" + jsonText(correlationGroup)
                + ",\"classification\":\"SAME_STRUCTURE\",\"primaryModel\":" + jsonText(candidate.primaryModel())
                + ",\"confirmationModels\":" + confirmationModelNamesJson(candidate.confirmationModels()) + "}]}";
    }

    private static String confirmationEvidenceJson(String confirmationModels, String correlationGroup) {
        List<String> models = confirmationModels == null || confirmationModels.isBlank() ? List.of()
                : Arrays.stream(confirmationModels.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < models.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"model\":").append(jsonText(models.get(index)))
                    .append(",\"role\":\"CONFIRMATION_SAME_STRUCTURE\",\"correlationGroup\":")
                    .append(jsonText(correlationGroup)).append('}');
        }
        return json.append(']').toString();
    }

    private static String confirmationModelNamesJson(String confirmationModels) {
        List<String> models = confirmationModels == null || confirmationModels.isBlank() ? List.of()
                : Arrays.stream(confirmationModels.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < models.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(jsonText(models.get(index)));
        }
        return json.append(']').toString();
    }

    private static String structureCorrelationGroup(CombinationCandidate candidate,
                                                    HtxTrendCombinationCalculator.FibonacciEvidence fibonacci) {
        if ("FIB_PULLBACK_CONTINUATION".equals(candidate.primaryModel())
                && fibonacci.breakoutBarTime() != null && fibonacci.breakoutBarTime() > 0L) {
            return "BREAKOUT_RETEST:" + fibonacci.breakoutBarTime();
        }
        return "BREAKOUT:" + candidate.direction().name() + ":" + candidate.boxHigh() + ":" + candidate.boxLow();
    }

    private static String emaAlignmentJson(TrendCandidateSignal signal) {
        if (signal.emaFast() == null || signal.emaMedium() == null || signal.emaSlow() == null) {
            return jsonText("NOT_AVAILABLE");
        }
        if (signal.emaFast().compareTo(signal.emaMedium()) >= 0
                && signal.emaMedium().compareTo(signal.emaSlow()) >= 0) {
            return jsonText("BULLISH_STACK");
        }
        if (signal.emaFast().compareTo(signal.emaMedium()) <= 0
                && signal.emaMedium().compareTo(signal.emaSlow()) <= 0) {
            return jsonText("BEARISH_STACK");
        }
        return jsonText("MIXED");
    }

    private static String statusJson(String status, String reason) {
        return "\"status\":" + jsonText(status) + (reason == null ? "" : ",\"reason\":" + jsonText(reason));
    }

    private static String multiTimeframeSnapshotJson(List<TrendCandidateSignal> signals,
                                                      Map<String, HtxTrendIndicatorObservationCalculator.Observation> observations) {
        StringBuilder json = new StringBuilder("{\"schema\":\"trend-factor-multi-v2\",\"readOnly\":true,\"source\":\"htx-completed-kline\",\"macro\":{\"ahr999\":{\"status\":\"NOT_AVAILABLE\",\"reason\":\"canonical AHR999 feed is not configured; HTX price-only data is not treated as AHR999\"}},\"timeframes\":{");
        boolean first = true;
        if (signals != null) {
            for (TrendCandidateSignal signal : signals) {
                if (signal == null || signal.period() == null || signal.period().isBlank()) {
                    continue;
                }
                if (!first) {
                    json.append(',');
                }
                first = false;
                boolean available = signal.barTime() != null && signal.barTime() > 0L;
                HtxTrendIndicatorObservationCalculator.Observation observation = observations == null
                        ? null : observations.get(signal.period());
                json.append(jsonText(signal.period())).append(":{")
                        .append("\"status\":").append(jsonText(available ? "COMPLETE" : "UNAVAILABLE"))
                        .append(",\"barTime\":").append(jsonNumber(signal.barTime()))
                        .append(",\"direction\":").append(jsonText(signal.direction() == null ? null : signal.direction().name()))
                        .append(",\"regime\":").append(jsonText(signal.regime() == null ? null : signal.regime().name()))
                        .append(",\"signalScore\":").append(jsonNumber(signal.signalScore()))
                        .append(",\"longScore\":").append(jsonNumber(signal.longScore()))
                        .append(",\"shortScore\":").append(jsonNumber(signal.shortScore()))
                        .append(",\"scoreGap\":").append(jsonNumber(signal.scoreGap()))
                        .append(",\"close\":").append(jsonNumber(signal.closePrice()))
                        .append(",\"ema\":{\"fast\":").append(jsonNumber(signal.emaFast()))
                        .append(",\"medium\":").append(jsonNumber(signal.emaMedium()))
                        .append(",\"slow\":").append(jsonNumber(signal.emaSlow())).append('}')
                        .append(",\"volatility\":{\"atr\":").append(jsonNumber(signal.atr()))
                        .append(",\"atrPct\":").append(jsonNumber(signal.atrPct()))
                        .append(",\"adx\":").append(jsonNumber(signal.adx())).append('}')
                        .append(",\"momentum\":{\"roc\":").append(jsonNumber(signal.momentumPct()))
                        .append(",\"volumeRatio\":").append(jsonNumber(signal.volumeRatio())).append('}')
                        .append(",\"donchian\":{\"high\":").append(jsonNumber(signal.breakoutHigh()))
                        .append(",\"low\":").append(jsonNumber(signal.breakoutLow())).append('}')
                        .append(",\"indicatorObservation\":").append(indicatorObservationJson(observation))
                        .append(",\"reason\":").append(jsonText(available ? signal.entryReason() : signal.invalidationReason()))
                        .append('}');
            }
        }
        return json.append("}}").toString();
    }

    private static String indicatorObservationJson(HtxTrendIndicatorObservationCalculator.Observation observation) {
        if (observation == null) {
            return "{\"status\":\"NOT_CAPTURED\",\"reason\":\"indicator observer did not run\"}";
        }
        HtxTrendIndicatorObservationCalculator.RsiObservation rsi = observation.rsi();
        HtxTrendIndicatorObservationCalculator.MacdObservation macd = observation.macd();
        HtxTrendIndicatorObservationCalculator.ParticipationObservation participation = observation.participation();
        HtxTrendIndicatorObservationCalculator.PriceActionObservation action = observation.priceAction();
        return "{\"schema\":\"trend-indicator-observation-v1\",\"status\":" + jsonText(observation.status())
                + ",\"reason\":" + jsonText(observation.reason()) + ",\"completedBars\":" + observation.completedBars()
                + ",\"barTime\":" + jsonNumber(observation.barTime()) + ",\"close\":" + jsonNumber(observation.close())
                + ",\"rsi\":{\"status\":" + jsonText(rsi.status()) + ",\"value\":" + jsonNumber(rsi.value())
                + ",\"zone\":" + jsonText(rsi.zone()) + ",\"reason\":" + jsonText(rsi.reason()) + "}"
                + ",\"macd\":{\"status\":" + jsonText(macd.status()) + ",\"line\":" + jsonNumber(macd.line())
                + ",\"signal\":" + jsonNumber(macd.signal()) + ",\"histogram\":" + jsonNumber(macd.histogram())
                + ",\"histogramSlope\":" + jsonNumber(macd.histogramSlope()) + ",\"acceleration\":"
                + jsonText(macd.acceleration()) + ",\"reason\":" + jsonText(macd.reason()) + "}"
                + ",\"participation\":{\"status\":" + jsonText(participation.status()) + ",\"volumeRatio\":"
                + jsonNumber(participation.volumeRatio()) + ",\"turnoverRatio\":" + jsonNumber(participation.turnoverRatio())
                + ",\"recentToPriorVolumeRatio\":" + jsonNumber(participation.recentToPriorVolumeRatio())
                + ",\"confirmation\":" + jsonText(participation.confirmation()) + ",\"divergence\":"
                + jsonText(participation.divergence()) + ",\"reason\":" + jsonText(participation.reason()) + "}"
                + ",\"priceAction\":{\"status\":" + jsonText(action.status()) + ",\"closeLocation\":"
                + jsonNumber(action.closeLocation()) + ",\"bodyRatio\":" + jsonNumber(action.bodyRatio())
                + ",\"upperWickRatio\":" + jsonNumber(action.upperWickRatio()) + ",\"lowerWickRatio\":"
                + jsonNumber(action.lowerWickRatio()) + ",\"reason\":" + jsonText(action.reason()) + "}}";
    }

    private static String entryCostEvidenceJson(EntryCostEvidence evidence) {
        return "{\"schema\":\"trend-entry-cost-v1\",\"runId\":" + jsonText(evidence.runId()) + ",\"account\":{\"label\":"
                + jsonText(evidence.accountLabel()) + ",\"feeSource\":" + jsonText(evidence.feeSource())
                + ",\"openTakerRate\":" + jsonNumber(evidence.openTakerRate())
                + ",\"closeTakerRate\":" + jsonNumber(evidence.closeTakerRate())
                + ",\"effectiveTakerRate\":" + jsonNumber(evidence.effectiveTakerRate()) + "}"
                + ",\"orderBook\":{\"bestBid\":" + jsonNumber(evidence.bestBid())
                + ",\"bestAsk\":" + jsonNumber(evidence.bestAsk())
                + ",\"spreadRate\":" + jsonNumber(evidence.spreadRate())
                + ",\"depthLevels\":" + evidence.depthLevels()
                + ",\"availableContracts\":" + jsonNumber(evidence.availableContracts())
                + ",\"requiredContracts\":" + jsonNumber(evidence.requiredContracts())
                + ",\"coverageRatio\":" + jsonNumber(evidence.depthCoverageRatio()) + "}"
                + ",\"entry\":{\"side\":" + jsonText(evidence.side())
                + ",\"price\":" + jsonNumber(evidence.entryPrice())
                + ",\"volume\":" + evidence.volume()
                + ",\"notionalUsdt\":" + jsonNumber(evidence.notionalUsdt()) + "}"
                + ",\"expectation\":{\"atrMove\":" + jsonNumber(evidence.expectedMove())
                + ",\"grossPnl\":" + jsonNumber(evidence.expectedGrossPnl())
                + ",\"entryFee\":" + jsonNumber(evidence.estimatedEntryFee())
                + ",\"closeFee\":" + jsonNumber(evidence.estimatedCloseFee())
                + ",\"spreadCost\":" + jsonNumber(evidence.estimatedSpreadCost())
                + ",\"netPnlAfterCosts\":" + jsonNumber(evidence.expectedNetAfterCosts())
                + ",\"requiredMove\":" + jsonNumber(evidence.requiredMove())
                + ",\"gateResult\":" + jsonText(evidence.gateResult())
                + ",\"gateReason\":" + jsonText(evidence.gateReason()) + "}}";
    }

    private static String directionalQualityEvidenceJson(DirectionalQualityEvidence evidence) {
        return "{\"schema\":\"trend-directional-quality-v1\",\"runId\":" + jsonText(evidence.runId())
                + ",\"scope\":{\"accountLabel\":" + jsonText(evidence.accountLabel())
                + ",\"side\":" + jsonText(evidence.side())
                + ",\"primaryModel\":" + jsonText(evidence.primaryModel())
                + ",\"marketRegime\":" + jsonText(evidence.marketRegime()) + "}"
                + ",\"quality\":{\"regime\":" + jsonText(evidence.qualityRegime())
                + ",\"reason\":" + jsonText(evidence.reason())
                + ",\"closedCount\":" + evidence.closedCount()
                + ",\"winCount\":" + evidence.winCount()
                + ",\"lossCount\":" + evidence.lossCount()
                + ",\"profitFactor\":" + jsonNumber(evidence.profitFactor())
                + ",\"payoffRatio\":" + jsonNumber(evidence.payoffRatio())
                + ",\"averageWin\":" + jsonNumber(evidence.averageWin())
                + ",\"averageLoss\":" + jsonNumber(evidence.averageLoss())
                + ",\"expectancy\":" + jsonNumber(evidence.expectancy())
                + ",\"netProfit\":" + jsonNumber(evidence.netProfit()) + "}}";
    }

    private static String pricePlanJson(PricePlanEvidence plan) {
        return "{\"schema\":\"trend-price-plan-v1\",\"runId\":" + jsonText(plan.runId())
                + ",\"timeframeProfile\":" + jsonText(plan.timeframeProfile())
                + ",\"orderMode\":\"MARKET_BASELINE\",\"entry\":{\"triggerPrice\":"
                + jsonNumber(plan.triggerPrice()) + ",\"idealPrice\":" + jsonNumber(plan.idealPrice())
                + ",\"maxAcceptablePrice\":" + jsonNumber(plan.maxAcceptablePrice())
                + ",\"validUntilEpoch\":" + jsonNumber(plan.validUntilEpoch())
                + ",\"side\":" + jsonText(plan.side())
                + ",\"contractSize\":" + jsonNumber(plan.contractSize())
                + ",\"plannedContracts\":" + plan.plannedContracts() + "}"
                + ",\"exit\":{\"invalidationPrice\":" + jsonNumber(plan.invalidationPrice())
                + ",\"initialStopPrice\":" + jsonNumber(plan.initialStopPrice())
                + ",\"target1RPrice\":" + jsonNumber(plan.target1RPrice())
                + ",\"target2RPrice\":" + jsonNumber(plan.target2RPrice())
                + ",\"structureTargetPrice\":" + jsonNumber(plan.structureTargetPrice()) + "}"
                + ",\"risk\":{\"maxSlippageBps\":0,\"plannedRiskUsdt\":" + jsonNumber(plan.plannedRiskUsdt())
                + ",\"netRAtStructureTarget\":" + jsonNumber(plan.netRAtStructureTarget())
                + ",\"estimatedRoundTripCostUsdt\":" + jsonNumber(plan.estimatedRoundTripCostUsdt()) + "}"
                + ",\"status\":\"FINAL_GATE_PASSED\",\"note\":\"read-only baseline; does not alter live order mode\"}";
    }

    private static String externalSignalJson(String contractCode, PricePlanEvidence plan) {
        return "{\"schema\":\"trend-external-signal-v1\",\"eventType\":\"ENTRY_READY\",\"mode\":\"SIGNAL_ONLY\","
                + "\"runId\":" + jsonText(plan.runId()) + ",\"contractCode\":" + jsonText(contractCode)
                + ",\"side\":" + jsonText(plan.side()) + ",\"timeframeProfile\":" + jsonText(plan.timeframeProfile()) + ",\"validUntilEpoch\":"
                + jsonNumber(plan.validUntilEpoch()) + ",\"pricePlanRef\":" + jsonText("trend-price-plan-v1")
                + ",\"executionBoundary\":\"NO_EXCHANGE_ORDER\"}";
    }

    private static String paperExitPlanJson(String contractCode, String period, Long barTime, String policyVersion,
                                            PaperExitPlanEvidence plan) {
        return "{\"schema\":\"paper-exit-plan-v1\",\"source\":{\"runId\":"
                + jsonText(plan.runId()) + ",\"executionKey\":" + jsonText(plan.executionKey())
                + ",\"contractCode\":" + jsonText(contractCode) + ",\"period\":" + jsonText(period)
                + ",\"barTime\":" + jsonNumber(barTime) + ",\"policyVersion\":" + jsonText(policyVersion)
                + ",\"pricePlanRef\":\"trend-price-plan-v1\"},\"entry\":{\"side\":"
                + jsonText(plan.side()) + ",\"fillPrice\":" + jsonNumber(plan.fillPrice())
                + ",\"filledContracts\":" + plan.filledContracts() + ",\"contractSize\":"
                + jsonNumber(plan.contractSize()) + "},\"exit\":{\"invalidationPrice\":"
                + jsonNumber(plan.invalidationPrice()) + ",\"initialStopPrice\":" + jsonNumber(plan.initialStopPrice())
                + ",\"target1RPrice\":" + jsonNumber(plan.target1RPrice()) + ",\"target2RPrice\":"
                + jsonNumber(plan.target2RPrice()) + ",\"structureTargetPrice\":"
                + jsonNumber(plan.structureTargetPrice()) + "},\"risk\":{\"distance\":"
                + jsonNumber(plan.riskDistance()) + ",\"plannedRiskUsdt\":" + jsonNumber(plan.plannedRiskUsdt())
                + ",\"structureGrossPnlUsdt\":" + jsonNumber(plan.structureGrossPnlUsdt())
                + ",\"estimatedRoundTripCostUsdt\":" + jsonNumber(plan.estimatedRoundTripCostUsdt())
                + ",\"netRAtStructureTarget\":" + jsonNumber(plan.netRAtStructureTarget()) + "}}";
    }

    private static String stableKey(String runId, String executionKey) {
        try {
            byte[] input = (runId + "\u0000" + executionKey).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String jsonNumber(Number value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal decimal) {
            int scale = Math.min(Math.max(decimal.scale(), 0), EVIDENCE_FRACTION_SCALE);
            return decimal.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private static String jsonText(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String jsonTextArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return "[" + values.stream().map(HtxTrendCombinationService::jsonText)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String jsonModelEvidenceArray(List<HtxTrendModelAnalyzer.ModelEvidence> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return "[" + values.stream().map(value -> "{\"model\":" + jsonText(value.model())
                        + ",\"structureId\":" + jsonText(value.structureId())
                        + ",\"entryRole\":" + jsonText(value.contract().entryRole().name())
                        + ",\"marketRole\":" + jsonText(value.contract().marketRole())
                        + ",\"timeframeProfile\":" + jsonText(value.contract().timeframeProfile())
                        + ",\"preferredEntryStyle\":" + jsonText(value.contract().preferredEntryStyle().name())
                        + ",\"invalidationBasis\":" + jsonText(value.contract().invalidationBasis().name())
                        + ",\"targetBasis\":" + jsonText(value.contract().targetBasis().name())
                        + ",\"exitAuthority\":" + jsonText(value.contract().exitAuthority().name()) + "}")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private void recordCandidate(String contractCode, String period, TrendCandidateSignal signal, ResolvedCandidate resolved,
                                 Long snapshotId, String runId, String result, String reason) {
        HtxTrendCandidateSignalEntity entity = new HtxTrendCandidateSignalEntity();
        entity.setContractCode(contractCode); entity.setPeriod(period); entity.setBarTime(signal.barTime());
        entity.setDirection(signal.direction().name()); entity.setRegime(resolved.candidate().marketRegime());
        entity.setSignalScore(resolved.candidate().score()); entity.setClosePrice(signal.closePrice());
        entity.setAdx(signal.adx()); entity.setAtr(signal.atr()); entity.setAtrPct(signal.atrPct());
        entity.setMomentumPct(signal.momentumPct()); entity.setVolumeRatio(signal.volumeRatio());
        entity.setBreakoutHigh(signal.breakoutHigh()); entity.setBreakoutLow(signal.breakoutLow());
        entity.setEmaFast(signal.emaFast()); entity.setEmaMedium(signal.emaMedium()); entity.setEmaSlow(signal.emaSlow());
        entity.setEntryReason(signal.entryReason()); entity.setInvalidationReason(signal.invalidationReason());
        entity.setShadow(false); entity.setModelVersion(resolved.policy().getPolicyVersion()
                + ":evidence-v3:" + resolved.policy().getPolicyCode());
        entity.setPrimaryModel(resolved.candidate().primaryModel()); entity.setConfirmationModels(resolved.candidate().confirmationModels());
        entity.setRunId(runId);
        entity.setPolicyVersion(resolved.policy().getPolicyVersion()); entity.setFactorSnapshotId(snapshotId);
        entity.setGateResult(result); entity.setGateReason(reason); entity.setInvalidationPrice(resolved.candidate().invalidationPrice());
        entity.setEntryHypothesis(resolved.candidate().hypothesis());
        entity.setSizingMultiplier(sizingMultiplier(resolved.candidate().score(), resolved.candidate().marketRegime()));
        entity.setPlannedNotionalUsdt(resolved.policy().getBaseNotionalUsdt().multiply(entity.getSizingMultiplier())
                .min(resolved.policy().getMaxNotionalUsdt()));
        entity.setTargetZoneLow(resolved.candidate().targetZoneLow()); entity.setTargetZoneHigh(resolved.candidate().targetZoneHigh());
        entity.setBoxMeasuredMoveTarget(resolved.candidate().boxMeasuredMoveTarget());
        entity.setFibExtension1272(resolved.candidate().fibExtension1272()); entity.setFibExtension1618(resolved.candidate().fibExtension1618());
        entity.setExpectedRMultiple(resolved.candidate().expectedRMultiple());
        entity.setMomentumQualityScore(resolved.candidate().momentumQualityScore());
        candidateRecorder.record(entity);
    }

    private static BigDecimal sizingMultiplier(BigDecimal score, String regime) {
        if ("MIXED".equals(regime)) return new BigDecimal("0.5");
        if (score.compareTo(new BigDecimal("95")) >= 0) return new BigDecimal("1.5");
        if (score.compareTo(new BigDecimal("90")) >= 0) return new BigDecimal("1.25");
        return BigDecimal.ONE;
    }

    private static String runId(String contractCode, String period, Long barTime) {
        return "trend-entry:" + contractCode + ":" + period + ":" + barTime;
    }

    private record ResolvedCandidate(CombinationCandidate candidate, HtxTrendStrategyPolicyEntity policy,
                                     BigDecimal baseScore, BigDecimal marketAdjustment) { }

    public record MarketEntryRank(String marketRegime, String breadthRegime, String anchorRegime,
                                  String alignedSide, BigDecimal alignedBonus, BigDecimal counterPenalty,
                                  BigDecimal mixedPenalty) {
        public static MarketEntryRank neutral() {
            return new MarketEntryRank("MIXED", "MIXED", "MIXED", "FLAT", BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal adjustment(String direction, String model) {
            if (direction == null || alignedSide == null || "FLAT".equals(alignedSide)) return mixedPenalty.negate();
            BigDecimal modelWeight = "DONCHIAN_BREAKOUT".equals(model) ? new BigDecimal("1.20")
                    : "EMA_VWAP_MOMENTUM_CONTINUATION".equals(model) ? BigDecimal.ONE
                    : new BigDecimal("0.80");
            return direction.equals(alignedSide) ? alignedBonus.multiply(modelWeight)
                    : counterPenalty.multiply(modelWeight).negate();
        }
    }

    public record Selection(CombinationCandidate candidate, HtxTrendStrategyPolicyEntity policy, Long factorSnapshotId,
                            BigDecimal sizingMultiplier, BigDecimal plannedNotionalUsdt, String runId, String rejection) {
        /** Compatibility constructor for callers that only hold structural evidence. */
        public Selection(CombinationCandidate candidate, HtxTrendStrategyPolicyEntity policy, Long factorSnapshotId,
                         BigDecimal sizingMultiplier, BigDecimal plannedNotionalUsdt, String rejection) {
            this(candidate, policy, factorSnapshotId, sizingMultiplier, plannedNotionalUsdt, null, rejection);
        }
        static Selection selected(CombinationCandidate candidate, HtxTrendStrategyPolicyEntity policy, Long snapshotId,
                                  BigDecimal multiplier, BigDecimal notional, String runId) {
            return new Selection(candidate, policy, snapshotId, multiplier, notional, runId, null);
        }
        static Selection rejected(String rejection) { return new Selection(null, null, null, null, null, null, rejection); }
        public boolean accepted() { return candidate != null; }
    }

    public record EntryCostEvidence(String runId, String accountLabel, String feeSource, BigDecimal openTakerRate,
                                    BigDecimal closeTakerRate, BigDecimal effectiveTakerRate,
                                    BigDecimal bestBid, BigDecimal bestAsk, BigDecimal spreadRate,
                                    int depthLevels, BigDecimal availableContracts, BigDecimal requiredContracts,
                                    BigDecimal depthCoverageRatio, String side, BigDecimal entryPrice, long volume,
                                    BigDecimal notionalUsdt, BigDecimal expectedMove, BigDecimal expectedGrossPnl,
                                    BigDecimal estimatedEntryFee, BigDecimal estimatedCloseFee,
                                    BigDecimal estimatedSpreadCost, BigDecimal expectedNetAfterCosts,
                                    BigDecimal requiredMove, String gateResult, String gateReason) {
        /** Compatibility constructor for historical tests and callers without a scan identifier. */
        public EntryCostEvidence(String accountLabel, String feeSource, BigDecimal openTakerRate,
                                 BigDecimal closeTakerRate, BigDecimal effectiveTakerRate,
                                 BigDecimal bestBid, BigDecimal bestAsk, BigDecimal spreadRate,
                                 int depthLevels, BigDecimal availableContracts, BigDecimal requiredContracts,
                                 BigDecimal depthCoverageRatio, String side, BigDecimal entryPrice, long volume,
                                 BigDecimal notionalUsdt, BigDecimal expectedMove, BigDecimal expectedGrossPnl,
                                 BigDecimal estimatedEntryFee, BigDecimal estimatedCloseFee,
                                 BigDecimal estimatedSpreadCost, BigDecimal expectedNetAfterCosts,
                                 BigDecimal requiredMove, String gateResult, String gateReason) {
            this(null, accountLabel, feeSource, openTakerRate, closeTakerRate, effectiveTakerRate,
                    bestBid, bestAsk, spreadRate, depthLevels, availableContracts, requiredContracts,
                    depthCoverageRatio, side, entryPrice, volume, notionalUsdt, expectedMove, expectedGrossPnl,
                    estimatedEntryFee, estimatedCloseFee, estimatedSpreadCost, expectedNetAfterCosts,
                    requiredMove, gateResult, gateReason);
        }
    }

    public record DirectionalQualityEvidence(String runId, String accountLabel, String side, String primaryModel,
                                             String marketRegime, String qualityRegime, String reason,
                                             long closedCount, long winCount, long lossCount,
                                             BigDecimal profitFactor, BigDecimal payoffRatio,
                                             BigDecimal averageWin, BigDecimal averageLoss,
                                             BigDecimal expectancy, BigDecimal netProfit) {
    }

    /** Account-specific cost basis used only for read-only forward-return attribution. */
    public record ModelCoverageFeeContext(String accountLabel, BigDecimal openTakerFeeRate,
                                          BigDecimal closeTakerFeeRate, String source, boolean fallback) {
        static ModelCoverageFeeContext unavailable() {
            return new ModelCoverageFeeContext("primary", BigDecimal.ZERO, BigDecimal.ZERO,
                    "UNAVAILABLE", true);
        }
    }

    public record PricePlanEvidence(String runId, String timeframeProfile, BigDecimal triggerPrice,
                                    BigDecimal idealPrice, BigDecimal maxAcceptablePrice, Long validUntilEpoch,
                                    BigDecimal invalidationPrice, BigDecimal initialStopPrice, BigDecimal target1RPrice,
                                    BigDecimal target2RPrice, BigDecimal structureTargetPrice,
                                    BigDecimal plannedRiskUsdt, BigDecimal netRAtStructureTarget, String side,
                                    BigDecimal contractSize, long plannedContracts, BigDecimal estimatedRoundTripCostUsdt) {
        /** Compatibility constructor for plans recorded before simulated execution dimensions were introduced. */
        public PricePlanEvidence(String runId, String timeframeProfile, BigDecimal triggerPrice,
                                 BigDecimal idealPrice, BigDecimal maxAcceptablePrice, Long validUntilEpoch,
                                 BigDecimal invalidationPrice, BigDecimal initialStopPrice, BigDecimal target1RPrice,
                                 BigDecimal target2RPrice, BigDecimal structureTargetPrice,
                                 BigDecimal plannedRiskUsdt, BigDecimal netRAtStructureTarget) {
            this(runId, timeframeProfile, triggerPrice, idealPrice, maxAcceptablePrice, validUntilEpoch,
                    invalidationPrice, initialStopPrice, target1RPrice, target2RPrice, structureTargetPrice,
                    plannedRiskUsdt, netRAtStructureTarget, null, null, 0L, null);
        }
    }

    public record PaperExitPlanEvidence(String runId, String executionKey, String side, BigDecimal fillPrice,
                                        long filledContracts, BigDecimal contractSize, BigDecimal invalidationPrice,
                                        BigDecimal initialStopPrice, BigDecimal target1RPrice, BigDecimal target2RPrice,
                                        BigDecimal structureTargetPrice, BigDecimal riskDistance,
                                        BigDecimal plannedRiskUsdt, BigDecimal structureGrossPnlUsdt,
                                        BigDecimal estimatedRoundTripCostUsdt, BigDecimal netRAtStructureTarget) {
    }
}
