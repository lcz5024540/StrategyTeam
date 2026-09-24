package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates every independently active entry family against the same stable K-line window.  It
 * deliberately assigns no model ownership to a bar: Donchian, Fibonacci and EMA/VWAP momentum
 * may all be valid observations of different properties of the same price structure.
 */
public class HtxTrendModelAnalyzer {

    public ModelAnalysis analyze(TrendCandidateSignal signal, HtxTrendModelCoverageCalculator.Coverage coverage) {
        if (signal == null || coverage == null || !coverage.available() || coverage.direction() == Direction.FLAT) {
            return ModelAnalysis.unavailable("coverage or directional context unavailable");
        }
        List<ModelEvidence> activeModels = activeModels(signal, coverage);
        List<String> conflicts = conflicts(signal, coverage);
        List<String> risks = risks(signal, coverage);
        return new ModelAnalysis(!activeModels.isEmpty(), coverage.direction(), "INTRADAY_15M", activeModels,
                contextEvidence(coverage), conflicts, risks, "OBSERVE_ONLY",
                activeModels.isEmpty() ? "no independent entry family is active"
                        : "all active models evaluated on the same K-line window");
    }

    private static List<ModelEvidence> activeModels(TrendCandidateSignal signal,
                                                     HtxTrendModelCoverageCalculator.Coverage coverage) {
        List<ModelEvidence> models = new ArrayList<>();
        for (String model : coverage.activeModels()) {
            models.add(new ModelEvidence(model, structureId(model, signal, coverage)));
        }
        return List.copyOf(models);
    }

    private static String structureId(String model, TrendCandidateSignal signal,
                                      HtxTrendModelCoverageCalculator.Coverage coverage) {
        if ("FIB_PULLBACK_CONTINUATION".equals(model) && coverage.fibonacciSourceBarTime() != null) {
            return "BREAKOUT_RETEST:" + coverage.fibonacciSourceBarTime();
        }
        return model + ":" + signal.barTime();
    }

    private static List<String> contextEvidence(HtxTrendModelCoverageCalculator.Coverage coverage) {
        List<String> result = new ArrayList<>();
        if (coverage.emaVwap()) result.add("EMA_VWAP_ALIGNED");
        if (coverage.momentum()) result.add("MOMENTUM_CONFIRMS");
        if (coverage.structure()) result.add("PRICE_STRUCTURE_ALIGNED");
        return List.copyOf(result);
    }

    private static List<String> conflicts(TrendCandidateSignal signal,
                                          HtxTrendModelCoverageCalculator.Coverage coverage) {
        List<String> result = new ArrayList<>();
        if (!regimeAligned(signal.regime(), coverage.direction())) result.add("MARKET_REGIME_NOT_ALIGNED");
        return List.copyOf(result);
    }

    private static boolean regimeAligned(Regime regime, Direction direction) {
        return (direction == Direction.LONG && regime == Regime.TRENDING_UP)
                || (direction == Direction.SHORT && regime == Regime.TRENDING_DOWN);
    }

    private static List<String> risks(TrendCandidateSignal signal,
                                      HtxTrendModelCoverageCalculator.Coverage coverage) {
        List<String> result = new ArrayList<>();
        if (coverage.squeeze()) result.add("VOLATILITY_SQUEEZE");
        if (!coverage.structure()) result.add("STRUCTURE_NOT_CONFIRMED");
        if (signal.adx() == null || signal.adx().compareTo(new java.math.BigDecimal("20")) < 0) result.add("ADX_BELOW_20");
        if (signal.atr() == null || signal.atr().signum() <= 0) result.add("ATR_UNAVAILABLE");
        return List.copyOf(result);
    }

    public record ModelEvidence(String model, String structureId) {
    }

    public record ModelAnalysis(boolean available, Direction direction, String timeframeProfile,
                                List<ModelEvidence> activeModels, List<String> contextEvidence,
                                List<String> conflicts, List<String> risks, String executionBoundary,
                                String reason) {
        static ModelAnalysis unavailable(String reason) {
            return new ModelAnalysis(false, Direction.FLAT, "INTRADAY_15M", List.of(), List.of(), List.of(),
                    List.of(), "OBSERVE_ONLY", reason);
        }
    }
}
