package com.metaverse.coin.strategy.htx.replay;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendAssessmentReplayAnalyzerTest {
    private final HtxTrendAssessmentReplayAnalyzer analyzer = new HtxTrendAssessmentReplayAnalyzer();

    @Test
    void partialFrozenEvidenceCannotProduceAResearchConclusion() {
        HtxTrendAssessmentReplayAnalyzer.Result result = analyzer.analyze("PARTIAL", List.of());
        assertEquals(HtxTrendAssessmentReplayAnalyzer.INSUFFICIENT, result.evidenceState());
        assertTrue(result.summaries().isEmpty());
    }

    @Test
    void missingStructuralOutcomeEvidenceCannotProduceAResearchConclusion() {
        HtxTrendAssessmentReplayAnalyzer.Result result = analyzer.analyze("COMPLETE", List.of(
                new HtxTrendAssessmentReplayAnalyzer.PositionObservation("FIB_PULLBACK_CONTINUATION", "LONG",
                        "BROAD_UP", "INTRADAY_15M", BigDecimal.ONE, new BigDecimal("-0.4"),
                        new BigDecimal("0.25"), 8, List.of(), BigDecimal.ONE, "DONE", false, null, false)));

        assertEquals(HtxTrendAssessmentReplayAnalyzer.INSUFFICIENT, result.evidenceState());
        assertTrue(result.reason().contains("required outcome evidence"));
    }

    @Test
    void aggregatesReachRatesAndTransitionOutcomesByModelDirectionRegimeAndProfile() {
        HtxTrendAssessmentReplayAnalyzer.Result result = analyzer.analyze("COMPLETE", List.of(
                new HtxTrendAssessmentReplayAnalyzer.PositionObservation("FIB_PULLBACK_CONTINUATION", "LONG",
                        "BROAD_UP", "INTRADAY_15M", new BigDecimal("1.2"), new BigDecimal("-0.4"),
                        new BigDecimal("0.25"), 8, List.of("TRENDING_TO_WEAKENING"), new BigDecimal("1.0"),
                        "DONE", false, true, false),
                new HtxTrendAssessmentReplayAnalyzer.PositionObservation("FIB_PULLBACK_CONTINUATION", "LONG",
                        "BROAD_UP", "INTRADAY_15M", new BigDecimal("0.3"), new BigDecimal("-0.8"),
                        new BigDecimal("0.80"), 4, List.of("TRENDING_TO_WEAKENING", "WEAKENING_TO_INVALIDATED"),
                        new BigDecimal("-0.5"), "INVALIDATED", false, false, true)));

        assertEquals(HtxTrendAssessmentReplayAnalyzer.COMPLETE, result.evidenceState());
        HtxTrendAssessmentReplayAnalyzer.Summary summary = result.summaries().values().iterator().next();
        assertEquals(2L, summary.sampleSize());
        assertEquals(1L, summary.reachedHalfR());
        assertEquals(1L, summary.reachedOneR());
        assertEquals(1L, summary.reachedStructureTarget());
        assertEquals(1L, summary.reachedInvalidation());
        assertEquals(new BigDecimal("0.5"), summary.maximumDrawdown());
        assertEquals(2L, result.transitionMatrix().get("TRENDING_TO_WEAKENING"));
        assertEquals(1L, result.transitionOutcomes().get("WEAKENING_TO_INVALIDATED=>INVALIDATED").sampleSize());
    }
}
