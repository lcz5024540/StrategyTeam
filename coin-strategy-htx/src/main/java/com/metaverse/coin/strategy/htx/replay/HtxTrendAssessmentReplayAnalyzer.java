package com.metaverse.coin.strategy.htx.replay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates only immutable, frozen assessment projections.  It is deliberately pure: incomplete
 * evidence is reported as insufficient instead of becoming an input to a live policy change.
 */
public final class HtxTrendAssessmentReplayAnalyzer {
    public static final String COMPLETE = "COMPLETE";
    public static final String INSUFFICIENT = "INSUFFICIENT_EVIDENCE";

    public Result analyze(String frozenDataIntegrity, List<PositionObservation> observations) {
        if (!COMPLETE.equals(frozenDataIntegrity)) {
            return Result.insufficient("frozen data integrity=" + frozenDataIntegrity);
        }
        if (observations == null || observations.isEmpty()) {
            return Result.insufficient("no frozen position observations");
        }
        Map<Bucket, List<PositionObservation>> grouped = new LinkedHashMap<>();
        Map<String, Long> transitions = new LinkedHashMap<>();
        Map<String, OutcomeSummary> transitionOutcomes = new LinkedHashMap<>();
        for (PositionObservation value : observations) {
            if (value == null || value.incomplete() || value.structureTargetReached() == null
                    || value.invalidationReached() == null) {
                return Result.insufficient("one or more observations are PARTIAL or lack required outcome evidence");
            }
            Bucket bucket = new Bucket(value.primaryModel(), value.direction(), value.marketRegime(), value.timeframeProfile());
            grouped.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(value);
            for (String transition : value.transitions()) {
                transitions.merge(transition, 1L, Long::sum);
                String key = transition + "=>" + value.terminalState();
                transitionOutcomes.merge(key, OutcomeSummary.from(value.netPnl()), OutcomeSummary::add);
            }
        }
        Map<Bucket, Summary> summaries = new LinkedHashMap<>();
        grouped.forEach((key, values) -> summaries.put(key, Summary.from(values)));
        return new Result(COMPLETE, Map.copyOf(summaries), Map.copyOf(transitions), Map.copyOf(transitionOutcomes), null);
    }

    public record PositionObservation(String primaryModel, String direction, String marketRegime, String timeframeProfile,
                                      BigDecimal maxFavorableR, BigDecimal mae, BigDecimal givebackRate, long holdingBars,
                                      List<String> transitions, BigDecimal netPnl, String terminalState, boolean incomplete,
                                      Boolean structureTargetReached, Boolean invalidationReached) {
        public PositionObservation {
            if (transitions == null) transitions = List.of();
        }
    }

    public record Bucket(String primaryModel, String direction, String marketRegime, String timeframeProfile) { }

    public record Summary(long sampleSize, long reachedHalfR, long reachedOneR, long reachedStructureTarget,
                          long reachedInvalidation, BigDecimal averageMfeR,
                          BigDecimal averageMae, BigDecimal averageGivebackRate, BigDecimal averageHoldingBars,
                          BigDecimal netPnl, BigDecimal profitFactor, BigDecimal averageWinLossRatio,
                          BigDecimal maximumDrawdown) {
        static Summary from(List<PositionObservation> values) {
            BigDecimal mfe = BigDecimal.ZERO, mae = BigDecimal.ZERO, giveback = BigDecimal.ZERO, bars = BigDecimal.ZERO;
            BigDecimal net = BigDecimal.ZERO, gains = BigDecimal.ZERO, losses = BigDecimal.ZERO;
            long half = 0L, one = 0L, structureTarget = 0L, invalidation = 0L, wins = 0L, lossCount = 0L;
            for (PositionObservation value : values) {
                BigDecimal maxR = zero(value.maxFavorableR());
                if (maxR.compareTo(new BigDecimal("0.5")) >= 0) half++;
                if (maxR.compareTo(BigDecimal.ONE) >= 0) one++;
                if (Boolean.TRUE.equals(value.structureTargetReached())) structureTarget++;
                if (Boolean.TRUE.equals(value.invalidationReached())) invalidation++;
                mfe = mfe.add(maxR); mae = mae.add(zero(value.mae())); giveback = giveback.add(zero(value.givebackRate()));
                bars = bars.add(BigDecimal.valueOf(Math.max(0L, value.holdingBars())));
                BigDecimal pnl = zero(value.netPnl()); net = net.add(pnl);
                if (pnl.signum() > 0) { gains = gains.add(pnl); wins++; }
                if (pnl.signum() < 0) { losses = losses.add(pnl.negate()); lossCount++; }
            }
            BigDecimal count = BigDecimal.valueOf(values.size());
            BigDecimal profitFactor = losses.signum() == 0 ? (gains.signum() == 0 ? BigDecimal.ZERO : new BigDecimal("999"))
                    : gains.divide(losses, 12, RoundingMode.HALF_UP);
            BigDecimal avgWinLoss = wins == 0 || lossCount == 0 ? BigDecimal.ZERO
                    : gains.divide(BigDecimal.valueOf(wins), 12, RoundingMode.HALF_UP)
                    .divide(losses.divide(BigDecimal.valueOf(lossCount), 12, RoundingMode.HALF_UP), 12, RoundingMode.HALF_UP);
            return new Summary(values.size(), half, one, structureTarget, invalidation,
                    mfe.divide(count, 12, RoundingMode.HALF_UP),
                    mae.divide(count, 12, RoundingMode.HALF_UP), giveback.divide(count, 12, RoundingMode.HALF_UP),
                    bars.divide(count, 12, RoundingMode.HALF_UP), net, profitFactor, avgWinLoss, maximumDrawdown(values));
        }

        /** Frozen projections must order positions by their terminal time before analysis. */
        private static BigDecimal maximumDrawdown(List<PositionObservation> values) {
            BigDecimal equity = BigDecimal.ZERO;
            BigDecimal peak = BigDecimal.ZERO;
            BigDecimal drawdown = BigDecimal.ZERO;
            for (PositionObservation value : values) {
                equity = equity.add(zero(value.netPnl()));
                peak = peak.max(equity);
                drawdown = drawdown.max(peak.subtract(equity));
            }
            return drawdown;
        }
    }

    public record OutcomeSummary(long sampleSize, BigDecimal netPnl) {
        static OutcomeSummary from(BigDecimal netPnl) { return new OutcomeSummary(1L, zero(netPnl)); }
        OutcomeSummary add(OutcomeSummary other) { return new OutcomeSummary(sampleSize + other.sampleSize, netPnl.add(other.netPnl)); }
    }

    public record Result(String evidenceState, Map<Bucket, Summary> summaries, Map<String, Long> transitionMatrix,
                         Map<String, OutcomeSummary> transitionOutcomes, String reason) {
        static Result insufficient(String reason) { return new Result(INSUFFICIENT, Map.of(), Map.of(), Map.of(), reason); }
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
