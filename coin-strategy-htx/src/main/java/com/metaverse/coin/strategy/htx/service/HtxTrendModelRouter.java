package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;

import java.util.ArrayList;
import java.util.List;

/**
 * Assigns exactly one entry family to a stable bar.  It intentionally does not calculate a
 * synthetic total score and has no exchange dependency: routing is recorded first, then its
 * model-specific entry and exit rules can be validated before the live path is replaced.
 */
public class HtxTrendModelRouter {

    public RoutingDecision route(TrendCandidateSignal signal, HtxTrendModelCoverageCalculator.Coverage coverage) {
        if (signal == null || coverage == null || !coverage.available() || coverage.direction() == Direction.FLAT) {
            return RoutingDecision.notRoutable("coverage or directional context unavailable");
        }
        String primaryModel = primaryModel(coverage);
        if (primaryModel == null) {
            return RoutingDecision.notRoutable("no independent entry family is active");
        }
        List<String> supports = supports(primaryModel, coverage);
        List<String> conflicts = conflicts(signal, coverage);
        List<String> risks = risks(signal, coverage);
        return new RoutingDecision(true, primaryModel, coverage.direction(), structureId(primaryModel, signal, coverage),
                "INTRADAY_15M", supports, conflicts, risks, "OBSERVE_ONLY",
                "one primary model; all other evidence is non-routing context");
    }

    private static String primaryModel(HtxTrendModelCoverageCalculator.Coverage coverage) {
        // These families represent different stages of the same trend lifecycle.  A fresh breakout
        // takes precedence over a pullback; a pullback takes precedence over a shallow continuation.
        if (coverage.donchian()) return "DONCHIAN_BREAKOUT";
        if (coverage.fibonacci()) return "FIB_PULLBACK_CONTINUATION";
        if (coverage.emaVwap() && coverage.momentum()) return "EMA_VWAP_MOMENTUM_CONTINUATION";
        return null;
    }

    private static String structureId(String model, TrendCandidateSignal signal,
                                      HtxTrendModelCoverageCalculator.Coverage coverage) {
        if ("FIB_PULLBACK_CONTINUATION".equals(model) && coverage.fibonacciSourceBarTime() != null) {
            return "BREAKOUT_RETEST:" + coverage.fibonacciSourceBarTime();
        }
        return model + ":" + signal.barTime();
    }

    private static List<String> supports(String primaryModel, HtxTrendModelCoverageCalculator.Coverage coverage) {
        List<String> result = new ArrayList<>();
        if (!"DONCHIAN_BREAKOUT".equals(primaryModel) && coverage.donchian()) result.add("DONCHIAN_CONFIRMS");
        if (!"FIB_PULLBACK_CONTINUATION".equals(primaryModel) && coverage.fibonacci()) result.add("FIB_RETEST_CONFIRMS");
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

    public record RoutingDecision(boolean routable, String primaryModel, Direction direction, String structureId,
                                  String timeframeProfile, List<String> supports, List<String> conflicts,
                                  List<String> risks, String executionBoundary, String reason) {
        static RoutingDecision notRoutable(String reason) {
            return new RoutingDecision(false, "NO_ROUTE", Direction.FLAT, null, "INTRADAY_15M",
                    List.of(), List.of(), List.of(), "OBSERVE_ONLY", reason);
        }
    }
}
