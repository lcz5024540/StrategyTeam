package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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

    public Selection select(String contractCode, String period, List<HtxSwapKline> candles,
                            TrendCandidateSignal signal) {
        List<HtxTrendStrategyPolicyEntity> policies = policyService.enabledPolicies();
        if (policies.isEmpty()) {
            return reject(contractCode, period, signal, HtxTrendCombinationGateReason.NO_ENABLED_POLICY);
        }
        List<CombinationCandidate> candidates = calculator.evaluate(candles, signal,
                policies.stream().map(HtxTrendStrategyPolicyEntity::getBoxWindowBars)
                        .filter(value -> value != null && value > 1).findFirst().orElse(32));
        if (candidates.isEmpty()) {
            return reject(contractCode, period, signal, HtxTrendCombinationGateReason.classifyNoCandidate(signal));
        }
        List<ResolvedCandidate> resolved = candidates.stream()
                .map(candidate -> resolve(candidate, policies))
                .filter(item -> item != null)
                .sorted(Comparator.comparing((ResolvedCandidate item) -> item.candidate().score()).reversed())
                .toList();
        if (resolved.isEmpty()) {
            return reject(contractCode, period, signal, HtxTrendCombinationGateReason.POLICY_NOT_MATCHED);
        }
        ResolvedCandidate selected = resolved.get(0);
        Long snapshotId = recordSnapshot(contractCode, period, signal, selected);
        if (snapshotId == null) {
            return Selection.rejected("factor snapshot persistence failed");
        }
        String runId = runId(contractCode, period, signal.barTime());
        for (ResolvedCandidate item : resolved) {
            recordCandidate(contractCode, period, signal, item, snapshotId,
                    runId, item == selected ? "PASSED" : "REJECTED", item == selected ? "PASSED" : HtxTrendCombinationGateReason.LOWER_PRIORITY_FOR_SYMBOL.code());
        }
        BigDecimal multiplier = sizingMultiplier(selected.candidate().score(), selected.candidate().marketRegime());
        BigDecimal notional = selected.policy().getBaseNotionalUsdt().multiply(multiplier)
                .min(selected.policy().getMaxNotionalUsdt());
        return Selection.selected(selected.candidate(), selected.policy(), snapshotId, multiplier, notional, runId);
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
    public void recordMultiTimeframeSnapshot(String contractCode, String entryPeriod, long entryBarTime,
                                             String marketRegime, List<TrendCandidateSignal> timeframeSignals) {
        if (contractCode == null || contractCode.isBlank() || entryBarTime <= 0L) {
            return;
        }
        String period = entryPeriod == null || entryPeriod.isBlank() ? "15min" : entryPeriod;
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + entryBarTime + ":trend-factor-multi-v1");
        snapshot.setContractCode(contractCode);
        snapshot.setPeriod(period);
        snapshot.setBarTime(entryBarTime);
        snapshot.setMarketRegime(marketRegime == null ? "UNKNOWN" : marketRegime);
        snapshot.setFactorsJson(multiTimeframeSnapshotJson(timeframeSignals));
        combinationRecorder.recordSnapshot(snapshot);
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

    private ResolvedCandidate resolve(CombinationCandidate candidate, List<HtxTrendStrategyPolicyEntity> policies) {
        return policies.stream()
                .filter(policy -> matches(policy, candidate))
                .filter(policy -> candidate.score().compareTo(policy.getScoreThreshold()) >= 0)
                .map(policy -> new ResolvedCandidate(candidate, policy))
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
                + ",\"modelEvidence\":" + modelEvidenceJson(candidate, fibonacci)
                + "}";
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

    private static String multiTimeframeSnapshotJson(List<TrendCandidateSignal> signals) {
        StringBuilder json = new StringBuilder("{\"schema\":\"trend-factor-multi-v1\",\"source\":\"htx-completed-kline\",\"timeframes\":{");
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
                        .append(",\"reason\":").append(jsonText(available ? signal.entryReason() : signal.invalidationReason()))
                        .append('}');
            }
        }
        return json.append("}}").toString();
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

    private record ResolvedCandidate(CombinationCandidate candidate, HtxTrendStrategyPolicyEntity policy) { }

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
}
