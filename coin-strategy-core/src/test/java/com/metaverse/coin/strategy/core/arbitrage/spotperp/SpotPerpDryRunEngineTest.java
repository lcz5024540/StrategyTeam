package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.T0;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.btcBinance;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.ethHtx;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.liquid;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.snapshot;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.testRules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotPerpDryRunEngineTest {
    @Test
    void opensPaperHedgeThenHarvestsFundingThenClosesWhenUnattractive() {
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(testRules());
        SpotPerpDryRunEngine.CycleResult opened = engine.tick(T0, List.of(liquid(btcBinance(), "0.0003", T0)));
        assertEquals(1, opened.count(SpotPerpDryRunEngine.ActionType.OPENED));
        SpotPerpPosition position = engine.position(btcBinance()).orElseThrow();
        assertEquals(SpotPerpPosition.Status.OPENED, position.status());
        assertEquals(2, position.fills().size());
        assertEquals("BUY", position.fills().get(0).side());
        assertEquals("SELL", position.fills().get(1).side());
        assertEquals("MARKET_IOC", position.fills().get(0).orderType());

        SpotPerpSnapshot settlement = liquid(btcBinance(), "0.0003", T0.plus(Duration.ofHours(8)));
        settlement = withNext(settlement, T0.plus(Duration.ofHours(16)));
        SpotPerpDryRunEngine.CycleResult funded = engine.tick(T0.plus(Duration.ofHours(8)), List.of(settlement));
        assertEquals(1, funded.count(SpotPerpDryRunEngine.ActionType.FUNDING_SETTLED));
        BigDecimal expected = position.quantity().multiply(new BigDecimal("99990")).multiply(new BigDecimal("0.0003"));
        assertEquals(0, expected.compareTo(engine.position(btcBinance()).orElseThrow().fundingFeesEarned()));

        Instant closeAt = T0.plus(Duration.ofHours(8)).plusSeconds(1);
        SpotPerpSnapshot weak = liquid(btcBinance(), "0.00001", closeAt);
        weak = withNext(weak, T0.plus(Duration.ofHours(16)));
        SpotPerpDryRunEngine.CycleResult closed = engine.tick(closeAt, List.of(weak));
        assertEquals(1, closed.count(SpotPerpDryRunEngine.ActionType.CLOSED));
        assertTrue(engine.position(btcBinance()).isEmpty());
        assertEquals("FUNDING_UNATTRACTIVE", engine.closedPositions().get(0).closeReason());
        assertEquals(0, expected.compareTo(engine.closedPositions().get(0).fundingFeesEarned()));
    }

    @Test
    void doesNotOpenASecondPositionOnTheSameInstrument() {
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(testRules());
        engine.tick(T0, List.of(liquid(btcBinance(), "0.0003", T0)));
        SpotPerpDryRunEngine.CycleResult again = engine.tick(T0.plusSeconds(30),
                List.of(liquid(btcBinance(), "0.0003", T0.plusSeconds(30))));
        assertEquals(0, again.count(SpotPerpDryRunEngine.ActionType.OPENED));
        assertEquals(1, engine.openPositions().size());
    }

    @Test
    void tracksBinanceBtcAndHtxEthIndependently() {
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(testRules());
        SpotPerpSnapshot eth = snapshot(ethHtx(), "0.0004",
                "4000", "4000", "4000", "4001",
                "4000", "4000", new BigDecimal("200000"),
                T0, T0.plus(Duration.ofHours(8)));
        engine.tick(T0, List.of(liquid(btcBinance(), "0.0003", T0), eth));
        assertEquals(2, engine.openPositions().size());
        assertTrue(engine.position(btcBinance()).isPresent());
        assertTrue(engine.position(ethHtx()).isPresent());
    }

    @Test
    void skipsInsteadOfOpeningWhenGatesFail() {
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(testRules());
        SpotPerpDryRunEngine.CycleResult cycle = engine.tick(T0, List.of(liquid(btcBinance(), "0", T0)));
        assertEquals(1, cycle.count(SpotPerpDryRunEngine.ActionType.SKIPPED));
        assertEquals("FUNDING_NON_POSITIVE", cycle.actions().get(0).reason());
        assertTrue(engine.openPositions().isEmpty());
    }

    @Test
    void emergencyClosesOnNegativeFundingDuringMinHold() {
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(testRules());
        engine.tick(T0, List.of(liquid(btcBinance(), "0.0003", T0)));
        SpotPerpDryRunEngine.CycleResult cycle = engine.tick(T0.plus(Duration.ofHours(1)),
                List.of(liquid(btcBinance(), "-0.0002", T0.plus(Duration.ofHours(1)))));
        assertEquals(1, cycle.count(SpotPerpDryRunEngine.ActionType.CLOSED));
        assertEquals("FUNDING_NON_POSITIVE", engine.closedPositions().get(0).closeReason());
    }

    private static SpotPerpSnapshot withNext(SpotPerpSnapshot snapshot, Instant next) {
        return new SpotPerpSnapshot(
                snapshot.instrument(), snapshot.fundingRate(), snapshot.fundingIntervalHours(),
                next, snapshot.markPrice(), snapshot.indexPrice(), snapshot.spotBid(), snapshot.spotAsk(),
                snapshot.perpBid(), snapshot.perpAsk(), snapshot.spotBids(), snapshot.spotAsks(),
                snapshot.perpBids(), snapshot.perpAsks(), snapshot.quantityStep(),
                snapshot.minimumNotional(), snapshot.receivedAt());
    }
}
