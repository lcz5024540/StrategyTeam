package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxMovingGridStressReplayTest {

    private static final String SYMBOL = "xautusdt";
    private static final String CONTRACT_CODE = "XAUT-USDT";
    private static final BigDecimal STEP_PCT = new BigDecimal("0.01");
    private static final BigDecimal HYSTERESIS_PCT = new BigDecimal("0.001");
    private static final BigDecimal CONTRACT_SIZE = new BigDecimal("0.001");
    private static final BigDecimal HEDGE_RATIO = new BigDecimal("0.5");
    private static final BigDecimal MAX_SHORT_BASE = new BigDecimal("0.020");

    private final MovingGridEngine gridEngine = new MovingGridEngine();
    private final DeltaHedgeManager hedgeManager = new DeltaHedgeManager();
    private final InventoryRecyclePlanner recyclePlanner = new InventoryRecyclePlanner();

    @Test
    void stressPricePathsMoveWindowBothWaysWithoutStoppedState() {
        List<List<BigDecimal>> paths = List.of(
                prices("101.50", "105.00", "120.00"),
                prices("98.50", "95.00", "90.00", "80.00"),
                prices("96.00", "89.00", "106.00", "112.00"),
                prices("70.00", "130.00", "85.00"));

        int scenario = 0;
        for (List<BigDecimal> path : paths) {
            scenario++;
            LocalDateTime base = LocalDateTime.of(2026, 9, 1, 10, 0).plusHours(scenario);
            HtxGridWindowEntity window = gridEngine.initial(
                    SYMBOL, new BigDecimal("100.00"), STEP_PCT, 5, base);
            long lastEpoch = window.getEpoch();
            boolean movedUp = false;
            boolean movedDown = false;

            for (int i = 0; i < path.size(); i++) {
                MovingGridEngine.Decision decision = gridEngine.observe(window, path.get(i),
                        HYSTERESIS_PCT, 1, 0, 80, base.plusSeconds(i + 1L));
                assertOperational(window.getState());
                assertFalse("STOPPED".equals(window.getState()));
                assertTrue(window.getEpoch() >= lastEpoch);
                if (decision.migrated()) {
                    movedUp |= decision.signedSteps() > 0;
                    movedDown |= decision.signedSteps() < 0;
                    assertEquals(lastEpoch + 1, window.getEpoch());
                }
                lastEpoch = window.getEpoch();
            }

            assertTrue(movedUp || movedDown);
        }
    }

    @Test
    void downtrendAndVReversalKeepAggregateShortWithinCapWithUniqueIntents() {
        List<BigDecimal> inventoryReplay = amounts(
                "0.004", "0.018", "0.060", "0.040", "0.012", "0.000");
        long currentShortContracts = 0L;
        Set<String> clientOrderIds = new HashSet<>();

        for (int i = 0; i < inventoryReplay.size(); i++) {
            DeltaHedgeManager.HedgeDecision decision = hedgeManager.plan(hedgeInput(
                    inventoryReplay.get(i), currentShortContracts, true, true, true, i + 1L));

            assertBaseAtOrBelow(decision.targetShortBase(), MAX_SHORT_BASE);
            HtxSwapOrderRequest order = decision.orderRequest();
            if (order != null) {
                assertTrue(clientOrderIds.add(order.getClientOrderId()));
                long volume = Long.parseLong(order.getVolume());
                if (DeltaHedgeManager.Action.OPEN_SHORT.equals(decision.action())) {
                    assertEquals("sell", order.getSide());
                    assertEquals("short", order.getPositionSide());
                    assertFalse(order.getReduceOnly());
                    currentShortContracts += volume;
                } else {
                    assertEquals(DeltaHedgeManager.Action.CLOSE_SHORT, decision.action());
                    assertEquals("buy", order.getSide());
                    assertEquals("short", order.getPositionSide());
                    assertTrue(order.getReduceOnly());
                    currentShortContracts -= volume;
                }
            }

            BigDecimal currentShortBase = CONTRACT_SIZE.multiply(BigDecimal.valueOf(currentShortContracts));
            assertBaseAtOrBelow(currentShortBase, MAX_SHORT_BASE);
            assertEquals(0, decision.targetShortBase().compareTo(currentShortBase));
        }
    }

    @Test
    void riskGateDeteriorationFailsClosedWithoutChangingExistingShort() {
        DeltaHedgeManager.HedgeDecision noPosition = hedgeManager.plan(hedgeInput(
                new BigDecimal("0.050"), 0L, false, true, true, 11L));

        assertEquals(DeltaHedgeManager.Action.NONE, noPosition.action());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, noPosition.state());
        assertNull(noPosition.orderRequest());

        DeltaHedgeManager.HedgeDecision existingShort = hedgeManager.plan(hedgeInput(
                new BigDecimal("0.050"), 12L, true, false, true, 12L));

        assertEquals(DeltaHedgeManager.Action.NONE, existingShort.action());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, existingShort.state());
        assertEquals(0, new BigDecimal("0.012").compareTo(existingShort.targetShortBase()));
        assertNull(existingShort.orderRequest());
    }

    @Test
    void recycleReplayNeverCreatesNakedShortOrOverClosesSoldSpot() {
        BigDecimal availableShort = new BigDecimal("0.020");
        BigDecimal totalSold = BigDecimal.ZERO;
        BigDecimal totalClosed = BigDecimal.ZERO;

        List<BigDecimal> spotFills = amounts("0.0034", "0.0004", "0.0078", "0.0150");
        for (int i = 0; i < spotFills.size(); i++) {
            BigDecimal sold = spotFills.get(i);
            InventoryRecyclePlanner.RecycleDecision decision = recyclePlanner.afterSpotSellFill(
                    new InventoryRecyclePlanner.RecycleInput(SYMBOL, CONTRACT_CODE, sold,
                            availableShort, CONTRACT_SIZE, "CS-MM-RECYCLE-XAUTUSDT", i + 1L));

            totalSold = totalSold.add(sold);
            BigDecimal closedBase = CONTRACT_SIZE.multiply(BigDecimal.valueOf(decision.closeContracts()));
            assertBaseAtOrBelow(closedBase, sold);
            assertBaseAtOrBelow(closedBase, availableShort);

            if (decision.closeShortRequest() != null) {
                assertEquals("buy", decision.closeShortRequest().getSide());
                assertEquals("short", decision.closeShortRequest().getPositionSide());
                assertTrue(decision.closeShortRequest().getReduceOnly());
            }

            availableShort = availableShort.subtract(closedBase);
            totalClosed = totalClosed.add(closedBase);
            assertTrue(availableShort.signum() >= 0);
        }

        assertBaseAtOrBelow(totalClosed, totalSold);
        assertTrue(totalSold.subtract(totalClosed).compareTo(CONTRACT_SIZE) >= 0);
    }

    private static DeltaHedgeManager.HedgeInput hedgeInput(BigDecimal inventory,
                                                          long currentShortContracts,
                                                          boolean fundingAllowed,
                                                          boolean basisAllowed,
                                                          boolean marginSafe,
                                                          long epoch) {
        return new DeltaHedgeManager.HedgeInput(true, SYMBOL, CONTRACT_CODE, inventory,
                currentShortContracts, CONTRACT_SIZE, HEDGE_RATIO, MAX_SHORT_BASE,
                fundingAllowed, basisAllowed, marginSafe, "CS-MM-HEDGE-XAUTUSDT", epoch);
    }

    private static List<BigDecimal> prices(String... values) {
        return amounts(values);
    }

    private static List<BigDecimal> amounts(String... values) {
        return java.util.Arrays.stream(values)
                .map(BigDecimal::new)
                .toList();
    }

    private static void assertOperational(String state) {
        assertTrue(MovingGridEngine.RUNNING.equals(state)
                || MovingGridEngine.MIGRATING_UP.equals(state)
                || MovingGridEngine.MIGRATING_DOWN.equals(state)
                || MarketMakerRiskCoordinator.PAUSED_RISK.equals(state)
                || MarketMakerRiskCoordinator.RECOVERING.equals(state)
                || MarketMakerRiskCoordinator.HEDGE_DEGRADED.equals(state));
    }

    private static void assertBaseAtOrBelow(BigDecimal actual, BigDecimal limit) {
        assertTrue(actual.compareTo(limit) <= 0,
                () -> "actual=" + actual.toPlainString() + " limit=" + limit.toPlainString());
    }
}
