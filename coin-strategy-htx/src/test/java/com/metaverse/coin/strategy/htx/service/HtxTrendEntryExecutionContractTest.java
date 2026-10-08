package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendEntryExecutionContractTest {
    @Test
    void finalEntryMathHasStableJavaParityFixtures() {
        assertTrue(HtxTrendEntryExecutionContract.fundingCostGate(new BigDecimal("0.0007"), TrendSide.LONG,
                new BigDecimal("0.0006")).contains("funding cost blocked"));
        assertNull(HtxTrendEntryExecutionContract.freshnessGate(9_000L, "15min", 10_000L, 600L));
        assertTrue(HtxTrendEntryExecutionContract.freshnessGate(9_000L, "15min", 10_501L, 600L).contains("too stale"));
        assertNull(HtxTrendEntryExecutionContract.expectedMoveGate(new BigDecimal("2"), new BigDecimal("100"),
                new BigDecimal("0.001"), new BigDecimal("0.0004"), true, BigDecimal.ONE, new BigDecimal("0.001")));
        assertTrue(HtxTrendEntryExecutionContract.expectedMoveGate(new BigDecimal("0.02"), new BigDecimal("100"),
                new BigDecimal("0.001"), new BigDecimal("0.0004"), true, BigDecimal.ONE, new BigDecimal("0.001"))
                .contains("expected move too small"));
        assertTrue(HtxTrendEntryExecutionContract.chaseGate(TrendSide.LONG, new BigDecimal("100"),
                new BigDecimal("100.5"), new BigDecimal("2"), new BigDecimal("0.20"), BigDecimal.ZERO)
                .contains("chased signal too far"));
        assertEquals(100L, HtxTrendEntryExecutionContract.policyVolume(new BigDecimal("100"),
                new BigDecimal("0.01"), new BigDecimal("100"), new BigDecimal("20")));
    }
}
