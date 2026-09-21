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
    void reportsEmptySeriesWhenAVenueHasNoPrints() {
        SpotPerpBacktest.Report report = new SpotPerpBacktest().replay(
                List.of(), testRules(), T0, T0.plus(Duration.ofDays(1)),
                List.of("HTX:BTCUSDT: connection timed out"));
        assertEquals(1, report.fetchErrors().size());
        assertEquals(0, report.stats().stream().mapToInt(SpotPerpBacktest.InstrumentStats::prints).sum());
    }

    private static SpotPerpHistoryClient.FundingPrint print(
            SpotPerpInstrument instrument, Instant time, String rate, String mark) {
        return new SpotPerpHistoryClient.FundingPrint(
                instrument, time, new BigDecimal(rate), new BigDecimal(mark),
                new BigDecimal(mark), "fixture");
    }
}
