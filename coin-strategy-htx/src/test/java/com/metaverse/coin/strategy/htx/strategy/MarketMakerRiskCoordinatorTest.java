package com.metaverse.coin.strategy.htx.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketMakerRiskCoordinatorTest {

    private final MarketMakerRiskCoordinator coordinator = new MarketMakerRiskCoordinator();

    @Test void unavailableBalanceEntersRecoveringAndBlocksNewRisk() {
        MarketMakerRiskCoordinator.RiskDecision decision = coordinator.evaluate(
                MarketMakerRiskCoordinator.SpotBalanceSnapshot.unavailable(),
                new BigDecimal("20"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertFalse(decision.allowNewRisk());
        assertEquals(MarketMakerRiskCoordinator.RECOVERING, decision.state());
    }

    @Test void quoteReserveBreachPausesRisk() {
        MarketMakerRiskCoordinator.RiskDecision decision = coordinator.evaluate(
                new MarketMakerRiskCoordinator.SpotBalanceSnapshot(true, new BigDecimal("25"), BigDecimal.ZERO),
                new BigDecimal("20"), new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertFalse(decision.allowNewRisk());
        assertEquals(MarketMakerRiskCoordinator.PAUSED_RISK, decision.state());
    }

    @Test void projectedBaseInventoryCapBreachPausesRisk() {
        MarketMakerRiskCoordinator.RiskDecision decision = coordinator.evaluate(
                new MarketMakerRiskCoordinator.SpotBalanceSnapshot(true, new BigDecimal("100"), new BigDecimal("0.09")),
                new BigDecimal("20"), BigDecimal.ZERO, new BigDecimal("0.09"),
                new BigDecimal("0.02"), new BigDecimal("0.10"));

        assertFalse(decision.allowNewRisk());
        assertEquals(MarketMakerRiskCoordinator.PAUSED_RISK, decision.state());
    }

    @Test void healthyBudgetKeepsRunning() {
        MarketMakerRiskCoordinator.RiskDecision decision = coordinator.evaluate(
                new MarketMakerRiskCoordinator.SpotBalanceSnapshot(true, new BigDecimal("100"), new BigDecimal("0.03")),
                new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("0.03"),
                new BigDecimal("0.02"), new BigDecimal("0.10"));

        assertTrue(decision.allowNewRisk());
        assertEquals(MovingGridEngine.RUNNING, decision.state());
    }
}
