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
import java.util.Comparator;
import java.util.List;

/** Resolves the one highest-ranked DB-authorized combination for a symbol and candle. */
@Service
@RequiredArgsConstructor
public class HtxTrendCombinationService {
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
        for (ResolvedCandidate item : resolved) {
            recordCandidate(contractCode, period, signal, item, snapshotId,
                    item == selected ? "PASSED" : "REJECTED", item == selected ? "PASSED" : HtxTrendCombinationGateReason.LOWER_PRIORITY_FOR_SYMBOL.code());
        }
        BigDecimal multiplier = sizingMultiplier(selected.candidate().score(), selected.candidate().marketRegime());
        BigDecimal notional = selected.policy().getBaseNotionalUsdt().multiply(multiplier)
                .min(selected.policy().getMaxNotionalUsdt());
        return Selection.selected(selected.candidate(), selected.policy(), snapshotId, multiplier, notional);
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

    /** Persist a negative decision too, so a later review can distinguish missing structure from execution rejection. */
    private Selection reject(String contractCode, String period, TrendCandidateSignal signal,
                             HtxTrendCombinationGateReason reason) {
        if (signal == null || signal.barTime() == null) {
            return Selection.rejected(reason.code());
        }
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + signal.barTime() + ":combo-v2:REJECTED");
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
        entity.setModelVersion("combo-v2:COMBINATION_GATE"); entity.setPrimaryModel("NONE");
        entity.setPolicyVersion("combo-v2"); entity.setFactorSnapshotId(snapshotId);
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
                + resolved.policy().getPolicyVersion() + ":" + resolved.policy().getPolicyCode());
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
    private static String factorSnapshotJson(TrendCandidateSignal signal, CombinationCandidate candidate) {
        HtxTrendCombinationCalculator.FibonacciEvidence fibonacci = candidate == null
                ? HtxTrendCombinationCalculator.FibonacciEvidence.notApplicable(0, 0)
                : candidate.fibonacciEvidence();
        return "{\"schema\":\"trend-factor-v2\",\"period\":" + jsonText(signal.period())
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
                + ",\"expectedR\":" + jsonNumber(candidate == null ? null : candidate.expectedRMultiple()) + "}}";
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

    private static String jsonNumber(Number value) {
        return value == null ? "null" : value.toString();
    }

    private static String jsonText(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void recordCandidate(String contractCode, String period, TrendCandidateSignal signal, ResolvedCandidate resolved,
                                 Long snapshotId, String result, String reason) {
        HtxTrendCandidateSignalEntity entity = new HtxTrendCandidateSignalEntity();
        entity.setContractCode(contractCode); entity.setPeriod(period); entity.setBarTime(signal.barTime());
        entity.setDirection(signal.direction().name()); entity.setRegime(resolved.candidate().marketRegime());
        entity.setSignalScore(resolved.candidate().score()); entity.setClosePrice(signal.closePrice());
        entity.setAdx(signal.adx()); entity.setAtr(signal.atr()); entity.setAtrPct(signal.atrPct());
        entity.setMomentumPct(signal.momentumPct()); entity.setVolumeRatio(signal.volumeRatio());
        entity.setBreakoutHigh(signal.breakoutHigh()); entity.setBreakoutLow(signal.breakoutLow());
        entity.setEmaFast(signal.emaFast()); entity.setEmaMedium(signal.emaMedium()); entity.setEmaSlow(signal.emaSlow());
        entity.setEntryReason(signal.entryReason()); entity.setInvalidationReason(signal.invalidationReason());
        entity.setShadow(false); entity.setModelVersion(resolved.policy().getPolicyVersion() + ":" + resolved.policy().getPolicyCode());
        entity.setPrimaryModel(resolved.candidate().primaryModel()); entity.setConfirmationModels(resolved.candidate().confirmationModels());
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

    private record ResolvedCandidate(CombinationCandidate candidate, HtxTrendStrategyPolicyEntity policy) { }

    public record Selection(CombinationCandidate candidate, HtxTrendStrategyPolicyEntity policy, Long factorSnapshotId,
                            BigDecimal sizingMultiplier, BigDecimal plannedNotionalUsdt, String rejection) {
        static Selection selected(CombinationCandidate candidate, HtxTrendStrategyPolicyEntity policy, Long snapshotId,
                                  BigDecimal multiplier, BigDecimal notional) {
            return new Selection(candidate, policy, snapshotId, multiplier, notional, null);
        }
        static Selection rejected(String rejection) { return new Selection(null, null, null, null, null, rejection); }
        public boolean accepted() { return candidate != null; }
    }
}
