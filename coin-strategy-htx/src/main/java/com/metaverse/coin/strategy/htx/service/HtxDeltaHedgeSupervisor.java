package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shadow-first supervisor for moving-grid hedge/recycle intents.
 *
 * <p>Its first job is reconciliation and surfacing manual-intervention cases. Pending sends are
 * deliberately gated behind a separate opt-in so enabling the supervisor cannot create real
 * orders by itself.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(HtxDeltaHedgeIntentExecutor.class)
@ConditionalOnExpression("${htx.contract.hedge-supervisor-enabled:false}")
public class HtxDeltaHedgeSupervisor {

    private final HtxDeltaHedgeIntentExecutor executor;
    private final HtxContractProperties props;

    @Scheduled(
            fixedDelayString = "${htx.contract.hedge-supervisor-interval-ms:60000}",
            initialDelayString = "${htx.contract.hedge-supervisor-initial-delay-ms:30000}")
    public void scheduled() {
        try {
            RunResult result = runOnce();
            log.info("[HTX-HEDGE-SUPERVISOR] result={}", result);
            if (AlertLevel.MANUAL_INTERVENTION_REQUIRED.equals(result.shadowReport().alertLevel())) {
                log.warn("[HTX-HEDGE-SUPERVISOR-ALERT] report={}", result.shadowReport());
            }
        } catch (RuntimeException ex) {
            log.error("[HTX-HEDGE-SUPERVISOR] failed; no auto-send will be attempted", ex);
        }
    }

    RunResult runOnce() {
        if (!props.isHedgeSupervisorEnabled()) {
            return result(RunCode.DISABLED, 0, 0, List.of(), List.of(),
                    new HtxDeltaHedgeIntentExecutor.RecoveryBacklog(0, 0, 0, 0),
                    new HtxDeltaHedgeIntentExecutor.PendingBacklog(0, 0), 0,
                    "hedge supervisor disabled");
        }
        int batchSize = Math.max(1, props.getHedgeSupervisorBatchSize());
        HtxDeltaHedgeIntentExecutor.RecoveryBacklog before = executor.recoveryBacklog();
        List<HtxDeltaHedgeIntentExecutor.ExecutionResult> hedgeReconciled =
                executor.reconcileSentHedgeIntents(batchSize);
        List<HtxDeltaHedgeIntentExecutor.ExecutionResult> recycleReconciled =
                executor.reconcileSentRecycleIntents(batchSize);
        HtxDeltaHedgeIntentExecutor.RecoveryBacklog after = executor.recoveryBacklog();
        HtxDeltaHedgeIntentExecutor.PendingBacklog afterPending = executor.pendingBacklog();
        int reconciled = hedgeReconciled.size() + recycleReconciled.size();
        int blockingResults = countBlockingResults(hedgeReconciled) + countBlockingResults(recycleReconciled);
        if (before.blocksAutoSend() || after.blocksAutoSend() || hasBlockingResult(hedgeReconciled)
                || hasBlockingResult(recycleReconciled)) {
            return result(RunCode.MANUAL_INTERVENTION_REQUIRED, reconciled, 0,
                    hedgeReconciled, recycleReconciled, after, afterPending,
                    Math.max(after.totalBlockingRows(), blockingResults),
                    "HEDGE_DEGRADED/UNKNOWN exists; freeze pending hedge sends and follow runbook");
        }
        if (props.isHedgeSupervisorShadowOnly()) {
            return result(RunCode.SHADOW_ONLY, reconciled, 0, hedgeReconciled,
                    recycleReconciled, after, afterPending, 0,
                    "shadow-only mode; reconciled SENT rows but did not submit pending intents");
        }
        if (!props.isHedgeAutoSendEnabled()) {
            return result(RunCode.AUTO_SEND_DISABLED, reconciled, 0, hedgeReconciled,
                    recycleReconciled, after, afterPending, 0,
                    "hedge auto-send disabled");
        }
        if (props.isDryRun() || !props.isLiveEnabled()) {
            return result(RunCode.LIVE_GATE_CLOSED, reconciled, 0, hedgeReconciled,
                    recycleReconciled, after, afterPending, 0,
                    "live gates closed; pending intents left untouched");
        }
        List<HtxDeltaHedgeIntentExecutor.ExecutionResult> hedgeSubmitted =
                executor.executePendingHedgeIntents(batchSize);
        List<HtxDeltaHedgeIntentExecutor.ExecutionResult> recycleSubmitted =
                executor.executePendingRecycleIntents(batchSize);
        int sent = countSubmittedResults(hedgeSubmitted) + countSubmittedResults(recycleSubmitted);
        HtxDeltaHedgeIntentExecutor.RecoveryBacklog finalBacklog = executor.recoveryBacklog();
        HtxDeltaHedgeIntentExecutor.PendingBacklog finalPending = executor.pendingBacklog();
        int postSubmitBlocking = countBlockingResults(hedgeSubmitted) + countBlockingResults(recycleSubmitted);
        if (finalBacklog.blocksAutoSend() || postSubmitBlocking > 0) {
            return result(RunCode.MANUAL_INTERVENTION_REQUIRED, reconciled, sent,
                    hedgeReconciled, recycleReconciled, finalBacklog, finalPending,
                    Math.max(finalBacklog.totalBlockingRows(), postSubmitBlocking),
                    "pending send produced HEDGE_DEGRADED/UNKNOWN; follow runbook before next send");
        }
        return result(RunCode.EXECUTED_PENDING, reconciled, sent, hedgeReconciled,
                recycleReconciled, finalBacklog, finalPending, 0,
                "pending intents submitted after reconciliation gates passed");
    }

    private RunResult result(RunCode code, int reconciledRows, int submittedRows,
                             List<HtxDeltaHedgeIntentExecutor.ExecutionResult> hedgeReconciled,
                             List<HtxDeltaHedgeIntentExecutor.ExecutionResult> recycleReconciled,
                             HtxDeltaHedgeIntentExecutor.RecoveryBacklog backlog,
                             HtxDeltaHedgeIntentExecutor.PendingBacklog pendingBacklog,
                             int blockingRows, String reason) {
        int reconciledHedgeRows = hedgeReconciled.size();
        int reconciledRecycleRows = recycleReconciled.size();
        int pendingSkippedRows = pendingRowsLeftUntouched(code) ? pendingBacklog.totalRows() : 0;
        ShadowReport report = new ShadowReport(
                code,
                props.isHedgeSupervisorEnabled(),
                props.isHedgeSupervisorShadowOnly(),
                props.isHedgeAutoSendEnabled(),
                props.isDryRun(),
                props.isLiveEnabled(),
                Math.max(1, props.getHedgeSupervisorBatchSize()),
                reconciledHedgeRows,
                reconciledRecycleRows,
                pendingBacklog.hedgeIntents(),
                pendingBacklog.recycleCloseSagas(),
                submittedRows,
                pendingSkippedRows,
                RunCode.MANUAL_INTERVENTION_REQUIRED.equals(code) ? pendingBacklog.totalRows() : 0,
                countBlockingResults(hedgeReconciled) + countBlockingResults(recycleReconciled),
                backlog,
                blockingRows > 0 ? AlertLevel.MANUAL_INTERVENTION_REQUIRED : AlertLevel.NONE,
                blockingRows > 0 ? reason : "");
        return new RunResult(code, reconciledRows, submittedRows, blockingRows, backlog, report, reason);
    }

    private static boolean pendingRowsLeftUntouched(RunCode code) {
        return RunCode.SHADOW_ONLY.equals(code)
                || RunCode.AUTO_SEND_DISABLED.equals(code)
                || RunCode.LIVE_GATE_CLOSED.equals(code);
    }

    private static boolean hasBlockingResult(List<HtxDeltaHedgeIntentExecutor.ExecutionResult> results) {
        return countBlockingResults(results) > 0;
    }

    private static int countBlockingResults(List<HtxDeltaHedgeIntentExecutor.ExecutionResult> results) {
        int count = 0;
        for (HtxDeltaHedgeIntentExecutor.ExecutionResult result : results) {
            if (HtxDeltaHedgeIntentExecutor.ExecutionCode.HEDGE_DEGRADED.equals(result.code())
                    || HtxDeltaHedgeIntentExecutor.ExecutionCode.UNKNOWN.equals(result.code())) {
                count++;
            }
        }
        return count;
    }

    private static int countSubmittedResults(List<HtxDeltaHedgeIntentExecutor.ExecutionResult> results) {
        int count = 0;
        for (HtxDeltaHedgeIntentExecutor.ExecutionResult result : results) {
            if (HtxDeltaHedgeIntentExecutor.ExecutionCode.SENT.equals(result.code())) {
                count++;
            }
        }
        return count;
    }

    public enum AlertLevel {
        NONE,
        MANUAL_INTERVENTION_REQUIRED
    }

    public enum RunCode {
        DISABLED,
        SHADOW_ONLY,
        AUTO_SEND_DISABLED,
        LIVE_GATE_CLOSED,
        MANUAL_INTERVENTION_REQUIRED,
        EXECUTED_PENDING
    }

    public record RunResult(RunCode code, int reconciledRows, int submittedRows,
                            int blockingRows,
                            HtxDeltaHedgeIntentExecutor.RecoveryBacklog backlog,
                            ShadowReport shadowReport,
                            String reason) {
    }

    public record ShadowReport(RunCode code, boolean supervisorEnabled, boolean shadowOnly,
                               boolean autoSendEnabled, boolean dryRun, boolean liveEnabled,
                               int batchSize, int reconciledHedgeRows,
                               int reconciledRecycleRows, int pendingHedgeRows,
                               int pendingRecycleRows, int pendingSubmittedRows,
                               int pendingSkippedRows, int pendingBlockedRows,
                               int newBlockingResults,
                               HtxDeltaHedgeIntentExecutor.RecoveryBacklog recoveryBacklog,
                               AlertLevel alertLevel, String alertReason) {
        public boolean requiresManualIntervention() {
            return AlertLevel.MANUAL_INTERVENTION_REQUIRED.equals(alertLevel);
        }
    }
}
