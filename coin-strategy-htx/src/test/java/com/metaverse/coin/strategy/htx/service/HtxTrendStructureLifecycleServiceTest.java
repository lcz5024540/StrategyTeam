package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStructureLifecycleEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendStructureLifecycleMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HtxTrendStructureLifecycleServiceTest {

    @Test
    void zecFirstFibStructureCanClaimButSecondEntryIsBlockedAfterTheFirstTradeConsumesIt() {
        HtxTrendStructureLifecycleMapper mapper = mock(HtxTrendStructureLifecycleMapper.class);
        HtxTrendStructureLifecycleService service = new HtxTrendStructureLifecycleService(mapper);
        HtxTrendStructureLifecycleService.Claim first = zecClaim("trend-entry:ZEC-USDT:15min:1791370800");
        HtxTrendStructureLifecycleService.Claim second = zecClaim("trend-entry:ZEC-USDT:15min:1791375300");
        HtxTrendStructureLifecycleEntity consumed = new HtxTrendStructureLifecycleEntity();
        consumed.setState("EXHAUSTED");
        consumed.setEntryPositionId(295L);

        when(mapper.insertDiscovered(any())).thenReturn(1, 0);
        when(mapper.findByStructureKey(eq(first.structureKey()))).thenReturn(consumed);

        HtxTrendStructureLifecycleService.Decision firstDecision = service.claim(first);
        HtxTrendStructureLifecycleService.Decision secondDecision = service.claim(second);

        assertTrue(firstDecision.allowed());
        assertTrue(firstDecision.claimed());
        assertFalse(secondDecision.allowed());
        assertTrue(secondDecision.reason().contains(HtxTrendStructureLifecycleService.STRUCTURE_ALREADY_CONSUMED));
        assertTrue(secondDecision.reason().contains("295"));
    }

    private static HtxTrendStructureLifecycleService.Claim zecClaim(String runId) {
        return new HtxTrendStructureLifecycleService.Claim(
                "ZEC-USDT:SHORT:FIB_PULLBACK_CONTINUATION:BREAKOUT_RETEST:1791369000",
                "ZEC-USDT", "15min", "SHORT", "FIB_PULLBACK_CONTINUATION", 1791369000L, runId);
    }
}
