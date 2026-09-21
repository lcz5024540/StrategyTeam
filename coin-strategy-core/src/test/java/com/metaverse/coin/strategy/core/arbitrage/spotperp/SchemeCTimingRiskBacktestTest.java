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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemeCTimingRiskBacktestTest {
    @Test
    void nearOneSettlementCollectsTheImminentPrintThatControlMisses() {
        List<SpotPerpHistoryClient.FundingPrint> prints = new ArrayList<>();
        prints.add(print(btcBinance(), T0, "0.0050", "100000"));
        prints.add(print(btcBinance(), T0.plus(Duration.ofHours(8)), "0.00001", "100000"));
        prints.add(print(ethHtx(), T0, "0.0050", "4000"));
        prints.add(print(ethHtx(), T0.plus(Duration.ofHours(8)), "0.00001", "4000"));

        SchemeCTimingRiskBacktest backtest = new SchemeCTimingRiskBacktest();
        SchemeCTimingRiskBacktest.Variant control = SchemeCTimingRiskBacktest.catalog().stream()
                .filter(variant -> variant.id().equals("C0"))
                .findFirst()
                .orElseThrow();
        SchemeCTimingRiskBacktest.Variant nearOne = SchemeCTimingRiskBacktest.catalog().stream()
                .filter(variant -> variant.id().equals("C1"))
                .findFirst()
                .orElseThrow();

        SchemeCTimingRiskBacktest.VariantResult c0 = backtest.replay(control, prints);
        SchemeCTimingRiskBacktest.VariantResult c1 = backtest.replay(nearOne, prints);

        SchemeCTimingRiskBacktest.InstrumentRow c0Btc = row(c0, btcBinance());
        SchemeCTimingRiskBacktest.InstrumentRow c1Btc = row(c1, btcBinance());
        assertEquals(1, c0Btc.opens());
        assertEquals(1, c0Btc.settlements());
        assertEquals(1, c1Btc.opens());
        assertEquals(1, c1Btc.settlements());
        assertTrue(c1Btc.fundingPnl().compareTo(c0Btc.fundingPnl()) > 0,
                c1Btc.fundingPnl() + " vs " + c0Btc.fundingPnl());
        assertTrue(c1Btc.netAfterFees().signum() > 0);
        assertEquals("HOLD_SETTLEMENTS_REACHED", c1Btc.closeReasons().keySet().iterator().next());
        assertEquals(0, SchemeCTimingRiskBacktest.toBps(new BigDecimal("28"), new BigDecimal("10000"))
                .compareTo(new BigDecimal("28.0000")));
    }

    @Test
    void catalogCoversTimingHoldLeverageBasisAndLiqStops() {
        List<String> ids = SchemeCTimingRiskBacktest.catalog().stream()
                .map(SchemeCTimingRiskBacktest.Variant::id)
                .toList();
        assertEquals(List.of("C0", "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C2L"), ids);
        SchemeCTimingRiskBacktest.Variant threeX = SchemeCTimingRiskBacktest.catalog().stream()
                .filter(variant -> variant.id().equals("C6"))
                .findFirst()
                .orElseThrow();
        assertEquals(3, threeX.rules().leverage());
        assertTrue(threeX.rules().capitalUsdt().compareTo(new BigDecimal("13333")) > 0);
        assertTrue(threeX.rules().capitalUsdt().compareTo(new BigDecimal("13334")) < 0);
        assertTrue(threeX.rules().schemeC().liquidationStopEnabled());
    }

    private static SchemeCTimingRiskBacktest.InstrumentRow row(
            SchemeCTimingRiskBacktest.VariantResult result, SpotPerpInstrument instrument) {
        return result.rows().stream()
                .filter(item -> item.instrument().equals(instrument))
                .findFirst()
                .orElseThrow();
    }

    private static SpotPerpHistoryClient.FundingPrint print(
            SpotPerpInstrument instrument, Instant time, String rate, String mark) {
        return new SpotPerpHistoryClient.FundingPrint(
                instrument, time, new BigDecimal(rate), new BigDecimal(mark),
                new BigDecimal(mark), "fixture");
    }
}
