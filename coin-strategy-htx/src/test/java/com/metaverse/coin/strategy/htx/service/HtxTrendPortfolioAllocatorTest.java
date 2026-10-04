package com.metaverse.coin.strategy.htx.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendPortfolioAllocatorTest {
    @Test
    void selectsCheapestEligibleAccountAndPreventsCorrelatedDuplicateRisk() {
        HtxTrendPortfolioAllocator allocator = new HtxTrendPortfolioAllocator();
        HtxTrendPortfolioAllocator.Allocation result = allocator.allocate(List.of(
                candidate("r1", "BTC-USDT", "LONG", "L1", "90"), candidate("r2", "ETH-USDT", "LONG", "L1", "80"),
                candidate("r3", "BTC-USDT", "LONG", "L2", "70")), List.of(
                account(1, "primary", "0.0005", "100"), account(2, "mirror", "0.0002", "100")),
                new HtxTrendPortfolioAllocator.RiskBudget(3, 1, new BigDecimal("300"), new BigDecimal("200")));
        assertEquals(1, result.targets().size());
        assertEquals(2L, result.targets().get(0).accountId());
        assertEquals("correlation_cluster_limit", result.decisions().get(1).rejection());
        assertEquals("duplicate_symbol_direction", result.decisions().get(2).rejection());
        assertEquals("TARGET_EXPOSURE_ONLY", result.targets().get(0).executionBoundary());
    }
    private static HtxTrendPortfolioAllocator.Candidate candidate(String run, String contract, String direction, String cluster, String score) {
        return new HtxTrendPortfolioAllocator.Candidate(run, contract, direction, new BigDecimal("100"), new BigDecimal("10"), new BigDecimal(score), cluster);
    }
    private static HtxTrendPortfolioAllocator.AccountCapacity account(long id, String label, String fee, String margin) {
        return new HtxTrendPortfolioAllocator.AccountCapacity(id, label, true, new BigDecimal(margin), new BigDecimal("200"), new BigDecimal(fee));
    }
}
