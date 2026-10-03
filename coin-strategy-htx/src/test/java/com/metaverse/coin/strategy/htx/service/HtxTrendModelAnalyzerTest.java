package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendModelAnalyzerTest {
    private final HtxTrendModelAnalyzer analyzer = new HtxTrendModelAnalyzer();

    @Test
    void allActiveModelsShareTheSameKlineWindowWithoutAnExclusiveOwner() {
        HtxTrendModelAnalyzer.ModelAnalysis analysis = analyzer.analyze(signal(100L),
                coverage(true, true, true, true, 88L));

        assertTrue(analysis.available());
        assertEquals(List.of("DONCHIAN_BREAKOUT", "FIB_PULLBACK_CONTINUATION", "EMA_VWAP_PULLBACK",
                        "MOMENTUM_CONTINUATION", "EMA_VWAP_MOMENTUM_CONTINUATION"),
                analysis.activeModels().stream().map(HtxTrendModelAnalyzer.ModelEvidence::model).toList());
        assertTrue(analysis.contextEvidence().contains("EMA_VWAP_ALIGNED"));
        assertTrue(analysis.contextEvidence().contains("MOMENTUM_CONFIRMS"));
        assertEquals(HtxTrendModelContract.ExitAuthority.FIB_V4,
                analysis.activeModels().stream().filter(item -> "FIB_PULLBACK_CONTINUATION".equals(item.model()))
                        .findFirst().orElseThrow().contract().exitAuthority());
        assertFalse(analysis.toString().contains("primaryModel"));
    }

    @Test
    void fibonacciKeepsItsOwnStableStructureIdAlongsideOtherModels() {
        HtxTrendModelCoverageCalculator.Coverage coverage = coverage(true, true, true, true, 88L);

        HtxTrendModelAnalyzer.ModelAnalysis first = analyzer.analyze(signal(104L), coverage);
        HtxTrendModelAnalyzer.ModelAnalysis second = analyzer.analyze(signal(105L), coverage);

        assertEquals("BREAKOUT_RETEST:88", structureId(first, "FIB_PULLBACK_CONTINUATION"));
        assertEquals(structureId(first, "FIB_PULLBACK_CONTINUATION"),
                structureId(second, "FIB_PULLBACK_CONTINUATION"));
        assertEquals("DONCHIAN_BREAKOUT:104", structureId(first, "DONCHIAN_BREAKOUT"));
    }

    @Test
    void noActiveModelProducesAnEmptyAnalysisInsteadOfAnInventedRoute() {
        HtxTrendModelAnalyzer.ModelAnalysis analysis = analyzer.analyze(signal(103L),
                coverage(false, false, false, false, null));

        assertFalse(analysis.available());
        assertTrue(analysis.activeModels().isEmpty());
        assertEquals("no independent entry family is active", analysis.reason());
    }

    private static String structureId(HtxTrendModelAnalyzer.ModelAnalysis analysis, String model) {
        return analysis.activeModels().stream().filter(item -> model.equals(item.model())).findFirst()
                .orElseThrow().structureId();
    }

    private static HtxTrendModelCoverageCalculator.Coverage coverage(boolean donchian, boolean fibonacci,
                                                                       boolean emaVwap, boolean momentum,
                                                                       Long fibonacciSourceBarTime) {
        return new HtxTrendModelCoverageCalculator.Coverage(true, Direction.LONG, donchian, fibonacci,
                emaVwap, momentum, false, true,
                activeModels(donchian, fibonacci, emaVwap, momentum), fibonacciSourceBarTime, new BigDecimal("100"));
    }

    private static List<String> activeModels(boolean donchian, boolean fibonacci, boolean emaVwap, boolean momentum) {
        java.util.ArrayList<String> models = new java.util.ArrayList<>();
        if (donchian) models.add("DONCHIAN_BREAKOUT");
        if (fibonacci) models.add("FIB_PULLBACK_CONTINUATION");
        if (emaVwap) models.add("EMA_VWAP_PULLBACK");
        if (momentum) models.add("MOMENTUM_CONTINUATION");
        if (emaVwap && momentum) models.add("EMA_VWAP_MOMENTUM_CONTINUATION");
        return List.copyOf(models);
    }

    private static TrendCandidateSignal signal(long barTime) {
        return new TrendCandidateSignal("15min", barTime, Direction.LONG, Regime.TRENDING_UP,
                new BigDecimal("80"), new BigDecimal("80"), BigDecimal.ZERO, new BigDecimal("80"),
                new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("98"), new BigDecimal("97"),
                new BigDecimal("101"), new BigDecimal("95"), new BigDecimal("2"), new BigDecimal("0.02"),
                new BigDecimal("25"), new BigDecimal("0.02"), new BigDecimal("1.2"), "test", "test");
    }
}
