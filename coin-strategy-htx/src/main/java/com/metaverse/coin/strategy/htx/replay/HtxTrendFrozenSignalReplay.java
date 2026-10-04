package com.metaverse.coin.strategy.htx.replay;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.service.HtxTrendCombinationCalculator;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
        List<HtxSwapKline> candles = dataset.candles(contractCode, period);
        int index = find(candles, completedBarTime);
        if (index < 0) {
            throw new IllegalArgumentException("completed bar is not in the frozen input: " + contractCode + " " + period + " " + completedBarTime);
        }
        return evaluateCompleted(dataset.datasetKey(), contractCode, period, candles, index, properties, boxWindowBars);
    }

    /**
     * Replays every completed entry-period bar in the supplied inclusive time window.
     * Every observation uses the same trailing {@code klineSize} window that production fetches from HTX.
     * Results are streamed to the optional consumer so callers can persist or compare evidence
     * without retaining a six-month universe in memory.
     */
    public BatchSummary replay(HtxTrendFrozenDataset dataset, String period, long startBarTime, long endBarTime,
                               HtxTrendEnsembleShadowProperties properties, int boxWindowBars,
                               Consumer<Observation> observationConsumer) throws IOException {
        return replay(dataset, dataset == null ? List.of() : dataset.contracts(), period, startBarTime, endBarTime,
                properties, boxWindowBars, observationConsumer);
    }

    /**
     * Replays only the explicitly supplied contracts from the frozen universe.
     * The contract filter makes small-window acceptance runs possible before the calculator has a rolling cache.
     */
    public BatchSummary replay(HtxTrendFrozenDataset dataset, List<String> contractCodes, String period,
                               long startBarTime, long endBarTime, HtxTrendEnsembleShadowProperties properties,
                               int boxWindowBars, Consumer<Observation> observationConsumer) throws IOException {
        if (dataset == null || properties == null || startBarTime > endBarTime) {
            throw new IllegalArgumentException("dataset, properties and an ordered replay window are required");
        }
        if (contractCodes == null || contractCodes.isEmpty()) {
            throw new IllegalArgumentException("at least one frozen contract is required");
        }
        Map<String, ContractSummary> summaries = new LinkedHashMap<>();
        long evaluated = 0L, directional = 0L, combinations = 0L, externalGateUnavailable = 0L;
        for (String contractCode : contractCodes.stream().distinct().sorted().toList()) {
            if (!dataset.contracts().contains(contractCode)) {
                throw new IllegalArgumentException("contract is not declared by frozen dataset: " + contractCode);
            }
            if (!dataset.periods(contractCode).contains(period)) {
                continue;
            }
            List<HtxSwapKline> candles = dataset.candles(contractCode, period);
            long contractEvaluated = 0L, contractDirectional = 0L, contractCombinations = 0L;
            for (int index = 0; index < candles.size(); index++) {
                long barTime = candles.get(index).getId();
                if (barTime < startBarTime || barTime > endBarTime) {
                    continue;
                }
                Observation observation = evaluateCompleted(dataset.datasetKey(), contractCode, period, candles, index,
                        properties, boxWindowBars);
                contractEvaluated++;
                evaluated++;
                if (observation.ensemble().direction() != HtxTrendEnsembleSignalCalculator.Direction.FLAT) {
                    contractDirectional++;
                    directional++;
                }
                if (!observation.combinations().isEmpty()) {
                    contractCombinations += observation.combinations().size();
                    combinations += observation.combinations().size();
                    externalGateUnavailable += observation.combinations().size();
                }
                if (observationConsumer != null) {
                    observationConsumer.accept(observation);
                }
            }
            summaries.put(contractCode, new ContractSummary(contractEvaluated, contractDirectional, contractCombinations));
        }
        return new BatchSummary(dataset.datasetKey(), period, startBarTime, endBarTime, evaluated, directional,
                combinations, externalGateUnavailable, Map.copyOf(summaries));
    }

    /**
     * Evaluates a caller-supplied immutable candle window without opening a dataset file again.
     * BT-02A uses this entry point to reconstruct all contracts at one completed bar while still
     * delegating every signal and combination formula to the production calculators.
     */
    public Observation evaluateCompleted(String datasetKey, String contractCode, String period, List<HtxSwapKline> candles,
                                        int index, HtxTrendEnsembleShadowProperties properties, int boxWindowBars) {
        long completedBarTime = candles.get(index).getId();
        // Production requests klineSize bars on every evaluation. A full historical prefix would change EMA/ADX
        // values relative to production and makes the replay needlessly quadratic in the frozen window length.
        int first = Math.max(0, index + 1 - properties.getKlineSize());
        List<HtxSwapKline> completed = candles.subList(first, index + 1);
        long seconds = periodSeconds(period);
        long evaluationTime = completedBarTime + seconds + Math.max(0L, properties.getCandleCloseGraceSeconds());
        HtxTrendEnsembleSignalCalculator.TrendCandidateSignal ensemble = ensembleCalculator.evaluate(
                completed, properties, period, evaluationTime);
        List<HtxTrendCombinationCalculator.CombinationCandidate> combinations = combinationCalculator.evaluate(
                completed, ensemble, boxWindowBars);
        return new Observation(datasetKey, contractCode, period, completedBarTime, ensemble, combinations,
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

    public record ContractSummary(long evaluatedBars, long directionalBars, long combinationCount) {
    }

    public record BatchSummary(String datasetKey, String period, long startBarTime, long endBarTime,
                               long evaluatedBars, long directionalBars, long combinationCount,
                               long externalGateUnavailableCount, Map<String, ContractSummary> contracts) {
        public BatchSummary {
            contracts = Map.copyOf(contracts == null ? Map.of() : contracts);
        }
    }
}
