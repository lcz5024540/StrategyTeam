package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class InventoryRecyclePlannerTest {

    private final InventoryRecyclePlanner planner = new InventoryRecyclePlanner();

    @Test void confirmedSpotSellClosesEquivalentShortReduceOnly() {
        InventoryRecyclePlanner.RecycleDecision decision = planner.afterSpotSellFill(new InventoryRecyclePlanner.RecycleInput(
                "xautusdt", "XAUT-USDT", new BigDecimal("0.015"),
                new BigDecimal("0.020"), new BigDecimal("0.001"),
                "CS-MM-RECYCLE-XAUTUSDT", 3L));

        assertEquals(MovingGridEngine.RUNNING, decision.state());
        assertEquals(15L, decision.closeContracts());
        HtxSwapOrderRequest request = decision.closeShortRequest();
        assertEquals("buy", request.getSide());
        assertEquals("short", request.getPositionSide());
        assertEquals("15", request.getVolume());
        assertTrue(request.getReduceOnly());
    }

    @Test void missingSpotFillEntersHedgeDegraded() {
        InventoryRecyclePlanner.RecycleDecision decision = planner.afterSpotSellFill(new InventoryRecyclePlanner.RecycleInput(
                "xautusdt", "XAUT-USDT", null, new BigDecimal("0.020"),
                new BigDecimal("0.001"), "CS-MM-RECYCLE-XAUTUSDT", 3L));

        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.closeShortRequest());
    }

    @Test void closeAmountNeverExceedsAvailableShort() {
        InventoryRecyclePlanner.RecycleDecision decision = planner.afterSpotSellFill(new InventoryRecyclePlanner.RecycleInput(
                "xautusdt", "XAUT-USDT", new BigDecimal("0.015"),
                new BigDecimal("0.004"), new BigDecimal("0.001"),
                "CS-MM-RECYCLE-XAUTUSDT", 3L));

        assertEquals(4L, decision.closeContracts());
        assertEquals("4", decision.closeShortRequest().getVolume());
        assertTrue(decision.closeShortRequest().getReduceOnly());
    }
}
