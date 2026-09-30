package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendFollowingSchedulerTest {

    @Test
    void defersEntryWindowDecisionUntilCandidateExecution() {
        HtxTrendFollowingService service = mock(HtxTrendFollowingService.class);
        HtxTrendProperties properties = new HtxTrendProperties();
        HtxTrendFollowingScheduler scheduler = new HtxTrendFollowingScheduler(service, properties);
        HtxTrendFollowingService.RunResult expected = HtxTrendFollowingService.RunResult.of(
                HtxTrendFollowingService.RunCode.SKIPPED, null, "candidate scan complete");
        when(service.runOnce(any(BooleanSupplier.class))).thenReturn(expected);

        HtxTrendFollowingService.RunResult result = scheduler.scheduledAt();

        assertEquals(expected, result);
        verify(service).runOnce(any(BooleanSupplier.class));
    }

    @Test
    void delegatesEachScheduledRunToDynamicExecutionWindow() {
        HtxTrendFollowingService service = mock(HtxTrendFollowingService.class);
        HtxTrendProperties properties = new HtxTrendProperties();
        HtxTrendFollowingScheduler scheduler = new HtxTrendFollowingScheduler(service, properties);
        HtxTrendFollowingService.RunResult expected = HtxTrendFollowingService.RunResult.of(
                HtxTrendFollowingService.RunCode.NO_SIGNAL, null, "checked");
        when(service.runOnce(any(BooleanSupplier.class))).thenReturn(expected);

        HtxTrendFollowingService.RunResult result = scheduler.scheduledAt();

        assertEquals(expected, result);
        verify(service).runOnce(any(BooleanSupplier.class));
    }

    @Test
    void delegatesExistingPositionManagementToTrendService() {
        HtxTrendFollowingService service = mock(HtxTrendFollowingService.class);
        HtxTrendProperties properties = new HtxTrendProperties();
        HtxTrendFollowingScheduler scheduler = new HtxTrendFollowingScheduler(service, properties);
        HtxTrendFollowingService.RunResult expected = HtxTrendFollowingService.RunResult.of(
                HtxTrendFollowingService.RunCode.ACTIVE, 7L, "holding");
        when(service.runOnce(any(BooleanSupplier.class))).thenReturn(expected);

        HtxTrendFollowingService.RunResult result = scheduler.scheduledAt();

        assertEquals(expected, result);
        verify(service).runOnce(any(BooleanSupplier.class));
    }

    @Test
    void exitSupervisorDelegatesOnlyExistingPositionManagement() {
        HtxTrendFollowingService service = mock(HtxTrendFollowingService.class);
        HtxTrendProperties properties = new HtxTrendProperties();
        HtxTrendFollowingScheduler scheduler = new HtxTrendFollowingScheduler(service, properties);
        HtxTrendFollowingService.RunResult expected = HtxTrendFollowingService.RunResult.of(
                HtxTrendFollowingService.RunCode.ACTIVE, 7L, "holding");
        when(service.manageOpenPositionsOnly()).thenReturn(expected);

        HtxTrendFollowingService.RunResult result = scheduler.exitSupervisorAt();

        assertEquals(expected, result);
        verify(service).manageOpenPositionsOnly();
    }
}
