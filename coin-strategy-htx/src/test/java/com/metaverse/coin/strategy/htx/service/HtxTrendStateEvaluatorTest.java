package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendStateEvaluatorTest {

    private final HtxTrendStateEvaluator evaluator = new HtxTrendStateEvaluator();

    @Test
    void currentCandidateCanQualifyWithoutAnyHistoricalPnlInput() {
        HtxTrendStateEvaluator.EntryState state = evaluator.evaluateEntry(new HtxTrendStateEvaluator.EntryInput(
                TrendSide.LONG, new BigDecimal("82"), new BigDecimal("1.60"), new BigDecimal("74"),
                new BigDecimal("101"), new BigDecimal("100"), "MIXED", new BigDecimal("75"),
                new BigDecimal("88"), new BigDecimal("1.25"), new BigDecimal("1.75"), new BigDecimal("60")));

        assertTrue(state.tradable());
        assertEquals("QUALIFIED", state.regime());
    }

    @Test
    void rejectsCandidateWhenItsOwnRewardOrMomentumIsInsufficient() {
        HtxTrendStateEvaluator.EntryState state = evaluator.evaluateEntry(new HtxTrendStateEvaluator.EntryInput(
                TrendSide.SHORT, new BigDecimal("92"), new BigDecimal("0.90"), new BigDecimal("52"),
                new BigDecimal("100"), new BigDecimal("101"), "MIXED", new BigDecimal("75"),
                new BigDecimal("88"), new BigDecimal("1.25"), new BigDecimal("1.75"), new BigDecimal("60")));

        assertFalse(state.tradable());
        assertEquals("WEAK", state.regime());
    }

    @Test
    void preservesRawStateScoreAndItsCappedExecutionScoreSeparately() {
        HtxTrendStateEvaluator.EntryState state = evaluator.evaluateEntry(new HtxTrendStateEvaluator.EntryInput(
                TrendSide.SHORT, new BigDecimal("88"), new BigDecimal("1.80"), new BigDecimal("75"),
                new BigDecimal("99"), new BigDecimal("100"), "BROAD_DOWN", new BigDecimal("75"),
                new BigDecimal("88"), new BigDecimal("1.25"), new BigDecimal("1.75"), new BigDecimal("60")));

        assertEquals(new BigDecimal("114"), state.rawScore());
        assertEquals(new BigDecimal("100"), state.score());
        assertEquals(new BigDecimal("8"), state.breakdown().expectedRAdjustment());
        assertEquals(new BigDecimal("8"), state.breakdown().breadthAdjustment());
    }

    @Test
    void holdingStateUsesLiveTrendEvidenceInsteadOfEntryTimeConstants() {
        assertEquals(HtxTrendStateEvaluator.HoldingState.TRENDING,
                evaluator.evaluateHolding(new HtxTrendStateEvaluator.HoldingInput("LONG", TrendSide.LONG,
                        new BigDecimal("101"), new BigDecimal("100"), 0, false)));
        assertEquals(HtxTrendStateEvaluator.HoldingState.WEAKENING,
                evaluator.evaluateHolding(new HtxTrendStateEvaluator.HoldingInput("LONG", TrendSide.FLAT,
                        new BigDecimal("99"), new BigDecimal("100"), 1, false)));
        assertEquals(HtxTrendStateEvaluator.HoldingState.INVALIDATED,
                evaluator.evaluateHolding(new HtxTrendStateEvaluator.HoldingInput("SHORT", TrendSide.LONG,
                        new BigDecimal("101"), new BigDecimal("100"), 0, false)));
    }
}
