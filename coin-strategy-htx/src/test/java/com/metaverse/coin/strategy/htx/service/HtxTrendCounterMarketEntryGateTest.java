package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendCounterMarketEntryGateTest {

    @Test
    void rayLikeIndependentLongCanQualifyAgainstBroadDownMarket() {
        HtxTrendCounterMarketEntryGate.Decision decision = HtxTrendCounterMarketEntryGate.evaluate(input(
                true, "BROAD_DOWN", new BigDecimal("90.4"), new BigDecimal("2.5459"),
                new BigDecimal("69.24"), new BigDecimal("38.48"), new BigDecimal("2.2283"),
                new BigDecimal("2.1865"), "BOX_BREAKOUT,DONCHIAN_BREAKOUT"));

        assertTrue(decision.counterMarket());
        assertTrue(decision.qualified());
    }

    @Test
    void localReboundWithoutIndependentBreakoutRemainsBlocked() {
        HtxTrendCounterMarketEntryGate.Decision decision = HtxTrendCounterMarketEntryGate.evaluate(input(
                true, "BROAD_DOWN", new BigDecimal("93"), new BigDecimal("2.3"),
                new BigDecimal("72"), new BigDecimal("31"), new BigDecimal("101"),
                new BigDecimal("100"), "EMA_VWAP_MOMENTUM_CONTINUATION"));

        assertTrue(decision.blocksEntry());
        assertFalse(decision.qualified());
    }

    @Test
    void featureFlagKeepsLiveCounterEntriesDisabledUntilReplayApproval() {
        HtxTrendCounterMarketEntryGate.Decision decision = HtxTrendCounterMarketEntryGate.evaluate(input(
                false, "BROAD_DOWN", new BigDecimal("95"), new BigDecimal("2.4"),
                new BigDecimal("72"), new BigDecimal("31"), new BigDecimal("101"),
                new BigDecimal("100"), "DONCHIAN_BREAKOUT"));

        assertTrue(decision.blocksEntry());
    }

    private static HtxTrendCounterMarketEntryGate.Input input(boolean enabled, String regime, BigDecimal modelScore,
                                                               BigDecimal expectedR, BigDecimal momentum, BigDecimal adx,
                                                               BigDecimal emaFast, BigDecimal emaSlow, String confirmations) {
        return new HtxTrendCounterMarketEntryGate.Input(enabled, TrendSide.LONG, regime, modelScore, expectedR,
                momentum, adx, emaFast, emaSlow, confirmations, new BigDecimal("88"), new BigDecimal("2.00"),
                new BigDecimal("65"), new BigDecimal("25"));
    }
}
