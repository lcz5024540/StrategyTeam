package com.metaverse.coin.strategy.htx.replay;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.service.HtxTrendCombinationCalculator;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator;

import java.io.IOException;
import java.util.List;

/**
 * Re-evaluates one completed historical bar with the production ensemble and combination calculators.
 *
 * <p>The result intentionally stops before order book, account fee, margin and exchange-position gates.
 * Those inputs are not part of the public K-line dataset and are therefore recorded as unavailable,
 * rather than replaced by current values.</p>
 */
public final class HtxTrendFrozenSignalReplay {

    public static final String EXTERNAL_GATE_UNAVAILABLE = "INSUFFICIENT_EVIDENCE: historical order-book, fee, margin and position inputs were not frozen";

    private final HtxTrendEnsembleSignalCalculator ensembleCalculator = new HtxTrendEnsembleSignalCalculator();
    private final HtxTrendCombinationCalculator combinationCalculator = new HtxTrendCombinationCalculator();

    public Observation evaluate(HtxTrendFrozenDataset dataset, String contractCode, String period, long completedBarTime,
                                HtxTrendEnsembleShadowProperties properties, int boxWindowBars) throws IOException {
        if (dataset == null || properties == null) {
            throw new IllegalArgumentException("frozen dataset and ensemble properties are required");
        }
        long seconds = periodSeconds(period);
        List<HtxSwapKline> candles = dataset.candles(contractCode, period);
        int index = find(candles, completedBarTime);
        if (index < 0) {
            throw new IllegalArgumentException("completed bar is not in the frozen input: " + contractCode + " " + period + " " + completedBarTime);
        }
        List<HtxSwapKline> completed = candles.subList(0, index + 1);
        long evaluationTime = completedBarTime + seconds + Math.max(0L, properties.getCandleCloseGraceSeconds());
        HtxTrendEnsembleSignalCalculator.TrendCandidateSignal ensemble = ensembleCalculator.evaluate(
                completed, properties, period, evaluationTime);
        List<HtxTrendCombinationCalculator.CombinationCandidate> combinations = combinationCalculator.evaluate(
                completed, ensemble, boxWindowBars);
        return new Observation(dataset.datasetKey(), contractCode, period, completedBarTime, ensemble, combinations,
                EXTERNAL_GATE_UNAVAILABLE);
    }

    private static int find(List<HtxSwapKline> candles, long barTime) {
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getId() == barTime) {
                return index;
            }
        }
        return -1;
    }

    private static long periodSeconds(String period) {
        return switch (period) {
            case "15min" -> 900L;
            case "30min" -> 1_800L;
            case "60min" -> 3_600L;
            case "4hour" -> 14_400L;
            case "1day" -> 86_400L;
            default -> throw new IllegalArgumentException("unsupported frozen replay period: " + period);
        };
    }

    public record Observation(String datasetKey, String contractCode, String period, long barTime,
                              HtxTrendEnsembleSignalCalculator.TrendCandidateSignal ensemble,
                              List<HtxTrendCombinationCalculator.CombinationCandidate> combinations,
                              String executionGateEvidence) {
        public Observation {
            combinations = List.copyOf(combinations == null ? List.of() : combinations);
        }
    }
}
