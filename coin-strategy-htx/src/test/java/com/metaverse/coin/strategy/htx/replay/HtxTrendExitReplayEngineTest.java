package com.metaverse.coin.strategy.htx.replay;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendExitReplayEngineTest {

    private final HtxTrendExitReplayEngine engine = new HtxTrendExitReplayEngine();

    @Test
    void doesNotCloseRunnerBeforeOneRAndMajorTimeframeOpposition() {
        HtxTrendExitReplayEngine.Outcome outcome = engine.replay(input(), List.of(
                candle(2, "101", "105", "99", "103")), Map.of(2L, 0));

        assertEquals("MARK_TO_MARK", outcome.outcomeState());
        assertEquals("COMPLETE", outcome.dataIntegrity());
        assertEquals(10L, outcome.remainingContracts());
    }

    @Test
    void appliesOneRAndTwoRPartialExitsBeforeWindowMark() {
        HtxTrendExitReplayEngine.Outcome outcome = engine.replay(input(), List.of(
                candle(2, "100", "112", "101", "111"),
                candle(3, "111", "121", "110", "120")), Map.of(2L, 0, 3L, 0));

        assertEquals("MARK_TO_MARK", outcome.outcomeState());
        assertEquals(4L, outcome.remainingContracts());
        assertTrue(outcome.netProfit().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void comparesFullExitStagedExitAndRunnerAgainstTheSamePricePath() {
        List<HtxTrendExitReplayEngine.Candle> candles = List.of(
                candle(2, "100", "112", "101", "111"),
                candle(3, "111", "121", "110", "120"),
                candle(4, "120", "130", "119", "128"));
        Map<Long, Integer> opposition = Map.of(2L, 0, 3L, 0, 4L, 0);

        HtxTrendExitReplayEngine.Outcome fullExit = engine.replay(input(), candles, opposition,
                HtxTrendExitReplayEngine.ExitProfile.FULL_EXIT_AT_FIRST_TARGET);
        HtxTrendExitReplayEngine.Outcome stagedExit = engine.replay(input(), candles, opposition,
                HtxTrendExitReplayEngine.ExitProfile.STAGED_EXIT_AT_TARGETS);
        HtxTrendExitReplayEngine.Outcome runner = engine.replay(input(), candles, opposition,
                HtxTrendExitReplayEngine.ExitProfile.STAGED_RUNNER);

        assertEquals("TARGET_FULL_EXIT", fullExit.exitReason());
        assertEquals(0L, fullExit.remainingContracts());
        assertEquals("TARGET_SECOND_STAGE_FULL_EXIT", stagedExit.exitReason());
        assertEquals(1, stagedExit.partialExitStage());
        assertEquals(0L, stagedExit.remainingContracts());
        assertEquals("MARK_TO_MARK", runner.outcomeState());
        assertEquals(4L, runner.remainingContracts());
        assertEquals(2, runner.partialExitStage());
        assertTrue(runner.netProfit().compareTo(stagedExit.netProfit()) > 0);
    }

    @Test
    void exitsRunnerOnlyWhenMajorTimeframesConfirmExhaustion() {
        HtxTrendExitReplayEngine.Outcome outcome = engine.replay(input(), List.of(
                candle(2, "100", "111", "110", "110"),
                candle(3, "110", "111", "107", "108")), Map.of(2L, 2, 3L, 2));

        assertEquals("CLOSED", outcome.outcomeState());
        assertEquals("ATR_TRAILING", outcome.exitReason());
        assertEquals(0L, outcome.remainingContracts());
    }

    @Test
    void usesConservativeStopFirstRuleForAmbiguousIntrabarTargetAndStop() {
        HtxTrendExitReplayEngine.Outcome outcome = engine.replay(input(), List.of(
                candle(2, "100", "111", "89", "100")), Map.of(2L, 2));

        assertEquals("CLOSED", outcome.outcomeState());
        assertEquals("MODEL_INVALIDATION", outcome.exitReason());
        assertEquals("INTRABAR_AMBIGUOUS: stop and target in one bar", outcome.ambiguityReason());
        assertTrue(outcome.netProfit().compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void marksRunnerOutcomeInsufficientWhenHighTimeframeEvidenceIsMissing() {
        HtxTrendExitReplayEngine.Outcome outcome = engine.replay(input(), List.of(
                candle(2, "100", "111", "110", "110")), Map.of());

        assertEquals("MARK_TO_MARK", outcome.outcomeState());
        assertEquals("INSUFFICIENT_EVIDENCE", outcome.dataIntegrity());
    }

    @Test
    void refusesToProducePnlWithoutCostEvidence() {
        HtxTrendExitReplayEngine.Input incomplete = new HtxTrendExitReplayEngine.Input("LONG", 1L,
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("115"), new BigDecimal("2"),
                BigDecimal.ONE, 10L, null, null, new BigDecimal("0.33"), new BigDecimal("0.50"));

        HtxTrendExitReplayEngine.Outcome outcome = engine.replay(incomplete, List.of(candle(2, "100", "101", "99", "100")), Map.of());

        assertEquals("UNAVAILABLE", outcome.outcomeState());
        assertEquals("INSUFFICIENT_EVIDENCE", outcome.dataIntegrity());
    }

    private static HtxTrendExitReplayEngine.Input input() {
        return new HtxTrendExitReplayEngine.Input("LONG", 1L, new BigDecimal("100"), new BigDecimal("90"),
                new BigDecimal("115"), new BigDecimal("2"), BigDecimal.ONE, 10L, new BigDecimal("0.04"),
                new BigDecimal("0.0004"), new BigDecimal("0.33"), new BigDecimal("0.50"));
    }

    private static HtxTrendExitReplayEngine.Candle candle(long barTime, String open, String high, String low, String close) {
        return new HtxTrendExitReplayEngine.Candle(barTime, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close));
    }
}
