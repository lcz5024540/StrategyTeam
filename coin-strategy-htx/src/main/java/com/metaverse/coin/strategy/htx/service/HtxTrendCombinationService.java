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
import java.time.LocalDateTime;
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
            return reject(contractCode, period, signal, "no enabled combination policy in database");
        }
        List<CombinationCandidate> candidates = calculator.evaluate(candles, signal,
                policies.stream().map(HtxTrendStrategyPolicyEntity::getBoxWindowBars)
                        .filter(value -> value != null && value > 1).findFirst().orElse(32));
        if (candidates.isEmpty()) {
            return reject(contractCode, period, signal, "no valid combination candidate");
        }
        List<ResolvedCandidate> resolved = candidates.stream()
                .map(candidate -> resolve(candidate, policies))
                .filter(item -> item != null)
                .sorted(Comparator.comparing((ResolvedCandidate item) -> item.candidate().score()).reversed())
                .toList();
        if (resolved.isEmpty()) {
            return reject(contractCode, period, signal, "candidate rejected by enabled combination policies");
        }
        ResolvedCandidate selected = resolved.get(0);
        Long snapshotId = recordSnapshot(contractCode, period, signal, selected);
        if (snapshotId == null) {
            return Selection.rejected("factor snapshot persistence failed");
        }
        for (ResolvedCandidate item : resolved) {
            recordCandidate(contractCode, period, signal, item, snapshotId,
                    item == selected ? "PASSED" : "REJECTED", item == selected ? "highest score for symbol" : "lower score");
        }
        BigDecimal multiplier = sizingMultiplier(selected.candidate().score(), selected.candidate().marketRegime());
        BigDecimal notional = selected.policy().getBaseNotionalUsdt().multiply(multiplier)
                .min(selected.policy().getMaxNotionalUsdt());
        return Selection.selected(selected.candidate(), selected.policy(), snapshotId, multiplier, notional);
    }

    /** Persist a negative decision too, so a later review can distinguish missing structure from execution rejection. */
    private Selection reject(String contractCode, String period, TrendCandidateSignal signal, String reason) {
        if (signal == null || signal.barTime() == null) {
            return Selection.rejected(reason);
        }
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setSnapshotKey(contractCode + ":" + period + ":" + signal.barTime() + ":combo-v2:REJECTED");
        snapshot.setContractCode(contractCode); snapshot.setPeriod(period); snapshot.setBarTime(signal.barTime());
        snapshot.setMarketRegime(signal.regime().name());
        snapshot.setFactorsJson("{\"adx\":\"" + signal.adx() + "\",\"atr\":\"" + signal.atr()
                + "\",\"atrPct\":\"" + signal.atrPct() + "\",\"volumeRatio\":\"" + signal.volumeRatio() + "\"}");
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
        entity.setGateResult("REJECTED"); entity.setGateReason(reason);
        candidateRecorder.record(entity);
        return Selection.rejected(reason);
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
        snapshot.setFactorsJson("{\"boxHigh\":\"" + resolved.candidate().boxHigh() + "\",\"boxLow\":\""
                + resolved.candidate().boxLow() + "\",\"swingHigh\":\"" + resolved.candidate().swingHigh()
                + "\",\"swingLow\":\"" + resolved.candidate().swingLow() + "\",\"fibLower\":\""
                + resolved.candidate().fibonacciLower() + "\",\"fibUpper\":\"" + resolved.candidate().fibonacciUpper()
                + "\",\"targetZoneLow\":\"" + resolved.candidate().targetZoneLow()
                + "\",\"targetZoneHigh\":\"" + resolved.candidate().targetZoneHigh()
                + "\",\"boxMeasuredMove\":\"" + resolved.candidate().boxMeasuredMoveTarget()
                + "\",\"fib1272\":\"" + resolved.candidate().fibExtension1272()
                + "\",\"fib1618\":\"" + resolved.candidate().fibExtension1618()
                + "\",\"expectedR\":\"" + resolved.candidate().expectedRMultiple()
                + "\",\"momentumQuality\":\"" + resolved.candidate().momentumQualityScore()
                + "\",\"adx\":\"" + signal.adx() + "\",\"atr\":\"" + signal.atr()
                + "\",\"volumeRatio\":\"" + signal.volumeRatio() + "\"}");
        return combinationRecorder.recordSnapshot(snapshot);
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
