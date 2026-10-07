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
    void remainsTheSingleV4EngineAfterAProfitProtectionRuleFix() {
        assertEquals("fib-exit-engine-v4", HtxTrendExitDecisionEngine.VERSION);
    }

    @Test
    void fundingCostExitRemainsInsideV4InsteadOfFallingThroughToTheLegacyBranch() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(ltcIntradayInput(
                new BigDecimal("73.20"), new BigDecimal("73.40"), TrendSide.LONG,
                new BigDecimal("73.30"), new BigDecimal("73.00"), null)
                .withFundingCostExitReason("funding cost blocked side=LONG funding=0.0020 sideCost=0.0020 max=0.0005"));

        assertEquals(HtxTrendExitDecisionEngine.Action.FULL_CLOSE, decision.action());
        assertEquals("FUNDING_COST", decision.phase());
        assertTrue(decision.reason().startsWith("funding-cost-exit:"));
        assertTrue(decision.evidenceJson().contains("\"fundingCostExit\""));
    }

    @Test
    void persistedHoldingAssessmentOverridesTransientSignalForV4DecisionEvidence() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(ltcIntradayInput(
                new BigDecimal("73.60"), new BigDecimal("73.80"), TrendSide.LONG,
                new BigDecimal("73.40"), new BigDecimal("73.00"), null)
                .withAssessmentState("WEAKENING", "TRENDING", "HOLDING:77:1790000000"));

        assertTrue(decision.evidenceJson().contains("\"assessmentState\":\"WEAKENING\""));
        assertTrue(decision.evidenceJson().contains("\"previousAssessmentState\":\"TRENDING\""));
    }

    @Test
    void protectionBreachClosesAfterConfirmationWithoutWaitingForIndependentReversalEvidence() {
        LocalDateTime armedAt = LocalDateTime.of(2026, 9, 24, 0, 1);
        HtxTrendExitDecisionEngine.Decision confirmed = engine.evaluate(new HtxTrendExitDecisionEngine.Input(
                HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION, "SHORT", 10L, BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("101"), new BigDecimal("100"), new BigDecimal("101.50"), new BigDecimal("100.50"),
                new BigDecimal("2"), TrendSide.SHORT, new BigDecimal("101"), new BigDecimal("102"),
                0, false, new BigDecimal("1"), new BigDecimal("0.10"), new BigDecimal("0.10"), armedAt,
                "NET_COST_AND_ATR_MILESTONE", new BigDecimal("0.25"), LocalDateTime.of(2026, 9, 24, 0, 30),
                0, 3, null, HtxTrendExitDecisionEngine.ProfitProtectionPolicy.intradayDefaults(), 2));
        assertEquals(HtxTrendExitDecisionEngine.Action.FULL_CLOSE, confirmed.action());
        assertEquals("confirmed-profit-protection-breach", confirmed.reason());
    }

    @Test
    void acuteAtrDrawdownAcrossArmedProtectionClosesFromReferencePriceWithoutCompletedBar() {
        LocalDateTime armedAt = LocalDateTime.of(2026, 9, 24, 0, 1);
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(suiInput(new BigDecimal("0.9730"), armedAt,
                LocalDateTime.of(2026, 9, 24, 0, 15)).withProtectionBreachBars(0));

        assertEquals(HtxTrendExitDecisionEngine.Action.FULL_CLOSE, decision.action());
        assertEquals("trend-weakened-protection-breach", decision.reason());
        assertTrue(decision.evidenceJson().contains("\"referenceProtectionBreached\":true"));
        assertTrue(decision.evidenceJson().contains("\"completedBarProtectionBreaches\":0"));
    }

    @Test
    void dogeLikeProfitAtRiskNeedsReversalEvidenceBeforeDynamicPartialClose() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(new HtxTrendExitDecisionEngine.Input(
                HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION, "SHORT", 11L, new BigDecimal("1"),
                new BigDecimal("0.094710"), new BigDecimal("0.099219"), new BigDecimal("0.099219194"),
                new BigDecimal("0.090645824"), new BigDecimal("0.093556"), new BigDecimal("0.094300"),
                new BigDecimal("0.095225"), new BigDecimal("0.091468"), new BigDecimal("0.000552"), TrendSide.FLAT,
                new BigDecimal("0.093308"), new BigDecimal("0.093644"), 0, true, new BigDecimal("0.80"),
                new BigDecimal("0.0001"), LocalDateTime.of(2026, 9, 24, 12, 0), "NET_COST_AND_ATR_MILESTONE",
                new BigDecimal("1.00"), LocalDateTime.of(2026, 9, 24, 12, 15),
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
        assertEquals("unarmed-fib-flat-loss-budget-with-independent-failure", decision.reason());
    }

    @Test
    void interruptedFlatSequenceDoesNotTriggerUnarmedLossFallback() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(unarmedLossInput(2));

        assertEquals(HtxTrendExitDecisionEngine.Action.HOLD, decision.action());
        assertEquals("THESIS_INTACT", decision.phase());
    }

    @Test
    void flatLossBudgetDoesNotCloseWithoutIndependentFailureEvidence() {
        HtxTrendExitDecisionEngine.Input input = new HtxTrendExitDecisionEngine.Input(
                HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION, "SHORT", 10L, BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("101"), new BigDecimal("105"), new BigDecimal("102"), new BigDecimal("99"),
                BigDecimal.ONE, TrendSide.FLAT, new BigDecimal("99"), new BigDecimal("100"),
                0, false, new BigDecimal("-0.50"), new BigDecimal("0.10"), null, null, BigDecimal.ZERO,
                LocalDateTime.of(2026, 9, 24, 12, 15), 3, 3, new BigDecimal("0.25"));

        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(input);

        assertEquals(HtxTrendExitDecisionEngine.Action.HOLD, decision.action());
        assertTrue(decision.evidenceJson().contains("\"flatIndependentFailureEvidence\":false"));
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
        assertTrue(decision.targetRemainingContracts() > 0L && decision.targetRemainingContracts() < 12L);
        assertTrue(decision.protectionPrice().compareTo(new BigDecimal("73.70")) > 0);
        assertTrue(decision.protectionPrice().compareTo(new BigDecimal("74.00")) < 0);
        assertEquals("profit-at-risk-with-deteriorating-continuation", decision.reason());
    }

    @Test
    void ltc20260925ProfitLockClosesWhenReferencePriceBreaksProtectionAfterAtrDrawdown() {
        // Production position 161: entry 72.92, 12 contracts at 0.10, entry fee 0.0350016.
        // The decision ledger first armed at the 74.74 MFE, then at 01:43:44 CST recorded ref=73.14.
        HtxTrendExitDecisionEngine.Decision armed = engine.evaluate(new HtxTrendExitDecisionEngine.Input(
                HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION, "LONG", 12L, new BigDecimal("0.10"),
                new BigDecimal("72.92"), new BigDecimal("68.98"), new BigDecimal("68.97766"),
                new BigDecimal("77.10336"), new BigDecimal("74.74"), new BigDecimal("72.47"),
                new BigDecimal("74.74"), new BigDecimal("72.92"), new BigDecimal("1.33179238095238"), TrendSide.FLAT,
                new BigDecimal("73.20"), new BigDecimal("73.30"), 0, false, new BigDecimal("2.1131232"),
                new BigDecimal("0.0350016"), new BigDecimal("0.0358752"), null, null, BigDecimal.ZERO,
                LocalDateTime.of(2026, 9, 25, 1, 20), 0, 3, new BigDecimal("0.262512")));

        assertEquals(HtxTrendExitDecisionEngine.Action.ARM_TRAILING, armed.action());
        assertEquals("NET_COST_AND_ATR_MILESTONE", armed.trailingArmReason());

        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(new HtxTrendExitDecisionEngine.Input(
                HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION, "LONG", 12L, new BigDecimal("0.10"),
                new BigDecimal("72.92"), new BigDecimal("68.98"), new BigDecimal("68.97766"),
                new BigDecimal("77.10336"), new BigDecimal("73.14"), armed.protectionPrice(),
                new BigDecimal("74.74"), new BigDecimal("72.92"), new BigDecimal("1.33179238095238"), TrendSide.FLAT,
                new BigDecimal("73.20"), new BigDecimal("73.30"), 0, false, new BigDecimal("0.1938912"),
                new BigDecimal("0.0350016"), new BigDecimal("0.0351072"), armed.trailingArmedAt(),
                armed.trailingArmReason(), armed.maxFavorableR(),
                LocalDateTime.of(2026, 9, 25, 1, 43, 44), 3, 3, new BigDecimal("0.262512")));

        assertEquals(HtxTrendExitDecisionEngine.Action.PARTIAL_CLOSE, decision.action());
        assertEquals("PROFIT_AT_RISK", decision.phase());
        assertEquals("trend-weakened-protection-breach-partial", decision.reason());
        assertTrue(decision.targetRemainingContracts() > 0L && decision.targetRemainingContracts() < 12L);
        assertTrue(decision.protectionPrice().compareTo(new BigDecimal("73.14")) > 0);
        assertTrue(decision.evidenceJson().contains("\"referenceProtectionBreached\":true"));
        assertTrue(decision.evidenceJson().contains("\"completedBarProtectionBreaches\":0"));
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

    @Test
    void netPositiveMfeArmsCostFloorTrailBeforeTheWiderAtrRunnerMilestone() {
        HtxTrendExitDecisionEngine.Decision armed = engine.evaluate(bnbCostFloorInput(
                new BigDecimal("765.395"), new BigDecimal("765.395"), new BigDecimal("0.1246696"),
                null, null, null));

        assertEquals(HtxTrendExitDecisionEngine.Action.ARM_TRAILING, armed.action());
        assertEquals("NET_POSITIVE_MFE_COST_FLOOR", armed.trailingArmReason());
        assertEquals(0, armed.protectionPrice().compareTo(new BigDecimal("765.6443392")));
        assertTrue(armed.maxFavorableR().compareTo(new BigDecimal("0.15")) < 0,
                "a cost-floor trail must not wait for the wide structural 1R threshold");
        assertTrue(armed.evidenceJson().contains("\"netPositiveMfeCostFloor\":true"));
    }

    @Test
    void weakeningFibTrendClosesWhenPriceReturnsThroughItsArmedCostFloor() {
        HtxTrendExitDecisionEngine.Decision armed = engine.evaluate(bnbCostFloorInput(
                new BigDecimal("765.395"), new BigDecimal("765.395"), new BigDecimal("0.1246696"),
                null, null, null));

        HtxTrendExitDecisionEngine.Decision exited = engine.evaluate(bnbCostFloorInput(
                new BigDecimal("765.700"), new BigDecimal("765.395"), new BigDecimal("-0.0278314"),
                armed.trailingArmedAt(), armed.trailingArmReason(), armed.maxFavorableR())
                .withProtectionBreachBars(1));

        assertEquals(HtxTrendExitDecisionEngine.Action.FULL_CLOSE, exited.action());
        assertEquals("REVERSAL_CONFIRMED", exited.phase());
        assertEquals("trend-weakened-protection-breach", exited.reason());
        assertTrue(exited.evidenceJson().contains("\"referenceProtectionBreached\":true"));
    }

    @Test
    void costFloorTrailDoesNotArmUntilTheBestPriceCanCoverBothTradingFees() {
        HtxTrendExitDecisionEngine.Decision decision = engine.evaluate(bnbCostFloorInput(
                new BigDecimal("765.700"), new BigDecimal("765.700"), new BigDecimal("-0.0278314"),
                null, null, null));

        assertEquals(HtxTrendExitDecisionEngine.Action.HOLD, decision.action());
        assertEquals(null, decision.trailingArmedAt());
        assertTrue(decision.evidenceJson().contains("\"netPositiveMfeCostFloor\":false"));
    }

    private static HtxTrendExitDecisionEngine.Input bnbCostFloorInput(BigDecimal reference, BigDecimal lowest,
                                                                        BigDecimal currentNetPnl, LocalDateTime armedAt,
                                                                        String armReason, BigDecimal maxFavorableR) {
        return new HtxTrendExitDecisionEngine.Input(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION,
                "SHORT", 5L, new BigDecimal("0.10"), new BigDecimal("766.257"), new BigDecimal("772.208"),
                new BigDecimal("772.208"), new BigDecimal("752.875616"), reference, new BigDecimal("771.156"),
                new BigDecimal("766.257"), lowest, new BigDecimal("2.742"), TrendSide.FLAT,
                new BigDecimal("765.80"), new BigDecimal("766.10"), 0, false, currentNetPnl,
                new BigDecimal("0.1532514"), new BigDecimal("0.1530790"), armedAt, armReason, maxFavorableR,
                LocalDateTime.of(2026, 10, 7, 11, 21), 0, 3, new BigDecimal("1.1493855"))
                .withAssessmentState("WEAKENING", "TRENDING", "HOLDING:290:1791342000");
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
        return suiInput(reference, existingArm, evaluatedAt, TrendSide.FLAT);
    }

    private static HtxTrendExitDecisionEngine.Input suiInput(BigDecimal reference, LocalDateTime existingArm,
                                                              LocalDateTime evaluatedAt, TrendSide signalSide) {
        return new HtxTrendExitDecisionEngine.Input(HtxTrendExitDecisionEngine.FIB_PULLBACK_CONTINUATION,
                "SHORT", 91L, new BigDecimal("0.01"), new BigDecimal("0.9702"), new BigDecimal("0.9981"),
                new BigDecimal("0.998092"), new BigDecimal("0.914432"), reference, new BigDecimal("0.9482"),
                new BigDecimal("0.9861"), new BigDecimal("0.9380"), new BigDecimal("0.0065"), signalSide,
                new BigDecimal("0.9607"), new BigDecimal("0.9666"), 0, false, new BigDecimal("1.20"),
                new BigDecimal("0.04"), existingArm, "RISK_REWARD_MILESTONE", new BigDecimal("1.15"), evaluatedAt,
                0, 3, null);
    }
}
