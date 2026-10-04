package com.metaverse.coin.strategy.htx.replay;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.service.HtxTrendCombinationCalculator;
import com.metaverse.coin.strategy.htx.service.HtxTrendCombinationMarketRiskGate;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * K-line-only reconstruction of the combination market-risk gate for BT-02A.
 *
 * <p>This class intentionally produces candidate and gate evidence only.  Historical public
 * K-lines cannot recreate order-book spread/depth, account fee profile, margin, active orders,
 * exchange positions, fills or slippage.  It must therefore never be used to claim a comparable
 * trade count, PnL, profit factor, drawdown, or a production-policy decision.</p>
 */
public final class HtxTrendFrozenGateAblation {

    public static final String KLINE_ONLY_EVIDENCE = "INSUFFICIENT_EVIDENCE: frozen public K-lines do not contain "
            + "historical order-book, fee, margin, position, order or fill evidence";

    private final HtxTrendFrozenSignalReplay signalReplay = new HtxTrendFrozenSignalReplay();

    public Result replay(HtxTrendFrozenDataset dataset, List<String> requestedContracts, String entryPeriod,
                         long startBarTime, long endBarTime, HtxTrendEnsembleShadowProperties properties,
                         int boxWindowBars, Configuration configuration) throws IOException {
        if (dataset == null || requestedContracts == null || requestedContracts.isEmpty() || properties == null
                || configuration == null || startBarTime > endBarTime) {
            throw new IllegalArgumentException("dataset, contracts, period, ordered window, properties and configuration are required");
        }
        List<String> contracts = requestedContracts.stream().distinct().sorted().toList();
        Map<String, List<HtxSwapKline>> entryCandles = new LinkedHashMap<>();
        Map<String, Map<String, List<HtxSwapKline>>> higherCandles = new LinkedHashMap<>();
        for (String contract : contracts) {
            if (!dataset.contracts().contains(contract) || !dataset.periods(contract).contains(entryPeriod)) {
                throw new IllegalArgumentException("frozen entry-period input unavailable: " + contract + " " + entryPeriod);
            }
            entryCandles.put(contract, dataset.candles(contract, entryPeriod));
            Map<String, List<HtxSwapKline>> byPeriod = new LinkedHashMap<>();
            for (String period : configuration.directionConfirmationPeriods()) {
                if (dataset.periods(contract).contains(period)) {
                    byPeriod.put(period, dataset.candles(contract, period));
                }
            }
            higherCandles.put(contract, Map.copyOf(byPeriod));
        }

        List<Long> barTimes = entryCandles.values().iterator().next().stream().map(HtxSwapKline::getId)
                .filter(bar -> bar >= startBarTime && bar <= endBarTime).toList();
        EnumMap<Profile, MutableSummary> summaries = new EnumMap<>(Profile.class);
        for (Profile profile : Profile.values()) {
            summaries.put(profile, new MutableSummary());
        }
        List<Decision> decisions = new ArrayList<>();
        long evaluatedBars = 0L;
        for (long barTime : barTimes) {
            Map<String, HtxTrendFrozenSignalReplay.Observation> observations = new LinkedHashMap<>();
            for (String contract : contracts) {
                List<HtxSwapKline> candles = entryCandles.get(contract);
                int index = find(candles, barTime);
                if (index >= 0) {
                    observations.put(contract, signalReplay.evaluateCompleted(dataset.datasetKey(), contract, entryPeriod,
                            candles, index, properties, boxWindowBars));
                }
            }
            if (observations.isEmpty()) {
                continue;
            }
            evaluatedBars++;
            MarketBreadth breadth = marketBreadth(observations, configuration);
            for (HtxTrendFrozenSignalReplay.Observation observation : observations.values()) {
                for (HtxTrendCombinationCalculator.CombinationCandidate candidate : observation.combinations()) {
                    DirectionConfirmation confirmation = directionConfirmation(dataset.datasetKey(), observation.contractCode(),
                            observation.barTime(), higherCandles.get(observation.contractCode()), properties,
                            boxWindowBars, candidate.direction(), configuration);
                    for (Profile profile : Profile.values()) {
                        GateOutcome outcome = gate(profile, candidate, breadth, confirmation);
                        summaries.get(profile).add(candidate.primaryModel(), outcome);
                        decisions.add(new Decision(profile, observation.contractCode(), observation.period(), observation.barTime(),
                                candidate.primaryModel(), candidate.direction().name(), candidate.score(), breadth, confirmation,
                                outcome));
                    }
                }
            }
        }
        Map<Profile, Summary> immutable = new EnumMap<>(Profile.class);
        summaries.forEach((profile, summary) -> immutable.put(profile, summary.freeze()));
        return new Result(dataset.datasetKey(), entryPeriod, startBarTime, endBarTime, evaluatedBars,
                Map.copyOf(immutable), List.copyOf(decisions), KLINE_ONLY_EVIDENCE);
    }

    private DirectionConfirmation directionConfirmation(String datasetKey, String contractCode, long entryBarTime,
                                                         Map<String, List<HtxSwapKline>> candlesByPeriod,
                                                         HtxTrendEnsembleShadowProperties properties, int boxWindowBars,
                                                         HtxTrendEnsembleSignalCalculator.Direction side,
                                                         Configuration configuration) {
        if (candlesByPeriod == null || candlesByPeriod.size() != configuration.directionConfirmationPeriods().size()) {
            return DirectionConfirmation.unavailable("one or more frozen confirmation periods are unavailable");
        }
        int aligned = 0;
        int opposite = 0;
        Map<String, String> directions = new LinkedHashMap<>();
        for (String period : configuration.directionConfirmationPeriods()) {
            List<HtxSwapKline> candles = candlesByPeriod.get(period);
            int index = completedIndex(candles, entryBarTime, period);
            if (index < 0) {
                return DirectionConfirmation.unavailable("no completed " + period + " candle at entry time");
            }
            HtxTrendFrozenSignalReplay.Observation observation = signalReplay.evaluateCompleted(datasetKey, contractCode,
                    period, candles, index, properties, boxWindowBars);
            HtxTrendEnsembleSignalCalculator.Direction direction = observation.ensemble().direction();
            directions.put(period, direction.name());
            if (direction == side) {
                aligned++;
            } else if (direction != HtxTrendEnsembleSignalCalculator.Direction.FLAT) {
                opposite++;
            }
        }
        return new DirectionConfirmation(true, aligned, opposite, Map.copyOf(directions), null);
    }

    private static GateOutcome gate(Profile profile, HtxTrendCombinationCalculator.CombinationCandidate candidate,
                                    MarketBreadth breadth, DirectionConfirmation confirmation) {
        boolean counterMarket = HtxTrendCombinationMarketRiskGate.opposesBroadMarket(candidate.direction().name(), breadth.regime());
        return switch (profile) {
            case COMBINATION_ONLY -> GateOutcome.pass("combination candidate accepted without market gate");
            case CURRENT_PRODUCTION_COMBINATION_GATE, COUNTER_MARKET_RISK_ONLY -> counterMarket
                    ? GateOutcome.reject("combination market risk: candidate opposes " + breadth.regime())
                    : GateOutcome.pass("candidate does not oppose broad market");
            case CURRENT_PLUS_MULTITIMEFRAME_DIRECTION -> {
                if (counterMarket) {
                    yield GateOutcome.reject("combination market risk: candidate opposes " + breadth.regime());
                }
                if (!confirmation.available()) {
                    yield GateOutcome.unavailable(confirmation.reason());
                }
                if (confirmation.oppositeCount() > 0 || confirmation.alignedCount() < 2) {
                    yield GateOutcome.reject("multi-timeframe direction not confirmed aligned=" + confirmation.alignedCount()
                            + " opposite=" + confirmation.oppositeCount());
                }
                yield GateOutcome.pass("candidate passes broad-market and multi-timeframe direction confirmation");
            }
        };
    }

    private static MarketBreadth marketBreadth(Map<String, HtxTrendFrozenSignalReplay.Observation> observations,
                                                Configuration configuration) {
        long total = 0L;
        long longs = 0L;
        long shorts = 0L;
        for (HtxTrendFrozenSignalReplay.Observation observation : observations.values()) {
            HtxTrendEnsembleSignalCalculator.Direction direction = observation.ensemble().direction();
            if (direction == HtxTrendEnsembleSignalCalculator.Direction.LONG) {
                total++;
                longs++;
            } else if (direction == HtxTrendEnsembleSignalCalculator.Direction.SHORT) {
                total++;
                shorts++;
            }
        }
        BigDecimal longRatio = ratio(longs, total);
        BigDecimal shortRatio = ratio(shorts, total);
        String regime = "MIXED";
        if (total >= configuration.minimumBreadthSymbols()
                && longRatio.compareTo(configuration.breadthThreshold()) >= 0
                && shortRatio.compareTo(configuration.oppositeBreadthMaximum()) <= 0) {
            regime = "BROAD_UP";
        } else if (total >= configuration.minimumBreadthSymbols()
                && shortRatio.compareTo(configuration.breadthThreshold()) >= 0
                && longRatio.compareTo(configuration.oppositeBreadthMaximum()) <= 0) {
            regime = "BROAD_DOWN";
        }
        return new MarketBreadth(regime, total, longs, shorts, longRatio, shortRatio);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return denominator <= 0L ? BigDecimal.ZERO
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 18, RoundingMode.HALF_UP);
    }

    private static int find(List<HtxSwapKline> candles, long barTime) {
        if (candles == null) {
            return -1;
        }
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getId() == barTime) {
                return index;
            }
        }
        return -1;
    }

    private static int completedIndex(List<HtxSwapKline> candles, long entryBarTime, String period) {
        if (candles == null) {
            return -1;
        }
        long seconds = periodSeconds(period);
        int resolved = -1;
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getId() + seconds <= entryBarTime) {
                resolved = index;
            } else {
                break;
            }
        }
        return resolved;
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

    public enum Profile {
        /** Model candidate only. This is a diagnostic baseline, not a live execution rule. */
        COMBINATION_ONLY,
        /** Exact current production combination-market-risk gate. */
        CURRENT_PRODUCTION_COMBINATION_GATE,
        /** Proposed experiment: current gate plus independent multi-timeframe direction confirmation. */
        CURRENT_PLUS_MULTITIMEFRAME_DIRECTION,
        /** Kept explicit for BT-02A; current production is presently equivalent to this narrow gate. */
        COUNTER_MARKET_RISK_ONLY
    }

    public record Configuration(int minimumBreadthSymbols, BigDecimal breadthThreshold,
                                BigDecimal oppositeBreadthMaximum, List<String> directionConfirmationPeriods) {
        public Configuration {
            if (minimumBreadthSymbols < 1 || breadthThreshold == null || breadthThreshold.signum() <= 0
                    || oppositeBreadthMaximum == null || oppositeBreadthMaximum.signum() < 0
                    || directionConfirmationPeriods == null || directionConfirmationPeriods.isEmpty()) {
                throw new IllegalArgumentException("complete frozen gate-ablation configuration is required");
            }
            directionConfirmationPeriods = directionConfirmationPeriods.stream().distinct().sorted().toList();
        }
    }

    public record MarketBreadth(String regime, long directionalSymbols, long longCount, long shortCount,
                                BigDecimal longRatio, BigDecimal shortRatio) {
    }

    public record DirectionConfirmation(boolean available, int alignedCount, int oppositeCount,
                                        Map<String, String> directions, String reason) {
        static DirectionConfirmation unavailable(String reason) {
            return new DirectionConfirmation(false, 0, 0, Map.of(), reason);
        }
    }

    public record GateOutcome(String state, String reason) {
        static GateOutcome pass(String reason) { return new GateOutcome("PASSED", reason); }
        static GateOutcome reject(String reason) { return new GateOutcome("REJECTED", reason); }
        static GateOutcome unavailable(String reason) { return new GateOutcome("UNAVAILABLE", reason); }
    }

    public record Decision(Profile profile, String contractCode, String period, long barTime, String primaryModel,
                           String direction, BigDecimal score, MarketBreadth marketBreadth,
                           DirectionConfirmation directionConfirmation, GateOutcome outcome) {
    }

    public record ModelSummary(long candidateCount, long passedCount, long rejectedCount, long unavailableCount) {
    }

    public record Summary(long candidateCount, long passedCount, long rejectedCount, long unavailableCount,
                          Map<String, ModelSummary> models) {
    }

    public record Result(String datasetKey, String entryPeriod, long startBarTime, long endBarTime, long evaluatedBars,
                         Map<Profile, Summary> summaries, List<Decision> decisions, String evidenceBoundary) {
    }

    private static final class MutableSummary {
        private long candidates;
        private long passed;
        private long rejected;
        private long unavailable;
        private final Map<String, MutableModelSummary> models = new LinkedHashMap<>();

        private void add(String model, GateOutcome outcome) {
            candidates++;
            MutableModelSummary summary = models.computeIfAbsent(model, ignored -> new MutableModelSummary());
            summary.candidates++;
            if ("PASSED".equals(outcome.state())) {
                passed++;
                summary.passed++;
            } else if ("REJECTED".equals(outcome.state())) {
                rejected++;
                summary.rejected++;
            } else {
                unavailable++;
                summary.unavailable++;
            }
        }

        private Summary freeze() {
            Map<String, ModelSummary> frozen = new LinkedHashMap<>();
            models.forEach((model, value) -> frozen.put(model, value.freeze()));
            return new Summary(candidates, passed, rejected, unavailable, Map.copyOf(frozen));
        }
    }

    private static final class MutableModelSummary {
        private long candidates;
        private long passed;
        private long rejected;
        private long unavailable;

        private ModelSummary freeze() {
            return new ModelSummary(candidates, passed, rejected, unavailable);
        }
    }
}
