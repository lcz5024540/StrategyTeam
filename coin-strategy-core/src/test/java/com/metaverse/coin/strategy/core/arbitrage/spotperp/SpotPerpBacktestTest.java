package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.T0;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.btcBinance;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.ethHtx;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.testRules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotPerpBacktestTest {
    @Test
    void replaysFundingPrintsThroughTheProductionEngine() {
        List<SpotPerpHistoryClient.FundingPrint> prints = new ArrayList<>();
        Instant t = T0;
        for (int i = 0; i < 6; i++) {
            String rate = i < 4 ? "0.0003" : "0.00001";
            prints.add(print(btcBinance(), t, rate, "100000"));
            prints.add(print(ethHtx(), t, i < 4 ? "0.0004" : "0.00001", "4000"));
            t = t.plus(Duration.ofHours(8));
        }
        SpotPerpBacktest.Report report = new SpotPerpBacktest().replay(
                prints, testRules(), T0, t, List.of());
        SpotPerpBacktest.InstrumentStats btc = report.stats().stream()
                .filter(row -> row.instrument().equals(btcBinance()))
                .findFirst()
                .orElseThrow();
        assertEquals(6, btc.prints());
        assertEquals(1, btc.opens());
        assertEquals(1, btc.closes());
        assertTrue(btc.settlements() >= 1);
        assertTrue(btc.fundingPnl().signum() > 0);
        assertTrue(report.method().contains("no historical L2"));
    }

    @Test
    void accruesEveryPrintWhileOpenWhenFundingTimeHasMillisecondJitter() {
        List<SpotPerpHistoryClient.FundingPrint> prints = new ArrayList<>();
        Instant t = T0;
        for (int i = 0; i < 8; i++) {
            Instant stamped = t.plusMillis(i % 2 == 0 ? 0 : 7);
            prints.add(print(btcBinance(), stamped, "0.0003", "100000"));
            t = t.plus(Duration.ofHours(8));
        }
        SpotPerpBacktest.Report report = new SpotPerpBacktest().replay(
                prints, testRules(), T0, t, List.of());
        SpotPerpBacktest.InstrumentStats btc = report.stats().stream()
                .filter(row -> row.instrument().equals(btcBinance()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, btc.opens());
        assertEquals(0, btc.closes());
        assertEquals(7, btc.settlements());
        assertEquals(0, new BigDecimal("0.21").compareTo(btc.fundingPnl()));
    }

    @Test
    void reportsEmptySeriesWhenAVenueHasNoPrints() {
        SpotPerpBacktest.Report report = new SpotPerpBacktest().replay(
                List.of(), testRules(), T0, T0.plus(Duration.ofDays(1)),
                List.of("HTX:BTCUSDT: connection timed out"));
        assertEquals(1, report.fetchErrors().size());
        assertEquals(0, report.stats().stream().mapToInt(SpotPerpBacktest.InstrumentStats::prints).sum());
    }

    @Test
    void feeAndFundingRatiosAreScaleInvariantExceptLotRounding() {
        List<SpotPerpHistoryClient.FundingPrint> prints = new ArrayList<>();
        Instant t = T0;
        for (int i = 0; i < 40; i++) {
            String rate = i < 32 ? "0.0003" : "0.00001";
            prints.add(print(btcBinance(), t, rate, "100000"));
            prints.add(print(ethHtx(), t, rate, "4000"));
            t = t.plus(Duration.ofHours(8));
        }
        SpotPerpBacktest backtest = new SpotPerpBacktest();
        SpotPerpRules large = SpotPerpRules.defaults();
        SpotPerpRules small = large.withNotional(SpotPerpBacktest.SMALL_NOTIONAL);
        assertEquals(0, SpotPerpBacktest.SCHEME_A_NOTIONAL.compareTo(large.notionalUsdt()));
        SpotPerpBacktest.Report report100 = backtest.replay(prints, small, T0, t, List.of());
        SpotPerpBacktest.Report report10k = backtest.replay(prints, large, T0, t, List.of());
        SpotPerpBacktest.ScaleComparison comparison = backtest.compareScale(report100, report10k);
        assertTrue(report10k.asText().contains("FundBps"));
        assertTrue(comparison.asText().contains("scale-invariant"));

        BigDecimal maxRelativeDrift = new BigDecimal("0.02");
        for (SpotPerpBacktest.ScaleRow row : comparison.rows()) {
            assertEquals(row.opensSmall(), row.opensLarge(), row.instrument().key());
            if (row.opensLarge() == 0) {
                continue;
            }
            assertCloseRatio(row.fundingScale(), row.expectedScale(), maxRelativeDrift, row.instrument().key() + " funding");
            assertCloseRatio(row.feesScale(), row.expectedScale(), maxRelativeDrift, row.instrument().key() + " fees");
            assertCloseRatio(row.feeToFundingLarge(), row.feeToFundingSmall(), maxRelativeDrift,
                    row.instrument().key() + " fee/funding");
            assertCloseRatio(row.fundingBpsLarge(), row.fundingBpsSmall(), maxRelativeDrift,
                    row.instrument().key() + " funding bps");
            assertCloseRatio(row.feesBpsLarge(), row.feesBpsSmall(), maxRelativeDrift,
                    row.instrument().key() + " fee bps");
        }
    }

    private static void assertCloseRatio(BigDecimal actual, BigDecimal expected, BigDecimal maxRelativeDrift,
            String label) {
        assertTrue(actual != null && expected != null && expected.signum() != 0, label + " missing ratio");
        BigDecimal drift = actual.subtract(expected).abs().divide(expected.abs(), 8, java.math.RoundingMode.HALF_UP);
        assertTrue(drift.compareTo(maxRelativeDrift) <= 0,
                label + " actual=" + actual.toPlainString() + " expected=" + expected.toPlainString()
                        + " drift=" + drift.toPlainString());
    }

    private static SpotPerpHistoryClient.FundingPrint print(
            SpotPerpInstrument instrument, Instant time, String rate, String mark) {
        return new SpotPerpHistoryClient.FundingPrint(
                instrument, time, new BigDecimal(rate), new BigDecimal(mark),
                new BigDecimal(mark), "fixture");
    }
}
