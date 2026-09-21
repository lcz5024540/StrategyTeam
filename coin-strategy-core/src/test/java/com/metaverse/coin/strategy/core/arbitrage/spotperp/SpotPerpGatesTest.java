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
                defaults.maxLotOvershoot(), defaults.roundTripFeeRates()));
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
                defaults.roundTripFeeRates()
        ));
    }

    private SpotPerpPosition openForCloseTests() {
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(testRules());
        engine.tick(T0, java.util.List.of(liquid(btcBinance(), "0.0003", T0)));
        return engine.position(btcBinance()).orElseThrow();
    }
}
