package com.metaverse.coin.strategy.htx.replay;

import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.service.HtxTrendCombinationCalculator;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator;
import com.metaverse.coin.strategy.htx.service.HtxTrendExitDecisionEngine;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Same-source BT-01 replay: production candidate and v4 exit code plus frozen execution facts. */
public final class HtxTrendFrozenExecutionReplay {
    public static final String COMPLETE = "COMPLETE";
    public static final String INSUFFICIENT = "INSUFFICIENT_EVIDENCE";

    private final HtxTrendFrozenSignalReplay signalReplay = new HtxTrendFrozenSignalReplay();
    private final HtxTrendExitDecisionEngine exitEngine = new HtxTrendExitDecisionEngine();

    public Result replay(HtxTrendFrozenDataset dataset, HtxTrendFrozenExecutionEvidence.Document evidence,
                         HtxTrendEnsembleShadowProperties properties, int boxWindowBars) throws IOException {
        if (dataset == null || evidence == null || properties == null) throw new IllegalArgumentException("dataset, evidence and configuration are required");
        if (!evidence.complete()) return Result.insufficient(dataset.datasetKey(), evidence.evidenceKey(), evidence.incompleteReason());
        List<TradeOutcome> outcomes = new ArrayList<>();
        for (HtxTrendFrozenExecutionEvidence.Trade trade : evidence.trades()) {
            outcomes.add(replayTrade(dataset, trade, properties, boxWindowBars));
        }
        if (outcomes.stream().anyMatch(value -> !COMPLETE.equals(value.evidenceState()))) {
            return new Result(dataset.datasetKey(), evidence.evidenceKey(), INSUFFICIENT, outcomes,
                    Map.of(), Map.of(), "one or more execution facts do not reproduce a production candidate or v4 decision");
        }
        return new Result(dataset.datasetKey(), evidence.evidenceKey(), COMPLETE, outcomes, summaries(outcomes),
                ablations(evidence.trades(), outcomes), null);
    }

    private TradeOutcome replayTrade(HtxTrendFrozenDataset dataset, HtxTrendFrozenExecutionEvidence.Trade trade,
                                     HtxTrendEnsembleShadowProperties properties, int boxWindowBars) throws IOException {
        if (!"PASSED".equals(trade.finalGate())) return TradeOutcome.insufficient(trade, "final gate did not pass");
        List<HtxSwapKline> candles = dataset.candles(trade.contractCode(), trade.period());
        int index = index(candles, trade.entryBarTime());
        if (index < 0) return TradeOutcome.insufficient(trade, "entry bar is absent from frozen K-lines");
        HtxTrendFrozenSignalReplay.Observation observation = signalReplay.evaluateCompleted(dataset.datasetKey(),
                trade.contractCode(), trade.period(), candles, index, properties, boxWindowBars);
        boolean matched = observation.combinations().stream().anyMatch(candidate -> matches(candidate, trade));
        if (!matched) return TradeOutcome.insufficient(trade, "production combination was not reproduced at the frozen entry bar");
        List<HtxTrendExitDecisionEngine.Decision> decisions = new ArrayList<>();
        for (JSONObject input : trade.exitInputs()) decisions.add(exitEngine.evaluate(exitInput(input)));
        if (decisions.isEmpty()) return TradeOutcome.insufficient(trade, "v4 exit inputs are absent");
        HtxTrendExitDecisionEngine.Decision finalDecision = decisions.get(decisions.size() - 1);
        BigDecimal net = netPnl(trade);
        return new TradeOutcome(trade.runId(), trade.contractCode(), trade.primaryModel(), trade.direction(), COMPLETE,
                net, totalFees(trade), maxFavorableR(decisions), finalDecision.action().name(), finalDecision.reason(), null);
    }

    private static boolean matches(HtxTrendCombinationCalculator.CombinationCandidate candidate,
                                   HtxTrendFrozenExecutionEvidence.Trade trade) {
        return candidate.primaryModel().equals(trade.primaryModel()) && candidate.direction().name().equals(trade.direction());
    }

    private static int index(List<HtxSwapKline> candles, long barTime) {
        for (int value = 0; value < candles.size(); value++) if (candles.get(value).getId() == barTime) return value;
        return -1;
    }

    private static BigDecimal netPnl(HtxTrendFrozenExecutionEvidence.Trade trade) {
        long entered = trade.entryFill().contracts();
        long exited = trade.exitFills().stream().mapToLong(HtxTrendFrozenExecutionEvidence.Fill::contracts).sum();
        if (entered != exited) throw new IllegalArgumentException("BT-01 requires every replayed trade to have complete exit fills");
        BigDecimal contractSize = positiveDecimal(trade.exitInputs().get(0), "contractSize");
        BigDecimal gross = BigDecimal.ZERO;
        for (HtxTrendFrozenExecutionEvidence.Fill exit : trade.exitFills()) {
            BigDecimal priceDelta = "LONG".equals(trade.direction()) ? exit.price().subtract(trade.entryFill().price())
                    : trade.entryFill().price().subtract(exit.price());
            gross = gross.add(priceDelta.multiply(contractSize).multiply(BigDecimal.valueOf(exit.contracts())));
        }
        return gross.subtract(totalFees(trade));
    }

    private static BigDecimal totalFees(HtxTrendFrozenExecutionEvidence.Trade trade) {
        BigDecimal total = trade.entryFill().fee();
        for (HtxTrendFrozenExecutionEvidence.Fill fill : trade.exitFills()) total = total.add(fill.fee());
        return total;
    }

    private static BigDecimal maxFavorableR(List<HtxTrendExitDecisionEngine.Decision> decisions) {
        return decisions.stream().map(HtxTrendExitDecisionEngine.Decision::maxFavorableR).filter(value -> value != null)
                .max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
    }

    private static HtxTrendExitDecisionEngine.Input exitInput(JSONObject input) {
        JSONObject policy = input.getJSONObject("profitProtectionPolicy");
        if (policy == null) throw new IllegalArgumentException("v4 exit input requires profitProtectionPolicy");
        return new HtxTrendExitDecisionEngine.Input(required(input, "primaryModel"), required(input, "side"),
                positiveLong(input, "remainingContracts"), positiveDecimal(input, "contractSize"), positiveDecimal(input, "entryPrice"),
                positiveDecimal(input, "initialStopPrice"), positiveDecimal(input, "invalidationPrice"),
                positiveDecimal(input, "structureTargetPrice"), positiveDecimal(input, "referencePrice"), input.getBigDecimal("protectionPrice"),
                positiveDecimal(input, "highestPrice"), positiveDecimal(input, "lowestPrice"), positiveDecimal(input, "atr"),
                TrendSide.valueOf(required(input, "signalSide")), input.getBigDecimal("emaFast"), input.getBigDecimal("emaSlow"),
                nonNegativeInt(input, "majorTimeframeOpposition"), input.getBooleanValue("marketContextOpposes"),
                decimal(input, "currentNetPnl"), input.getBigDecimal("entryFee"), input.getBigDecimal("estimatedCloseFee"),
                date(input.getString("trailingArmedAt")), input.getString("trailingArmReason"), input.getBigDecimal("maxFavorableR"),
                date(required(input, "evaluatedAt")), nonNegativeInt(input, "flatStreak"), positiveInt(input, "requiredFlatStreak"),
                input.getBigDecimal("flatLossBudget"), new HtxTrendExitDecisionEngine.ProfitProtectionPolicy(
                nonNegativeDecimal(policy, "armAtrMultiplier"), nonNegativeDecimal(policy, "lockAtrMultiplier"),
                nonNegativeDecimal(policy, "partialAtrMultiplier"), nonNegativeDecimal(policy, "pullbackAtrMultiplier"),
                nonNegativeDecimal(policy, "minNetFeeMultiple"), positiveInt(policy, "protectionBreachConfirmationBars")),
                nonNegativeInt(input, "protectionBreachBars"), input.getString("fundingCostExitReason"));
    }

    private static String required(JSONObject input, String name) { String value = input.getString(name); if (value == null || value.isBlank()) throw new IllegalArgumentException("v4 exit input requires " + name); return value; }
    private static BigDecimal decimal(JSONObject input, String name) { BigDecimal value = input.getBigDecimal(name); if (value == null) throw new IllegalArgumentException("v4 exit input requires " + name); return value; }
    private static BigDecimal positiveDecimal(JSONObject input, String name) { BigDecimal value = input.getBigDecimal(name); if (value == null || value.signum() <= 0) throw new IllegalArgumentException("v4 exit input requires positive " + name); return value; }
    private static BigDecimal nonNegativeDecimal(JSONObject input, String name) { BigDecimal value = input.getBigDecimal(name); if (value == null || value.signum() < 0) throw new IllegalArgumentException("v4 exit input requires non-negative " + name); return value; }
    private static long positiveLong(JSONObject input, String name) { Long value = input.getLong(name); if (value == null || value <= 0) throw new IllegalArgumentException("v4 exit input requires positive " + name); return value; }
    private static int positiveInt(JSONObject input, String name) { Integer value = input.getInteger(name); if (value == null || value <= 0) throw new IllegalArgumentException("v4 exit input requires positive " + name); return value; }
    private static int nonNegativeInt(JSONObject input, String name) { Integer value = input.getInteger(name); if (value == null || value < 0) throw new IllegalArgumentException("v4 exit input requires non-negative " + name); return value; }
    private static LocalDateTime date(String value) { return value == null || value.isBlank() ? null : LocalDateTime.parse(value); }

    private static Map<String, Summary> summaries(List<TradeOutcome> outcomes) {
        Map<String, List<TradeOutcome>> grouped = new LinkedHashMap<>();
        for (TradeOutcome value : outcomes) grouped.computeIfAbsent(value.primaryModel(), ignored -> new ArrayList<>()).add(value);
        Map<String, Summary> result = new LinkedHashMap<>();
        grouped.forEach((model, values) -> result.put(model, Summary.of(values)));
        return Map.copyOf(result);
    }

    /** BT-02A outcome layer. Every profile consumes the same frozen candidate universe, fills and v4 exit inputs. */
    private static Map<String, AblationSummary> ablations(List<HtxTrendFrozenExecutionEvidence.Trade> trades,
                                                           List<TradeOutcome> outcomes) {
        Map<String, TradeOutcome> byRun = new LinkedHashMap<>();
        for (TradeOutcome outcome : outcomes) byRun.put(outcome.runId(), outcome);
        Map<String, AblationSummary> result = new LinkedHashMap<>();
        for (String profile : List.of("COMBINATION_ONLY", "CURRENT_PRODUCTION_COMBINATION_GATE",
                "CURRENT_PLUS_MULTITIMEFRAME_DIRECTION", "COUNTER_MARKET_RISK_ONLY")) {
            List<TradeOutcome> accepted = new ArrayList<>(); long rejected = 0L;
            for (HtxTrendFrozenExecutionEvidence.Trade trade : trades) {
                if ("PASSED".equals(trade.gateOutcomes().get(profile))) accepted.add(byRun.get(trade.runId()));
                else rejected++;
            }
            Summary metrics = Summary.of(accepted);
            result.put(profile, new AblationSummary(trades.size(), accepted.size(), rejected, metrics));
        }
        return Map.copyOf(result);
    }

    public record TradeOutcome(String runId, String contractCode, String primaryModel, String direction, String evidenceState,
                               BigDecimal netPnl, BigDecimal fees, BigDecimal maxFavorableR, String finalExitAction,
                               String finalExitReason, String reason) {
        static TradeOutcome insufficient(HtxTrendFrozenExecutionEvidence.Trade trade, String reason) {
            return new TradeOutcome(trade.runId(), trade.contractCode(), trade.primaryModel(), trade.direction(), INSUFFICIENT,
                    null, null, null, null, null, reason);
        }
    }

    public record Summary(long tradeCount, long winCount, long lossCount, BigDecimal netPnl, BigDecimal profitFactor,
                          BigDecimal expectancy, BigDecimal maxDrawdown, BigDecimal feeTotal) {
        static Summary of(List<TradeOutcome> outcomes) {
            BigDecimal equity = BigDecimal.ZERO, peak = BigDecimal.ZERO, drawdown = BigDecimal.ZERO, profit = BigDecimal.ZERO,
                    loss = BigDecimal.ZERO, fees = BigDecimal.ZERO;
            long wins = 0, losses = 0;
            for (TradeOutcome outcome : outcomes) {
                BigDecimal pnl = outcome.netPnl(); equity = equity.add(pnl); peak = peak.max(equity); drawdown = drawdown.min(equity.subtract(peak));
                fees = fees.add(outcome.fees());
                if (pnl.signum() > 0) { wins++; profit = profit.add(pnl); } else if (pnl.signum() < 0) { losses++; loss = loss.add(pnl.negate()); }
            }
            BigDecimal net = equity;
            return new Summary(outcomes.size(), wins, losses, net, loss.signum() == 0 ? (profit.signum() == 0 ? BigDecimal.ZERO : new BigDecimal("999"))
                    : profit.divide(loss, 12, RoundingMode.HALF_UP), outcomes.isEmpty() ? BigDecimal.ZERO
                    : net.divide(BigDecimal.valueOf(outcomes.size()), 12, RoundingMode.HALF_UP), drawdown, fees);
        }
    }

    public record AblationSummary(long candidateCount, long acceptedCount, long rejectedCount, Summary metrics) {
    }

    public record Result(String datasetKey, String evidenceKey, String evidenceState, List<TradeOutcome> outcomes,
                         Map<String, Summary> summaries, Map<String, AblationSummary> gateAblations, String reason) {
        static Result insufficient(String datasetKey, String evidenceKey, String reason) {
            return new Result(datasetKey, evidenceKey, INSUFFICIENT, List.of(), Map.of(), Map.of(), reason);
        }
    }
}
