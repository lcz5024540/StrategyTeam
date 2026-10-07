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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendStructureLifecycleServiceTest {

    @Test
    void zecSecondFibTradeUsesItsOwnCompletedCandleCycleRatherThanTheFirstTradesBreakout() {
        HtxTrendStructureLifecycleMapper mapper = mock(HtxTrendStructureLifecycleMapper.class);
        HtxTrendStructureLifecycleService service = new HtxTrendStructureLifecycleService(mapper);
        String firstRun = "trend-entry:ZEC-USDT:15min:1791370800";
        String secondRun = "trend-entry:ZEC-USDT:15min:1791375300";
        HtxTrendStructureLifecycleService.Claim first = zecClaim(firstRun);
        HtxTrendStructureLifecycleService.Claim second = zecClaim(secondRun);

        when(mapper.insertDiscovered(any())).thenReturn(1, 1);

        HtxTrendStructureLifecycleService.Decision firstDecision = service.claim(first);
        HtxTrendStructureLifecycleService.Decision secondDecision = service.claim(second);

        assertFalse(first.structureKey().equals(second.structureKey()));
        assertTrue(first.structureKey().contains(firstRun));
        assertTrue(second.structureKey().contains(secondRun));
        assertTrue(firstDecision.allowed());
        assertTrue(firstDecision.claimed());
        assertTrue(secondDecision.allowed());
        assertTrue(secondDecision.claimed());
        verify(mapper, never()).findByStructureKey(any());
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
    void terminalStateBlocksOnlyTheSameCompletedCandleCycle() {
        for (String state : new String[]{"EXHAUSTED", "INVALIDATED", "PROFIT_PROTECTED", "SUPERSEDED"}) {
            HtxTrendStructureLifecycleMapper mapper = mock(HtxTrendStructureLifecycleMapper.class);
            HtxTrendStructureLifecycleService service = new HtxTrendStructureLifecycleService(mapper);
            HtxTrendStructureLifecycleEntity terminal = new HtxTrendStructureLifecycleEntity();
            terminal.setState(state);
            terminal.setEntryPositionId(295L);
            when(mapper.findByStructureKey(eq("ZEC-" + state))).thenReturn(terminal);

            HtxTrendStructureLifecycleService.Decision availability = service.availability("ZEC-" + state);

            assertFalse(availability.allowed(), state + " must not re-enter from the same completed candle");
        }
    }

    private static HtxTrendStructureLifecycleService.Claim zecClaim(String runId) {
        return new HtxTrendStructureLifecycleService.Claim(
                HtxTrendStructureLifecycleService.entryCycleKey("ZEC-USDT", "SHORT",
                        "FIB_PULLBACK_CONTINUATION", runId),
                "ZEC-USDT", "15min", "SHORT", "FIB_PULLBACK_CONTINUATION", 1791369000L, runId);
    }
}
