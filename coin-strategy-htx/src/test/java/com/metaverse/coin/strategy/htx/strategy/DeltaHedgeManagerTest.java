package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class DeltaHedgeManagerTest {

    private final DeltaHedgeManager manager = new DeltaHedgeManager();

    @Test void missingFundingOrBasisFailsClosedAndDoesNotOpenShort() {
        DeltaHedgeManager.HedgeDecision decision = manager.plan(input(
                new BigDecimal("1.0"), 0L, false, true, true));

        assertEquals(DeltaHedgeManager.Action.NONE, decision.action());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        assertNull(decision.orderRequest());
    }

    @Test void healthyInventoryOpensOneAggregateShortWithoutReduceOnly() {
        DeltaHedgeManager.HedgeDecision decision = manager.plan(input(
                new BigDecimal("1.0"), 0L, true, true, true));

        assertEquals(DeltaHedgeManager.Action.OPEN_SHORT, decision.action());
        assertEquals(0, new BigDecimal("0.500").compareTo(decision.targetShortBase()));
        HtxSwapOrderRequest request = decision.orderRequest();
        assertEquals("XAUT-USDT", request.getContractCode());
        assertEquals("short", request.getPositionSide());
        assertEquals("sell", request.getSide());
        assertEquals("500", request.getVolume());
        assertFalse(request.getReduceOnly());
    }

    @Test void excessShortClosesWithReduceOnly() {
        DeltaHedgeManager.HedgeDecision decision = manager.plan(input(
                new BigDecimal("0.4"), 500L, true, true, true));

        assertEquals(DeltaHedgeManager.Action.CLOSE_SHORT, decision.action());
        assertEquals(0, new BigDecimal("0.200").compareTo(decision.targetShortBase()));
        HtxSwapOrderRequest request = decision.orderRequest();
        assertEquals("buy", request.getSide());
        assertEquals("short", request.getPositionSide());
        assertEquals("300", request.getVolume());
        assertTrue(request.getReduceOnly());
    }

    @Test void targetShortIsCappedAndRoundedDownToContractStep() {
        DeltaHedgeManager.HedgeDecision decision = manager.plan(new DeltaHedgeManager.HedgeInput(
                true, "xautusdt", "XAUT-USDT", new BigDecimal("10"),
                0L, new BigDecimal("0.003"), BigDecimal.ONE,
                new BigDecimal("0.010"), true, true, true, "CS-MM-HEDGE-XAUTUSDT", 8L));

        assertEquals(DeltaHedgeManager.Action.OPEN_SHORT, decision.action());
        assertEquals(0, new BigDecimal("0.009").compareTo(decision.targetShortBase()));
        assertEquals("3", decision.orderRequest().getVolume());
    }

    private static DeltaHedgeManager.HedgeInput input(BigDecimal inventory, long currentShortContracts,
                                                      boolean fundingAllowed, boolean basisAllowed,
                                                      boolean marginSafe) {
        return new DeltaHedgeManager.HedgeInput(true, "xautusdt", "XAUT-USDT", inventory,
                currentShortContracts, new BigDecimal("0.001"), new BigDecimal("0.5"),
                new BigDecimal("2"), fundingAllowed, basisAllowed, marginSafe,
                "CS-MM-HEDGE-XAUTUSDT", 7L);
    }
}
