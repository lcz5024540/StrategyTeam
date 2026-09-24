package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendModelRouterTest {
    private final HtxTrendModelRouter router = new HtxTrendModelRouter();

    @Test
    void donchianIsPrimaryWhenMomentumAlsoConfirms() {
        HtxTrendModelRouter.RoutingDecision decision = router.route(signal(100L),
                coverage(true, false, false, true, null));

        assertTrue(decision.routable());
        assertEquals("DONCHIAN_BREAKOUT", decision.primaryModel());
        assertTrue(decision.supports().contains("MOMENTUM_CONFIRMS"));
        assertEquals("DONCHIAN_BREAKOUT:100", decision.structureId());
    }

    @Test
    void fibonacciIsPrimaryWhenEmaAndMomentumAlsoConfirm() {
        HtxTrendModelRouter.RoutingDecision decision = router.route(signal(101L),
                coverage(false, true, true, true, 88L));

        assertTrue(decision.routable());
        assertEquals("FIB_PULLBACK_CONTINUATION", decision.primaryModel());
        assertTrue(decision.supports().contains("EMA_VWAP_ALIGNED"));
        assertTrue(decision.supports().contains("MOMENTUM_CONFIRMS"));
        assertEquals("BREAKOUT_RETEST:88", decision.structureId());
    }

    @Test
    void emaAndMomentumRouteOnlyWhenNoEarlierTrendStageIsActive() {
        HtxTrendModelRouter.RoutingDecision decision = router.route(signal(102L),
                coverage(false, false, true, true, null));

        assertTrue(decision.routable());
        assertEquals("EMA_VWAP_MOMENTUM_CONTINUATION", decision.primaryModel());
        assertEquals("EMA_VWAP_MOMENTUM_CONTINUATION:102", decision.structureId());
    }

    @Test
    void inactiveModelsNeverInventARoute() {
        HtxTrendModelRouter.RoutingDecision decision = router.route(signal(103L),
                coverage(false, false, false, false, null));

        assertFalse(decision.routable());
        assertEquals("NO_ROUTE", decision.primaryModel());
        assertEquals(Direction.FLAT, decision.direction());
    }

    @Test
    void fibonacciStructureIdRemainsStableForTheSameSourceBreakout() {
        HtxTrendModelCoverageCalculator.Coverage coverage = coverage(false, true, true, true, 88L);

        HtxTrendModelRouter.RoutingDecision first = router.route(signal(104L), coverage);
        HtxTrendModelRouter.RoutingDecision second = router.route(signal(105L), coverage);

        assertEquals("BREAKOUT_RETEST:88", first.structureId());
        assertEquals(first.structureId(), second.structureId());
    }

    private static HtxTrendModelCoverageCalculator.Coverage coverage(boolean donchian, boolean fibonacci,
                                                                       boolean emaVwap, boolean momentum,
                                                                       Long fibonacciSourceBarTime) {
        return new HtxTrendModelCoverageCalculator.Coverage(true, Direction.LONG, donchian, fibonacci,
                emaVwap, momentum, false, true, "TEST", fibonacciSourceBarTime, new BigDecimal("100"));
    }

    private static TrendCandidateSignal signal(long barTime) {
        return new TrendCandidateSignal("15min", barTime, Direction.LONG, Regime.TRENDING_UP,
                new BigDecimal("80"), new BigDecimal("80"), BigDecimal.ZERO, new BigDecimal("80"),
                new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("98"), new BigDecimal("97"),
                new BigDecimal("101"), new BigDecimal("95"), new BigDecimal("2"), new BigDecimal("0.02"),
                new BigDecimal("25"), new BigDecimal("0.02"), new BigDecimal("1.2"), "test", "test");
    }
}
