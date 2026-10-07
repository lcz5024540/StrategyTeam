package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStructureLifecycleEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendStructureLifecycleMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HtxTrendStructureLifecycleServiceTest {

    @Test
    void zecSecondFibCandidateCanReclaimTerminalStructureAndMustBeEvaluatedFromCurrentEvidence() {
        HtxTrendStructureLifecycleMapper mapper = mock(HtxTrendStructureLifecycleMapper.class);
        HtxTrendStructureLifecycleService service = new HtxTrendStructureLifecycleService(mapper);
        HtxTrendStructureLifecycleService.Claim first = zecClaim("trend-entry:ZEC-USDT:15min:1791370800");
        HtxTrendStructureLifecycleService.Claim second = zecClaim("trend-entry:ZEC-USDT:15min:1791375300");
        HtxTrendStructureLifecycleEntity consumed = new HtxTrendStructureLifecycleEntity();
        consumed.setState("EXHAUSTED");
        consumed.setEntryPositionId(295L);

        when(mapper.insertDiscovered(any())).thenReturn(1, 0);
        when(mapper.findByStructureKey(eq(first.structureKey()))).thenReturn(consumed);
        when(mapper.reclaimReusable(eq(first.structureKey()), eq(second.sourceRunId()))).thenReturn(1);

        HtxTrendStructureLifecycleService.Decision firstDecision = service.claim(first);
        HtxTrendStructureLifecycleService.Decision secondDecision = service.claim(second);

        assertTrue(firstDecision.allowed());
        assertTrue(firstDecision.claimed());
        assertTrue(secondDecision.allowed());
        assertTrue(secondDecision.claimed());
    }

    @Test
    void activeFibStructureRemainsBlockedUntilTheExistingPositionTerminates() {
        HtxTrendStructureLifecycleMapper mapper = mock(HtxTrendStructureLifecycleMapper.class);
        HtxTrendStructureLifecycleService service = new HtxTrendStructureLifecycleService(mapper);
        HtxTrendStructureLifecycleService.Claim claim = zecClaim("trend-entry:ZEC-USDT:15min:1791370800");
        HtxTrendStructureLifecycleEntity active = new HtxTrendStructureLifecycleEntity();
        active.setState("ACTIVE");
        active.setEntryPositionId(295L);
        when(mapper.findByStructureKey(eq(claim.structureKey()))).thenReturn(active);

        HtxTrendStructureLifecycleService.Decision availability = service.availability(claim.structureKey());
        HtxTrendStructureLifecycleService.Decision decision = service.claim(claim);

        assertFalse(availability.allowed());
        assertEquals(HtxTrendStructureLifecycleService.STRUCTURE_ALREADY_CONSUMED + " state=ACTIVE entryPositionId=295",
                availability.reason());
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("state=ACTIVE"));
    }

    @Test
    void allTerminalStatesAreAvailableForFreshTrendAssessment() {
        for (String state : new String[]{"ABANDONED", "EXHAUSTED", "INVALIDATED", "PROFIT_PROTECTED", "SUPERSEDED"}) {
            HtxTrendStructureLifecycleMapper mapper = mock(HtxTrendStructureLifecycleMapper.class);
            HtxTrendStructureLifecycleService service = new HtxTrendStructureLifecycleService(mapper);
            HtxTrendStructureLifecycleEntity terminal = new HtxTrendStructureLifecycleEntity();
            terminal.setState(state);
            terminal.setEntryPositionId(295L);
            when(mapper.findByStructureKey(eq("ZEC-" + state))).thenReturn(terminal);

            HtxTrendStructureLifecycleService.Decision availability = service.availability("ZEC-" + state);

            assertTrue(availability.allowed(), state + " should release the structure for fresh gates");
        }
    }

    private static HtxTrendStructureLifecycleService.Claim zecClaim(String runId) {
        return new HtxTrendStructureLifecycleService.Claim(
                "ZEC-USDT:SHORT:FIB_PULLBACK_CONTINUATION:BREAKOUT_RETEST:1791369000",
                "ZEC-USDT", "15min", "SHORT", "FIB_PULLBACK_CONTINUATION", 1791369000L, runId);
    }
}
