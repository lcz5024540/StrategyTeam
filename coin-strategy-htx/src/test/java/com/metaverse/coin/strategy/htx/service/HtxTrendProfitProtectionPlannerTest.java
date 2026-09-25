package com.metaverse.coin.strategy.htx.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendProfitProtectionPlannerTest {

    private final HtxTrendProfitProtectionPlanner planner = new HtxTrendProfitProtectionPlanner();

    @Test
    void ltc161LocksMeaningfulShareOfPeakProfitInsteadOfOnlyCostFloor() {
        HtxTrendProfitProtectionPlanner.Plan plan = planner.evaluate(new HtxTrendProfitProtectionPlanner.Input(
                "LONG", 12L, new BigDecimal("0.10"), new BigDecimal("72.92"),
                new BigDecimal("74.74"), new BigDecimal("72.47"), new BigDecimal("74.74"),
                new BigDecimal("1.33179238095238"), new BigDecimal("0.0350016"), new BigDecimal("0.0358752"),
                null, HtxTrendProfitProtectionPlanner.Policy.defaults()));

        assertTrue(plan.armed());
        assertEquals(0, plan.mfeNetPnl().compareTo(new BigDecimal("2.1131232")));
        assertEquals(0, plan.protectionPrice().compareTo(new BigDecimal("73.83")));
        assertTrue(plan.projectedLockedNetPnl().compareTo(new BigDecimal("1.00")) > 0);
        assertTrue(plan.mfeCaptureRatio().compareTo(new BigDecimal("0.48")) > 0);
        assertEquals("MFE_LOCK", plan.mode());
    }

    @Test
    void protectionNeverLoosensAfterPeakPriceFalls() {
        HtxTrendProfitProtectionPlanner.Plan plan = planner.evaluate(new HtxTrendProfitProtectionPlanner.Input(
                "LONG", 12L, new BigDecimal("0.10"), new BigDecimal("72.92"),
                new BigDecimal("74.74"), new BigDecimal("72.47"), new BigDecimal("73.14"),
                new BigDecimal("1.33179238095238"), new BigDecimal("0.0350016"), new BigDecimal("0.0351072"),
                new BigDecimal("73.83"), HtxTrendProfitProtectionPlanner.Policy.defaults()));

        assertEquals(0, plan.protectionPrice().compareTo(new BigDecimal("73.83")));
        assertTrue(plan.projectedLockedNetPnl().compareTo(BigDecimal.ONE) > 0);
    }
}
