package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.T0;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.btcBinance;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.ethHtx;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotPerpSchemeBTest {
    private static final BigDecimal TEN_K = new BigDecimal("10000");
    private static final BigDecimal BUFFER = new BigDecimal("0.0005");

    @Test
    void remainingFundingGateRejectsWhenTwelvePeriodsCannotCoverMakerFeesPlusBuffer() {
        SpotPerpRules rules = SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 12, BUFFER);
        SpotPerpGates gates = new SpotPerpGates(rules);
        SpotPerpSnapshot snap = deep(btcBinance(), "0.0001", T0);
        assertEquals("EXPECTED_NET_YIELD_TOO_LOW", gates.openBlockReason(snap, T0).orElseThrow());
    }

    @Test
    void remainingFundingGateAdmitsElevatedRateThatClearsMakerFeesPlusBuffer() {
        SpotPerpRules rules = SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 12, BUFFER);
        SpotPerpGates gates = new SpotPerpGates(rules);
        assertTrue(gates.openBlockReason(deep(btcBinance(), "0.0003", T0), T0).isEmpty());
    }

    @Test
    void productionTakerWouldStillOpenTheLowPersistencePrint() {
        SpotPerpGates taker = new SpotPerpGates(SpotPerpRules.tenThousandTaker());
        assertTrue(taker.openBlockReason(deep(btcBinance(), "0.0001", T0), T0).isEmpty());
    }

    @Test
    void makerFillsAtPassiveQuotesWithLimitOrderType() {
        SpotPerpRules rules = SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 12, BUFFER);
        SpotPerpPaperExecutor executor = new SpotPerpPaperExecutor(rules,
                SpotPerpExecutionPolicy.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 0));
        SpotPerpSnapshot snap = spreadBook(btcBinance(), "0.0003", T0);
        SpotPerpPaperExecutor.Result opened = executor.open(snap, T0);
        assertTrue(opened.success(), opened.message());
        assertEquals("LIMIT", opened.fills().get(0).orderType());
        assertEquals("LIMIT", opened.fills().get(1).orderType());
        assertEquals(0, new BigDecimal("99900").compareTo(opened.position().spotEntry()));
        assertEquals(0, new BigDecimal("100100").compareTo(opened.position().perpEntry()));
        BigDecimal expectedOpenFee = new BigDecimal("0.1").multiply(new BigDecimal("99900"))
                .multiply(new BigDecimal("0.0010"))
                .add(new BigDecimal("0.1").multiply(new BigDecimal("100100"))
                        .multiply(new BigDecimal("0.0002")));
        assertEquals(0, expectedOpenFee.setScale(8, RoundingMode.HALF_UP)
                .compareTo(opened.position().estimatedFees()));
    }

    @Test
    void mixedPerpMakerTakesSpotAndRestsPerp() {
        SpotPerpRules rules = SpotPerpRules.schemeB(
                SpotPerpExecutionStyle.MIXED_SPOT_TAKER_PERP_MAKER, 12, BUFFER);
        SpotPerpPaperExecutor executor = new SpotPerpPaperExecutor(rules,
                SpotPerpExecutionPolicy.schemeB(SpotPerpExecutionStyle.MIXED_SPOT_TAKER_PERP_MAKER, 0));
        SpotPerpPaperExecutor.Result opened = executor.open(spreadBook(btcBinance(), "0.0003", T0), T0);
        assertTrue(opened.success(), opened.message());
        assertEquals("MARKET_IOC", opened.fills().get(0).orderType());
        assertEquals("LIMIT", opened.fills().get(1).orderType());
        assertEquals("SPOT", opened.fills().get(0).leg());
        assertEquals("PERP", opened.fills().get(1).leg());
    }

    @Test
    void makerDelayRestsOneBarThenOpens() {
        SpotPerpRules rules = SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 12, BUFFER);
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(rules,
                SpotPerpExecutionPolicy.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 1));
        SpotPerpDryRunEngine.CycleResult rest = engine.tick(T0, List.of(deep(btcBinance(), "0.0003", T0)));
        assertEquals(1, rest.count(SpotPerpDryRunEngine.ActionType.SKIPPED));
        assertEquals("MAKER_RESTING", rest.actions().get(0).reason());
        assertTrue(engine.openPositions().isEmpty());
        Instant next = T0.plus(Duration.ofHours(8));
        SpotPerpDryRunEngine.CycleResult filled = engine.tick(next, List.of(deep(btcBinance(), "0.0003", next)));
        assertEquals(1, filled.count(SpotPerpDryRunEngine.ActionType.OPENED));
        assertEquals(1, engine.openPositions().size());
    }

    @Test
    void makerDelayCancelsWhenFundingDiesWhileResting() {
        SpotPerpRules rules = SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 12, BUFFER);
        SpotPerpDryRunEngine engine = new SpotPerpDryRunEngine(rules,
                SpotPerpExecutionPolicy.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 1));
        engine.tick(T0, List.of(deep(btcBinance(), "0.0003", T0)));
        Instant next = T0.plus(Duration.ofHours(8));
        SpotPerpDryRunEngine.CycleResult cancelled = engine.tick(next,
                List.of(deep(btcBinance(), "0", next)));
        assertEquals("MAKER_UNFILLED_FUNDING_NON_POSITIVE", cancelled.actions().get(0).reason());
        assertTrue(engine.openPositions().isEmpty());
    }

    @Test
    void compareReplayShowsSchemeBSkippingLowRemainingPrintsThatTakerTakes() {
        List<SpotPerpHistoryClient.FundingPrint> prints = new ArrayList<>();
        Instant time = T0;
        for (int i = 0; i < 16; i++) {
            String rate = i < 12 ? "0.0001" : "0.00001";
            prints.add(print(btcBinance(), time, rate, "100000"));
            prints.add(print(ethHtx(), time, "0.00004", "4000"));
            time = time.plus(Duration.ofHours(8));
        }
        List<SpotPerpSchemeCompare.Scheme> schemes = List.of(
                new SpotPerpSchemeCompare.Scheme("A", "taker prod",
                        SpotPerpRules.tenThousandTaker(),
                        SpotPerpExecutionPolicy.productionTaker()),
                new SpotPerpSchemeCompare.Scheme("B", "maker remaining",
                        SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 12, BUFFER),
                        SpotPerpExecutionPolicy.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 0)));
        SpotPerpSchemeCompare.Report report = new SpotPerpSchemeCompare().run(
                prints, T0, time, List.of(), schemes);
        SpotPerpSchemeCompare.Row takerBtc = row(report, "A", "BINANCE:BTCUSDT");
        SpotPerpSchemeCompare.Row makerBtc = row(report, "B", "BINANCE:BTCUSDT");
        assertTrue(takerBtc.stats().opens() >= 1, "taker should open 1bp/8h under 30-period persistence");
        assertEquals(0, makerBtc.stats().opens());
        assertTrue(report.asText().contains("NetBps"));
        assertEquals(0, TEN_K.compareTo(SpotPerpRules.schemeB(
                SpotPerpExecutionStyle.MAKER_LIMIT, 12, BUFFER).notionalUsdt()));
    }

    @Test
    void schemeBNeverEnablesLiveTrading() {
        SpotPerpRules rules = SpotPerpRules.schemeB(SpotPerpExecutionStyle.MAKER_LIMIT, 12, BUFFER);
        assertTrue(rules.dryRun());
    }

    private static SpotPerpSchemeCompare.Row row(SpotPerpSchemeCompare.Report report, String scheme,
            String key) {
        return report.rows().stream()
                .filter(item -> item.schemeId().equals(scheme) && item.stats().instrument().key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static SpotPerpHistoryClient.FundingPrint print(
            SpotPerpInstrument instrument, Instant time, String rate, String mark) {
        return new SpotPerpHistoryClient.FundingPrint(
                instrument, time, new BigDecimal(rate), new BigDecimal(mark),
                new BigDecimal(mark), "fixture");
    }

    private static SpotPerpSnapshot deep(SpotPerpInstrument instrument, String rate, Instant now) {
        return snapshot(instrument, rate,
                "100000", "100000", "100000", "100010",
                "100000", "100000", new BigDecimal("1000000"),
                now, now.plus(Duration.ofHours(8)));
    }

    private static SpotPerpSnapshot spreadBook(SpotPerpInstrument instrument, String rate, Instant now) {
        return snapshot(instrument, rate,
                "99900", "100000", "100000", "100100",
                "100000", "100000", new BigDecimal("1000000"),
                now, now.plus(Duration.ofHours(8)));
    }
}
