package com.metaverse.coin.strategy.htx.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendCombinationMarketRiskGateTest {

    @Test
    void rejectsOnlyCandidatesThatFightAConfirmedBroadDirection() {
        assertTrue(HtxTrendCombinationMarketRiskGate.opposesBroadMarket("LONG", "BROAD_DOWN"));
        assertTrue(HtxTrendCombinationMarketRiskGate.opposesBroadMarket("SHORT", "BROAD_UP"));
        assertFalse(HtxTrendCombinationMarketRiskGate.opposesBroadMarket("LONG", "BROAD_UP"));
        assertFalse(HtxTrendCombinationMarketRiskGate.opposesBroadMarket("SHORT", "MIXED"));
    }
}
