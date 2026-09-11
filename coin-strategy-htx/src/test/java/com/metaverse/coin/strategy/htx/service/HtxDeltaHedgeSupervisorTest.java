package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxHedgeRecycleRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity;
import com.metaverse.coin.strategy.htx.strategy.DeltaHedgeManager;
import com.metaverse.coin.strategy.htx.strategy.HtxDeltaHedgeSnapshotProvider;
import com.metaverse.coin.strategy.htx.strategy.HtxGridWindowStore;
import com.metaverse.coin.strategy.htx.strategy.MarketMakerRiskCoordinator;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxDeltaHedgeSupervisorTest {

    private HtxDeltaHedgeIntentExecutor executor;
    private HtxContractProperties props;
    private HtxMarketMakerProperties marketMakerProps;
    private HtxDeltaHedgeSnapshotProvider snapshotProvider;
    private HtxGridWindowStore gridWindowStore;
    private HtxHedgeRecycleRecorder recorder;
    private HtxDeltaHedgeSupervisor supervisor;

    @BeforeEach
    void setUp() {
        executor = mock(HtxDeltaHedgeIntentExecutor.class);
        props = new HtxContractProperties();
        props.setHedgeSupervisorEnabled(true);
        marketMakerProps = new HtxMarketMakerProperties();
        snapshotProvider = mock(HtxDeltaHedgeSnapshotProvider.class);
        gridWindowStore = mock(HtxGridWindowStore.class);
        recorder = mock(HtxHedgeRecycleRecorder.class);
        supervisor = new HtxDeltaHedgeSupervisor(
                executor, props, marketMakerProps, snapshotProvider, gridWindowStore, recorder);
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
        assertEquals(0, result.shadowReport().appendedHedgeIntents());
        verify(executor).reconcileSentHedgeIntents(20);
        verify(executor).reconcileSentRecycleIntents(20);
        verify(executor, never()).executePendingHedgeIntents(20);
        verify(executor, never()).executePendingRecycleIntents(20);
        verify(snapshotProvider, never()).snapshot(any());
    }

    @Test
    void shadowModePlansOpenShortIntentFromMovingGridInventoryWithoutSubmitting() {
        enableGridPlanner();
        when(gridWindowStore.find("xautusdt")).thenReturn(window(46L));
        when(snapshotProvider.snapshot(any())).thenReturn(snapshot(true, input(
                new BigDecimal("0.020"), 0L, true, true, true), null));
        when(recorder.recordHedgeIntent(eq("xautusdt"), eq(46L), any()))
                .thenReturn(new HtxHedgeRecycleRecorder.RecordResult(true, false, null, 100L));

        HtxDeltaHedgeSupervisor.RunResult result = supervisor.runOnce();

        assertEquals(HtxDeltaHedgeSupervisor.RunCode.SHADOW_ONLY, result.code());
        assertEquals(1, result.shadowReport().evaluatedHedgeSymbols());
        assertEquals(1, result.shadowReport().appendedHedgeIntents());
        assertEquals(0, result.shadowReport().duplicatedHedgeIntents());
        assertEquals(0, result.shadowReport().degradedHedgeSnapshots());
        ArgumentCaptor<HtxDeltaHedgeSnapshotProvider.HedgeSourceRequest> requestCaptor =
                ArgumentCaptor.forClass(HtxDeltaHedgeSnapshotProvider.HedgeSourceRequest.class);
        verify(snapshotProvider).snapshot(requestCaptor.capture());
        assertEquals("xautusdt", requestCaptor.getValue().symbol());
        assertEquals("XAUT-USDT", requestCaptor.getValue().contractCode());
        assertEquals(46L, requestCaptor.getValue().epoch());
        assertEquals("CS-MM-HEDGE-XAUTUSDT", requestCaptor.getValue().clientOrderIdPrefix());
        ArgumentCaptor<DeltaHedgeManager.HedgeDecision> decisionCaptor =
                ArgumentCaptor.forClass(DeltaHedgeManager.HedgeDecision.class);
        verify(recorder).recordHedgeIntent(eq("xautusdt"), eq(46L), decisionCaptor.capture());
        DeltaHedgeManager.HedgeDecision decision = decisionCaptor.getValue();
        assertEquals(DeltaHedgeManager.Action.OPEN_SHORT, decision.action());
        assertEquals("10", decision.orderRequest().getVolume());
        assertTrue(decision.orderRequest().getClientOrderId().matches("\\d{18}"));
        assertFalse(decision.orderRequest().getReduceOnly());
        verify(executor, never()).executePendingHedgeIntents(20);
    }

    @Test
    void duplicatePlannedIntentIsReportedButNotSubmittedAgain() {
        enableGridPlanner();
        when(gridWindowStore.find("xautusdt")).thenReturn(window(46L));
        when(snapshotProvider.snapshot(any())).thenReturn(snapshot(true, input(
                new BigDecimal("0.020"), 0L, true, true, true), null));
        when(recorder.recordHedgeIntent(eq("xautusdt"), eq(46L), any()))
                .thenReturn(new HtxHedgeRecycleRecorder.RecordResult(false, true, null, null));

        HtxDeltaHedgeSupervisor.RunResult result = supervisor.runOnce();

        assertEquals(0, result.shadowReport().appendedHedgeIntents());
        assertEquals(1, result.shadowReport().duplicatedHedgeIntents());
        assertEquals(0, result.shadowReport().skippedHedgePlans());
        verify(executor, never()).executePendingHedgeIntents(20);
    }

    @Test
    void degradedSnapshotDoesNotRecordShortReduction() {
        enableGridPlanner();
        when(gridWindowStore.find("xautusdt")).thenReturn(window(46L));
        when(snapshotProvider.snapshot(any())).thenReturn(snapshot(false, input(
                BigDecimal.ZERO, 20L, false, false, false), "basis gate blocked"));
        when(recorder.recordHedgeIntent(eq("xautusdt"), eq(46L), any()))
                .thenReturn(new HtxHedgeRecycleRecorder.RecordResult(false, false, "no hedge order intent", null));

        HtxDeltaHedgeSupervisor.RunResult result = supervisor.runOnce();

        assertEquals(0, result.shadowReport().appendedHedgeIntents());
        assertEquals(1, result.shadowReport().skippedHedgePlans());
        assertEquals(1, result.shadowReport().degradedHedgeSnapshots());
        ArgumentCaptor<DeltaHedgeManager.HedgeDecision> decisionCaptor =
                ArgumentCaptor.forClass(DeltaHedgeManager.HedgeDecision.class);
        verify(recorder).recordHedgeIntent(eq("xautusdt"), eq(46L), decisionCaptor.capture());
        DeltaHedgeManager.HedgeDecision decision = decisionCaptor.getValue();
        assertEquals(DeltaHedgeManager.Action.NONE, decision.action());
        assertEquals(MarketMakerRiskCoordinator.HEDGE_DEGRADED, decision.state());
        verify(executor, never()).executePendingHedgeIntents(20);
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

    private void enableGridPlanner() {
        marketMakerProps.setEnabled(true);
        marketMakerProps.setGridModeEnabled(true);
        marketMakerProps.setSymbols(List.of("xautusdt"));
    }

    private static HtxGridWindowEntity window(long epoch) {
        HtxGridWindowEntity window = new HtxGridWindowEntity();
        window.setSymbol("xautusdt");
        window.setEpoch(epoch);
        return window;
    }

    private static HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot(
            boolean available, DeltaHedgeManager.HedgeInput input, String reason) {
        return new HtxDeltaHedgeSnapshotProvider.HedgeSnapshot(available, input, reason);
    }

    private static DeltaHedgeManager.HedgeInput input(BigDecimal inventory, long currentShortContracts,
                                                      boolean fundingAllowed, boolean basisAllowed,
                                                      boolean marginSafe) {
        return new DeltaHedgeManager.HedgeInput(true, "xautusdt", "XAUT-USDT", inventory,
                currentShortContracts, new BigDecimal("0.001"), new BigDecimal("0.5"),
                new BigDecimal("0.05"), fundingAllowed, basisAllowed, marginSafe,
                "CS-MM-HEDGE-XAUTUSDT", 46L);
    }
}
