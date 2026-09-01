package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxDeltaHedgeSupervisorTest {

    private HtxDeltaHedgeIntentExecutor executor;
    private HtxContractProperties props;
    private HtxDeltaHedgeSupervisor supervisor;

    @BeforeEach
    void setUp() {
        executor = mock(HtxDeltaHedgeIntentExecutor.class);
        props = new HtxContractProperties();
        props.setHedgeSupervisorEnabled(true);
        supervisor = new HtxDeltaHedgeSupervisor(executor, props);
        when(executor.recoveryBacklog()).thenReturn(new HtxDeltaHedgeIntentExecutor
                .RecoveryBacklog(0, 0, 0, 0));
        when(executor.pendingBacklog()).thenReturn(new HtxDeltaHedgeIntentExecutor
                .PendingBacklog(2, 1));
        when(executor.reconcileSentHedgeIntents(20)).thenReturn(List.of());
        when(executor.reconcileSentRecycleIntents(20)).thenReturn(List.of());
    }

    @Test
    void shadowModeReconcilesOnlyAndNeverSubmitsPendingIntents() {
        HtxDeltaHedgeSupervisor.RunResult result = supervisor.runOnce();

        assertEquals(HtxDeltaHedgeSupervisor.RunCode.SHADOW_ONLY, result.code());
        assertEquals(0, result.shadowReport().pendingSubmittedRows());
        assertEquals(3, result.shadowReport().pendingSkippedRows());
        assertEquals(HtxDeltaHedgeSupervisor.AlertLevel.NONE, result.shadowReport().alertLevel());
        assertEquals(2, result.shadowReport().pendingHedgeRows());
        assertEquals(1, result.shadowReport().pendingRecycleRows());
        verify(executor).reconcileSentHedgeIntents(20);
        verify(executor).reconcileSentRecycleIntents(20);
        verify(executor, never()).executePendingHedgeIntents(20);
        verify(executor, never()).executePendingRecycleIntents(20);
    }

    @Test
    void degradedBacklogBlocksAutoSendEvenWhenAllLiveSwitchesAreOpen() {
        props.setHedgeSupervisorShadowOnly(false);
        props.setHedgeAutoSendEnabled(true);
        props.setDryRun(false);
        props.setLiveEnabled(true);
        when(executor.recoveryBacklog()).thenReturn(new HtxDeltaHedgeIntentExecutor
                .RecoveryBacklog(1, 0, 0, 0));

        HtxDeltaHedgeSupervisor.RunResult result = supervisor.runOnce();

        assertEquals(HtxDeltaHedgeSupervisor.RunCode.MANUAL_INTERVENTION_REQUIRED, result.code());
        assertEquals(1, result.blockingRows());
        assertEquals(3, result.shadowReport().pendingBlockedRows());
        assertEquals(0, result.shadowReport().pendingSkippedRows());
        assertEquals(HtxDeltaHedgeSupervisor.AlertLevel.MANUAL_INTERVENTION_REQUIRED,
                result.shadowReport().alertLevel());
        assertTrue(result.shadowReport().requiresManualIntervention());
        verify(executor, never()).executePendingHedgeIntents(20);
        verify(executor, never()).executePendingRecycleIntents(20);
    }

    @Test
    void liveGateClosedLeavesPendingIntentsUntouched() {
        props.setHedgeSupervisorShadowOnly(false);
        props.setHedgeAutoSendEnabled(true);

        HtxDeltaHedgeSupervisor.RunResult result = supervisor.runOnce();

        assertEquals(HtxDeltaHedgeSupervisor.RunCode.LIVE_GATE_CLOSED, result.code());
        assertEquals(0, result.shadowReport().pendingSubmittedRows());
        assertEquals(3, result.shadowReport().pendingSkippedRows());
        verify(executor, never()).executePendingHedgeIntents(20);
        verify(executor, never()).executePendingRecycleIntents(20);
    }

    @Test
    void newlyDegradedReconcileResultBlocksAutoSend() {
        props.setHedgeSupervisorShadowOnly(false);
        props.setHedgeAutoSendEnabled(true);
        props.setDryRun(false);
        props.setLiveEnabled(true);
        when(executor.reconcileSentHedgeIntents(20)).thenReturn(List.of(
                new HtxDeltaHedgeIntentExecutor.ExecutionResult(
                        HtxDeltaHedgeIntentExecutor.ExecutionKind.HEDGE,
                        HtxDeltaHedgeIntentExecutor.ExecutionCode.HEDGE_DEGRADED, 2L,
                        "HEDGE_DEGRADED", "partial fill")));

        HtxDeltaHedgeSupervisor.RunResult result = supervisor.runOnce();

        assertEquals(HtxDeltaHedgeSupervisor.RunCode.MANUAL_INTERVENTION_REQUIRED, result.code());
        assertEquals(1, result.blockingRows());
        assertEquals(1, result.shadowReport().newBlockingResults());
        assertTrue(result.shadowReport().alertReason().contains("HEDGE_DEGRADED/UNKNOWN"));
        verify(executor, never()).executePendingHedgeIntents(20);
        verify(executor, never()).executePendingRecycleIntents(20);
    }

    @Test
    void explicitAutoSendRequiresShadowOffAutoGateAndLiveGates() {
        props.setHedgeSupervisorShadowOnly(false);
        props.setHedgeAutoSendEnabled(true);
        props.setDryRun(false);
        props.setLiveEnabled(true);
        when(executor.executePendingHedgeIntents(20)).thenReturn(List.of(
                new HtxDeltaHedgeIntentExecutor.ExecutionResult(
                        HtxDeltaHedgeIntentExecutor.ExecutionKind.HEDGE,
                        HtxDeltaHedgeIntentExecutor.ExecutionCode.SENT, 1L, "SENT", "accepted")));
        when(executor.executePendingRecycleIntents(20)).thenReturn(List.of());

        HtxDeltaHedgeSupervisor.RunResult result = supervisor.runOnce();

        assertEquals(HtxDeltaHedgeSupervisor.RunCode.EXECUTED_PENDING, result.code());
        assertEquals(1, result.submittedRows());
        assertEquals(1, result.shadowReport().pendingSubmittedRows());
        assertEquals(0, result.shadowReport().pendingSkippedRows());
        verify(executor).executePendingHedgeIntents(20);
        verify(executor).executePendingRecycleIntents(20);
    }

    @Test
    void unknownSubmitResultRequiresManualInterventionImmediately() {
        props.setHedgeSupervisorShadowOnly(false);
        props.setHedgeAutoSendEnabled(true);
        props.setDryRun(false);
        props.setLiveEnabled(true);
        when(executor.executePendingHedgeIntents(20)).thenReturn(List.of(
                new HtxDeltaHedgeIntentExecutor.ExecutionResult(
                        HtxDeltaHedgeIntentExecutor.ExecutionKind.HEDGE,
                        HtxDeltaHedgeIntentExecutor.ExecutionCode.UNKNOWN, 1L, "UNKNOWN",
                        "send exception")));
        when(executor.executePendingRecycleIntents(20)).thenReturn(List.of());

        HtxDeltaHedgeSupervisor.RunResult result = supervisor.runOnce();

        assertEquals(HtxDeltaHedgeSupervisor.RunCode.MANUAL_INTERVENTION_REQUIRED, result.code());
        assertEquals(1, result.blockingRows());
        assertEquals(0, result.submittedRows());
        assertEquals(HtxDeltaHedgeSupervisor.AlertLevel.MANUAL_INTERVENTION_REQUIRED,
                result.shadowReport().alertLevel());
    }
}
