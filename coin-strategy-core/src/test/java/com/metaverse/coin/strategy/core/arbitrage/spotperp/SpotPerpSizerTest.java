package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.T0;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.btcBinance;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.ethHtx;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.liquid;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.snapshot;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.testRules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotPerpSizerTest {
    private final SpotPerpSizer sizer = new SpotPerpSizer(testRules());

    @Test
    void sizesBtcToTargetNotionalOnFineLot() {
        SpotPerpSizer.Result result = sizer.size(liquid(btcBinance(), "0.0003", T0));
        assertTrue(result.accepted());
        assertEquals(0, new BigDecimal("0.001").compareTo(result.quantity()));
        assertEquals(0, new BigDecimal("100").compareTo(result.spotNotional()));
    }

    @Test
    void usesOneLotWhenTargetIsBelowStepAndOvershootAllowsIt() {
        SpotPerpSnapshot coarse = snapshot(btcBinance(), "0.0003",
                "110000", "110000", "110000", "110010",
                "110000", "110000", new BigDecimal("200000"),
                T0, T0.plus(Duration.ofHours(8)));
        coarse = new SpotPerpSnapshot(
                coarse.instrument(), coarse.fundingRate(), coarse.fundingIntervalHours(),
                coarse.nextFundingTime(), coarse.markPrice(), coarse.indexPrice(),
                coarse.spotBid(), coarse.spotAsk(), coarse.perpBid(), coarse.perpAsk(),
                coarse.spotBids(), coarse.spotAsks(), coarse.perpBids(), coarse.perpAsks(),
                new BigDecimal("0.001"),
                new BigDecimal("5"),
                coarse.receivedAt()
        );
        SpotPerpSizer.Result result = sizer.size(coarse);
        assertTrue(result.accepted(), result.rejectionReason());
        assertEquals(0, new BigDecimal("0.001").compareTo(result.quantity()));
        assertTrue(result.spotNotional().compareTo(new BigDecimal("100")) > 0);
        assertTrue(result.spotNotional().compareTo(new BigDecimal("125")) <= 0);
    }

    @Test
    void rejectsWhenOneLotExceedsOvershootCap() {
        SpotPerpSnapshot expensive = snapshot(btcBinance(), "0.0003",
                "200000", "200000", "200000", "200010",
                "200000", "200000", new BigDecimal("200000"),
                T0, T0.plus(Duration.ofHours(8)));
        expensive = new SpotPerpSnapshot(
                expensive.instrument(), expensive.fundingRate(), expensive.fundingIntervalHours(),
                expensive.nextFundingTime(), expensive.markPrice(), expensive.indexPrice(),
                expensive.spotBid(), expensive.spotAsk(), expensive.perpBid(), expensive.perpAsk(),
                expensive.spotBids(), expensive.spotAsks(), expensive.perpBids(), expensive.perpAsks(),
                new BigDecimal("0.001"),
                new BigDecimal("5"),
                expensive.receivedAt()
        );
        SpotPerpSizer.Result result = sizer.size(expensive);
        assertFalse(result.accepted());
        assertEquals("SIZED_NOTIONAL_TOO_SMALL_FOR_LOT", result.rejectionReason());
    }

    @Test
    void sizesEthOnHtxNativeInstrument() {
        SpotPerpSnapshot eth = snapshot(ethHtx(), "0.0004",
                "4000", "4000", "4000", "4001",
                "4000", "4000", new BigDecimal("200000"),
                T0, T0.plus(Duration.ofHours(8)));
        SpotPerpSizer.Result result = sizer.size(eth);
        assertTrue(result.accepted(), result.rejectionReason());
        assertEquals(0, new BigDecimal("0.025").compareTo(result.quantity()));
        assertEquals("HTX", eth.instrument().venue().name());
        assertEquals("ethusdt", eth.instrument().spotSymbol());
        assertEquals("ETH-USDT", eth.instrument().perpExchangeSymbol());
    }
}
