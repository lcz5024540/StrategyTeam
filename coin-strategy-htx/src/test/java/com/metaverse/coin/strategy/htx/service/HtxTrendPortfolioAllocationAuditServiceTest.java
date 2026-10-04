package com.metaverse.coin.strategy.htx.service;

import com.alibaba.fastjson2.JSON;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCombinationRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendPortfolioAllocationAuditServiceTest {
    @Test
    void recordsBothAcceptedTargetAndRejectedCandidateWithoutCreatingOrders() {
        HtxTrendCombinationRecorder recorder = mock(HtxTrendCombinationRecorder.class);
        when(recorder.recordSnapshot(any())).thenReturn(1L);
        HtxTrendPortfolioAllocationAuditService service = new HtxTrendPortfolioAllocationAuditService(recorder);
        var allocation = service.allocateAndRecord("scan:1", List.of(audit("run:btc", "BTC-USDT", "BTC", "90"),
                        audit("run:eth", "ETH-USDT", "BTC", "80")),
                List.of(new HtxTrendPortfolioAllocator.AccountCapacity(1L, "primary", true, new BigDecimal("100"),
                        new BigDecimal("150"), new BigDecimal("0.0004"))),
                new HtxTrendPortfolioAllocator.RiskBudget(3, 1, new BigDecimal("300"), new BigDecimal("300")));

        assertEquals(1, allocation.targets().size());
        var snapshot = org.mockito.ArgumentCaptor.forClass(HtxTrendFactorSnapshotEntity.class);
        verify(recorder, org.mockito.Mockito.times(2)).recordSnapshot(snapshot.capture());
        assertEquals("trend-target-exposure-v1", JSON.parseObject(snapshot.getAllValues().get(0).getFactorsJson()).getString("schema"));
        assertEquals("TARGET_EXPOSURE_ONLY", JSON.parseObject(snapshot.getAllValues().get(1).getFactorsJson()).getString("executionBoundary"));
    }

    private static HtxTrendPortfolioAllocationAuditService.AuditedCandidate audit(String runId, String contract,
                                                                                    String cluster, String score) {
        return new HtxTrendPortfolioAllocationAuditService.AuditedCandidate("15min", 100L, "BROAD_UP",
                new HtxTrendPortfolioAllocator.Candidate(runId, contract, "LONG", new BigDecimal("100"),
                        new BigDecimal("10"), new BigDecimal(score), cluster));
    }
}
