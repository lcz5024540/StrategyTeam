package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.T0;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.btcBinance;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.liquid;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.snapshot;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.testRules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotPerpGatesTest {
    private final SpotPerpGates gates = new SpotPerpGates(testRules());

    @Test
    void admitsAttractivePositiveFundingOnDeepBook() {
        assertTrue(gates.openBlockReason(liquid(btcBinance(), "0.0003", T0), T0).isEmpty());
    }

    @Test
    void rejectsNonPositiveFunding() {
        assertEquals("FUNDING_NON_POSITIVE",
                gates.openBlockReason(liquid(btcBinance(), "0", T0), T0).orElseThrow());
        assertEquals("FUNDING_NON_POSITIVE",
                gates.openBlockReason(liquid(btcBinance(), "-0.0001", T0), T0).orElseThrow());
    }

    @Test
    void rejectsFundingBelowOpenThreshold() {
        assertEquals("FUNDING_BELOW_OPEN_THRESHOLD",
                gates.openBlockReason(liquid(btcBinance(), "0.00005", T0), T0).orElseThrow());
    }

    @Test
    void rejectsExpectedNetYieldTooLowAgainstRoundTripFees() {
        SpotPerpRules defaults = testRules();
        SpotPerpGates strict = new SpotPerpGates(new SpotPerpRules(
                defaults.dryRun(), defaults.autoOpenEnabled(), defaults.notionalUsdt(),
                defaults.leverage(), defaults.openThreshold(), defaults.closeThreshold(),
                defaults.minBasisRate(), defaults.maxBasisRate(), defaults.maxBasisDeteriorationRate(),
                new BigDecimal("0.0010"), defaults.minFundingPeriods(), defaults.settlementProtection(),
                defaults.minHolding(), defaults.maxHolding(), defaults.maxQuoteAge(),
                defaults.minBookNotional(), defaults.coverageRatio(), defaults.maxVwapImpact(),
                defaults.maxLotOvershoot(), defaults.roundTripFeeRates(), defaults.schemeC()));
        Optional<String> reason = strict.openBlockReason(liquid(btcBinance(), "0.0003", T0), T0);
        assertEquals("EXPECTED_NET_YIELD_TOO_LOW", reason.orElseThrow());
    }

    @Test
    void rejectsWideOpenBasis() {
        SpotPerpSnapshot wide = snapshot(btcBinance(), "0.0003",
                "100000", "100000", "100400", "100410",
                "100400", "100000", new BigDecimal("200000"),
                T0, T0.plus(Duration.ofHours(8)));
        assertEquals("BASIS_ABOVE_MAX", gates.openBlockReason(wide, T0).orElseThrow());
    }

    @Test
    void rejectsDiscountedPerpBelowMinBasis() {
        SpotPerpSnapshot cheapPerp = snapshot(btcBinance(), "0.0003",
                "100000", "100000", "99900", "99910",
                "99900", "100000", new BigDecimal("200000"),
                T0, T0.plus(Duration.ofHours(8)));
        assertEquals("BASIS_BELOW_MIN", gates.openBlockReason(cheapPerp, T0).orElseThrow());
    }

    @Test
    void rejectsSettlementProtectionWindow() {
        Instant next = T0.plus(Duration.ofMinutes(5));
        SpotPerpSnapshot snap = snapshot(btcBinance(), "0.0003",
                "100000", "100000", "100000", "100010",
                "99990", "100000", new BigDecimal("200000"), T0, next);
        assertEquals("WITHIN_SETTLEMENT_PROTECTION", gates.openBlockReason(snap, T0).orElseThrow());
    }

    @Test
    void rejectsStaleQuote() {
        SpotPerpSnapshot snap = liquid(btcBinance(), "0.0003", T0.minus(Duration.ofSeconds(30)));
        assertEquals("QUOTE_STALE", gates.openBlockReason(snap, T0).orElseThrow());
    }

    @Test
    void rejectsThinSpotAskDepth() {
        SpotPerpSnapshot thin = snapshot(btcBinance(), "0.0003",
                "100000", "100000", "100000", "100010",
                "99990", "100000", new BigDecimal("20"),
                T0, T0.plus(Duration.ofHours(8)));
        String reason = gates.openBlockReason(thin, T0).orElseThrow();
        assertTrue(reason.startsWith("INSUFFICIENT_") || reason.equals("INSUFFICIENT_DEPTH"), reason);
    }

    @Test
    void emergencyClosesWhenFundingTurnsNonPositive() {
        SpotPerpPosition position = openForCloseTests();
        SpotPerpSnapshot snap = liquid(btcBinance(), "-0.00001", T0.plus(Duration.ofHours(1)));
        SpotPerpGates.CloseSignal signal = gates.closeSignal(snap, position, T0.plus(Duration.ofHours(1)))
                .orElseThrow();
        assertEquals("FUNDING_NON_POSITIVE", signal.reason());
        assertTrue(signal.emergency());
    }

    @Test
    void holdsThroughWeakFundingUntilMinHoldThenExits() {
        SpotPerpPosition position = openForCloseTests();
        SpotPerpSnapshot weak = liquid(btcBinance(), "0.00001", T0.plus(Duration.ofHours(1)));
        assertTrue(gates.closeSignal(weak, position, T0.plus(Duration.ofHours(1))).isEmpty());
        SpotPerpGates.CloseSignal afterMin = gates.closeSignal(
                liquid(btcBinance(), "0.00001", T0.plus(Duration.ofHours(8))),
                position,
                T0.plus(Duration.ofHours(8))).orElseThrow();
        assertEquals("FUNDING_UNATTRACTIVE", afterMin.reason());
        assertFalse(afterMin.emergency());
    }

    @Test
    void closesOnMaxHoldingTtl() {
        SpotPerpPosition position = openForCloseTests();
        Instant later = T0.plus(Duration.ofDays(21));
        assertEquals("MAX_HOLDING_TTL",
                gates.closeSignal(liquid(btcBinance(), "0.0003", later), position, later)
                        .orElseThrow().reason());
    }

    @Test
    void closesWhenBasisDeterioratesBeyondCap() {
        SpotPerpPosition position = openForCloseTests();
        SpotPerpSnapshot worse = snapshot(btcBinance(), "0.0003",
                "100000", "100000", "100200", "100210",
                "100200", "100000", new BigDecimal("200000"),
                T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(8)));
        assertEquals("BASIS_DETERIORATED",
                gates.closeSignal(worse, position, T0.plus(Duration.ofHours(1))).orElseThrow().reason());
    }

    @Test
    void nearSettlementWindowAdmitsInsideAndRejectsFar() {
        SpotPerpRules near = SpotPerpRules.schemeCTenThousand(
                2, 3, BigDecimal.ZERO, new BigDecimal("0.0020"),
                Duration.ZERO, Duration.ZERO,
                new SpotPerpRules.SchemeCPolicy(Duration.ofMinutes(30), 3,
                        new BigDecimal("0.15"), new BigDecimal("0.004")));
        SpotPerpGates timed = new SpotPerpGates(near);
        Instant next = T0.plus(Duration.ofMinutes(5));
        SpotPerpSnapshot inside = snapshot(btcBinance(), "0.0030",
                "100000", "100000", "100000", "100010",
                "99990", "100000", new BigDecimal("1000000"), T0, next);
        assertTrue(timed.openBlockReason(inside, T0).isEmpty());
        SpotPerpSnapshot far = snapshot(btcBinance(), "0.0030",
                "100000", "100000", "100000", "100010",
                "99990", "100000", new BigDecimal("1000000"), T0,
                T0.plus(Duration.ofHours(8)));
        assertEquals("TOO_FAR_FROM_SETTLEMENT", timed.openBlockReason(far, T0).orElseThrow());
    }

    @Test
    void closesAfterConfiguredSettlementCount() {
        SpotPerpRules holdOne = SpotPerpRules.schemeCTenThousand(
                2, 1, BigDecimal.ZERO, new BigDecimal("0.0020"),
                Duration.ZERO, Duration.ZERO,
                new SpotPerpRules.SchemeCPolicy(Duration.ofMinutes(30), 1,
                        new BigDecimal("0.15"), new BigDecimal("0.004")));
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(holdOne);
        Instant next = T0.plus(Duration.ofMinutes(30));
        SpotPerpSnapshot pre = snapshot(btcBinance(), "0.0050",
                "100000", "100000", "100000", "100010",
                "100000", "100000", new BigDecimal("1000000"), T0, next);
        assertEquals(1, engine.tick(T0, java.util.List.of(pre)).count(SpotPerpDryRunEngine.ActionType.OPENED));
        SpotPerpSnapshot settle = snapshot(btcBinance(), "0.0050",
                "100000", "100000", "100000", "100010",
                "100000", "100000", new BigDecimal("1000000"),
                next, next.plus(Duration.ofHours(8)));
        SpotPerpDryRunEngine.CycleResult cycle = engine.tick(next, java.util.List.of(settle));
        assertEquals(1, cycle.count(SpotPerpDryRunEngine.ActionType.FUNDING_SETTLED));
        assertEquals(1, cycle.count(SpotPerpDryRunEngine.ActionType.CLOSED));
        assertEquals("HOLD_SETTLEMENTS_REACHED", engine.closedPositions().get(0).closeReason());
    }

    @Test
    void emergencyClosesWhenShortPerpLiquidationDistanceCompresses() {
        SpotPerpRules leveraged = SpotPerpRules.schemeCTenThousand(
                3, 3, BigDecimal.ZERO, new BigDecimal("0.0020"),
                Duration.ZERO, Duration.ZERO,
                new SpotPerpRules.SchemeCPolicy(Duration.ofMinutes(30), 3,
                        new BigDecimal("0.15"), new BigDecimal("0.004")));
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(leveraged);
        Instant next = T0.plus(Duration.ofMinutes(20));
        engine.tick(T0, java.util.List.of(snapshot(btcBinance(), "0.0050",
                "100000", "100000", "100000", "100010",
                "100000", "100000", new BigDecimal("1000000"), T0, next)));
        SpotPerpPosition open = engine.position(btcBinance()).orElseThrow();
        BigDecimal atEntry = open.liquidationDistance(new BigDecimal("100000"), new BigDecimal("0.004"));
        assertTrue(atEntry.compareTo(new BigDecimal("0.30")) > 0, atEntry.toPlainString());
        SpotPerpSnapshot squeezed = snapshot(btcBinance(), "0.0050",
                "120000", "120000", "120000", "120010",
                "120000", "120000", new BigDecimal("1000000"),
                T0.plus(Duration.ofMinutes(10)), next);
        assertEquals("LIQUIDATION_DISTANCE",
                new SpotPerpGates(leveraged).closeSignal(squeezed, open, T0.plus(Duration.ofMinutes(10)))
                        .orElseThrow().reason());
    }

    @Test
    void liveRulesAreRejected() {
        SpotPerpRules defaults = testRules();
        assertThrows(IllegalArgumentException.class, () -> new SpotPerpRules(
                false,
                defaults.autoOpenEnabled(),
                defaults.notionalUsdt(),
                defaults.leverage(),
                defaults.openThreshold(),
                defaults.closeThreshold(),
                defaults.minBasisRate(),
                defaults.maxBasisRate(),
                defaults.maxBasisDeteriorationRate(),
                defaults.minNetYieldRate(),
                defaults.minFundingPeriods(),
                defaults.settlementProtection(),
                defaults.minHolding(),
                defaults.maxHolding(),
                defaults.maxQuoteAge(),
                defaults.minBookNotional(),
                defaults.coverageRatio(),
                defaults.maxVwapImpact(),
                defaults.maxLotOvershoot(),
                defaults.roundTripFeeRates(),
                defaults.schemeC()
        ));
    }

    private SpotPerpPosition openForCloseTests() {
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(testRules());
        engine.tick(T0, java.util.List.of(liquid(btcBinance(), "0.0003", T0)));
        return engine.position(btcBinance()).orElseThrow();
    }
}
