package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.T0;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.btcBinance;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.liquid;
import static com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpTestSupport.testRules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotPerpPaperExecutorTest {
    @Test
    void opensSpotBuyThenPerpSellAsIocPaperFills() {
        SpotPerpPaperExecutor executor = new SpotPerpPaperExecutor(testRules());
        SpotPerpPaperExecutor.Result result = executor.open(liquid(btcBinance(), "0.0003", T0), T0);
        assertTrue(result.success(), result.message());
        assertEquals(2, result.fills().size());
        assertEquals("SPOT", result.fills().get(0).leg());
        assertEquals("BUY", result.fills().get(0).side());
        assertEquals("PERP", result.fills().get(1).leg());
        assertEquals("SELL", result.fills().get(1).side());
        assertEquals("MARKET_IOC", result.fills().get(0).orderType());
        assertEquals(0, new BigDecimal("0.001").compareTo(result.position().quantity()));
        assertTrue(result.position().estimatedFees().signum() > 0);
        BigDecimal expectedOpenFee = result.position().quantity()
                .multiply(result.position().spotEntry().add(result.position().perpEntry()))
                .multiply(testRules().roundTripFee(btcBinance().venue())
                        .divide(new BigDecimal("4"), 18, java.math.RoundingMode.HALF_UP));
        assertEquals(0, expectedOpenFee.setScale(8, java.math.RoundingMode.HALF_UP)
                .compareTo(result.position().estimatedFees()));
    }

    @Test
    void closesWithPerpBuyThenSpotSell() {
        SpotPerpPaperExecutor executor = new SpotPerpPaperExecutor(testRules());
        SpotPerpPosition position = executor.open(liquid(btcBinance(), "0.0003", T0), T0).position();
        SpotPerpPaperExecutor.Result closed = executor.close(
                position, liquid(btcBinance(), "0.0003", T0), T0, "TEST_CLOSE");
        assertTrue(closed.success(), closed.message());
        assertEquals(SpotPerpPosition.Status.CLOSED, position.status());
        assertEquals("PERP", position.fills().get(2).leg());
        assertEquals("BUY", position.fills().get(2).side());
        assertEquals("SPOT", position.fills().get(3).leg());
        assertEquals("SELL", position.fills().get(3).side());
    }
}
