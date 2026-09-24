package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendExitDecisionEngineTest {

    private final HtxTrendExitDecisionEngine engine = new HtxTrendExitDecisionEngine();

    @Test
    void suiLikeRunnerArmsAtMilestoneAndClosesAfterLaterProtectionCross() {
        LocalDateTime armedAt = LocalDateTime.of(2026, 9, 24, 0, 1);
        HtxTrendExitDecisionEngine.Decision armed = engine.evaluate(suiInput(new BigDecimal("0.9380"), null, armedAt));

        assertEquals(HtxTrendExitDecisionEngine.Action.ARM_TRAILING, armed.action());
        assertNotNull(armed.trailingArmedAt());
        assertTrue(armed.maxFavorableR().compareTo(BigDecimal.ONE) > 0);

        HtxTrendExitDecisionEngine.Decision exited = engine.evaluate(suiInput(new BigDecimal("0.9730"), armed.trailingArmedAt(),
                LocalDateTime.of(2026, 9, 24, 0, 15)));

        assertEquals(HtxTrendExitDecisionEngine.Action.FULL_CLOSE, exited.action());
        assertEquals("armed-protection-crossed", exited.reason());
    }

    @Test
    void dogeLikeProfitAtRiskNeedsReversalEvidenceBeforeDynamicPartialClose() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(new HtxTrendExitDecisionEngine.Input(
                HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION, "SHORT", 11L, new BigDecimal("1"),
                new BigDecimal("0.094710"), new BigDecimal("0.099219"), new BigDecimal("0.099219194"),
                new BigDecimal("0.090645824"), new BigDecimal("0.093556"), new BigDecimal("0.094300"),
                new BigDecimal("0.095225"), new BigDecimal("0.091468"), new BigDecimal("0.000552"), TrendSide.FLAT,
                new BigDecimal("0.093308"), new BigDecimal("0.093644"), 0, true, new BigDecimal("0.80"),
                new BigDecimal("0.0001"), null, null, null, LocalDateTime.of(2026, 9, 24, 12, 15),
                0, 3, null));

        assertEquals(HtxTrendExitDecisionEngine.Action.PARTIAL_CLOSE, decision.action());
        assertTrue(decision.targetRemainingContracts() > 0 && decision.targetRemainingContracts() < 11L);
        assertEquals("PROFIT_AT_RISK", decision.phase());
    }

    @Test
    void strongTrendPullbackArmsProtectionButDoesNotReducePosition() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(new HtxTrendExitDecisionEngine.Input(
                HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION, "LONG", 10L, BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("130"),
                new BigDecimal("108"), new BigDecimal("104"), new BigDecimal("112"), new BigDecimal("100"),
                new BigDecimal("2"), TrendSide.LONG, new BigDecimal("108"), new BigDecimal("104"), 0, false,
                new BigDecimal("7"), new BigDecimal("0.10"), null, null, null, LocalDateTime.now(),
                0, 3, null));

        assertEquals(HtxTrendExitDecisionEngine.Action.ARM_TRAILING, decision.action());
        assertEquals("RUNNER", decision.phase());
    }

    @Test
    void unarmedLossClosesOnlyAfterConsecutiveFlatSignalsExceedBudget() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(unarmedLossInput(3));

        assertEquals(HtxTrendExitDecisionEngine.Action.FULL_CLOSE, decision.action());
        assertEquals("THESIS_EXPIRED", decision.phase());
        assertEquals("unarmed-fib-flat-loss-budget-exceeded", decision.reason());
    }

    @Test
    void interruptedFlatSequenceDoesNotTriggerUnarmedLossFallback() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(unarmedLossInput(2));

        assertEquals(HtxTrendExitDecisionEngine.Action.HOLD, decision.action());
        assertEquals("THESIS_INTACT", decision.phase());
    }

    @Test
    void intradayProfitArmUsesCostsAndAtrInsteadOfTheWideInitialStop() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(ltcIntradayInput(
                new BigDecimal("73.60"), new BigDecimal("73.60"), TrendSide.LONG,
                new BigDecimal("73.40"), new BigDecimal("73.00"), null));

        assertEquals(HtxTrendExitDecisionEngine.Action.ARM_TRAILING, decision.action());
        assertEquals("NET_COST_AND_ATR_MILESTONE", decision.trailingArmReason());
        assertEquals(new BigDecimal("73.17979657142855"), decision.protectionPrice());
        assertTrue(decision.protectionPrice().compareTo(new BigDecimal("72.92")) > 0);
        assertTrue(decision.maxFavorableR().compareTo(new BigDecimal("0.20")) < 0,
                "the arm must not wait for the 1R target implied by a wide structural stop");
    }

    @Test
    void intradayPullbackReducesOnlyAfterAtrExcursionAndIndependentWeakeningEvidence() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(ltcIntradayInput(
                new BigDecimal("74.00"), new BigDecimal("74.55"), TrendSide.FLAT,
                new BigDecimal("73.80"), new BigDecimal("74.00"), LocalDateTime.of(2026, 9, 25, 0, 0)));

        assertEquals(HtxTrendExitDecisionEngine.Action.PARTIAL_CLOSE, decision.action());
        assertEquals("PROFIT_AT_RISK", decision.phase());
        assertEquals(9L, decision.targetRemainingContracts());
        assertEquals("profit-at-risk-with-deteriorating-continuation", decision.reason());
    }

    @Test
    void profitLockUsesActualEntryFeeInsteadOfAssumingMatchingCloseFee() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(new HtxTrendExitDecisionEngine.Input(
                HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION, "LONG", 10L, BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("110"),
                new BigDecimal("100.60"), new BigDecimal("99"), new BigDecimal("100.60"), new BigDecimal("100"),
                BigDecimal.ONE, TrendSide.LONG, new BigDecimal("101"), new BigDecimal("100"), 0, false,
                new BigDecimal("1.00"), new BigDecimal("0.10"), new BigDecimal("0.40"), null, null, null,
                LocalDateTime.of(2026, 9, 25, 0, 15), 0, 3, new BigDecimal("0.25"),
                HtxTrendExitDecisionEngine.ProfitProtectionPolicy.intradayDefaults()));

        assertEquals(HtxTrendExitDecisionEngine.Action.ARM_TRAILING, decision.action());
        assertEquals(0, decision.protectionPrice().compareTo(new BigDecimal("100.20")));
    }

    private static HtxTrendExitDecisionEngine.Input ltcIntradayInput(BigDecimal reference, BigDecimal highest,
                                                                       TrendSide signalSide, BigDecimal emaFast,
                                                                       BigDecimal emaSlow, LocalDateTime armedAt) {
        return new HtxTrendExitDecisionEngine.Input(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION,
                "LONG", 12L, new BigDecimal("0.10"), new BigDecimal("72.92"), new BigDecimal("68.98"),
                new BigDecimal("68.97766"), new BigDecimal("77.10336"), reference, new BigDecimal("71.79"),
                highest, new BigDecimal("72.92"), new BigDecimal("1.342857142857"), signalSide, emaFast, emaSlow,
                0, false, new BigDecimal("1.20"), new BigDecimal("0.0350208"), armedAt,
                armedAt == null ? null : "NET_COST_AND_ATR_MILESTONE", null,
                LocalDateTime.of(2026, 9, 25, 0, 15), 0, 3, new BigDecimal("0.25"));
    }

    private static HtxTrendExitDecisionEngine.Input unarmedLossInput(int flatStreak) {
        return new HtxTrendExitDecisionEngine.Input(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION,
                "SHORT", 10L, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("110"),
                new BigDecimal("90"), new BigDecimal("101"), new BigDecimal("105"), new BigDecimal("102"),
                new BigDecimal("99"), BigDecimal.ONE, TrendSide.FLAT, new BigDecimal("101"), new BigDecimal("100"),
                0, false, new BigDecimal("-0.50"), new BigDecimal("0.10"), null, null, BigDecimal.ZERO,
                LocalDateTime.of(2026, 9, 24, 12, 15), flatStreak, 3, new BigDecimal("0.25"));
    }

    private static HtxTrendExitDecisionEngine.Input suiInput(BigDecimal reference, LocalDateTime existingArm,
                                                              LocalDateTime evaluatedAt) {
        return new HtxTrendExitDecisionEngine.Input(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION,
                "SHORT", 91L, new BigDecimal("0.01"), new BigDecimal("0.9702"), new BigDecimal("0.9981"),
                new BigDecimal("0.998092"), new BigDecimal("0.914432"), reference, new BigDecimal("0.9482"),
                new BigDecimal("0.9861"), new BigDecimal("0.9380"), new BigDecimal("0.0065"), TrendSide.FLAT,
                new BigDecimal("0.9607"), new BigDecimal("0.9666"), 0, false, new BigDecimal("1.20"),
                new BigDecimal("0.04"), existingArm, "RISK_REWARD_MILESTONE", new BigDecimal("1.15"), evaluatedAt,
                0, 3, null);
    }
}
