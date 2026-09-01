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
        } catch (RuntimeException ex) {
            log.error("[HTX-HEDGE-SUPERVISOR] failed; no auto-send will be attempted", ex);
        }
    }

    RunResult runOnce() {
        if (!props.isHedgeSupervisorEnabled()) {
            return new RunResult(RunCode.DISABLED, 0, 0, 0,
                    new HtxDeltaHedgeIntentExecutor.RecoveryBacklog(0, 0, 0, 0),
                    "hedge supervisor disabled");
        }
        int batchSize = Math.max(1, props.getHedgeSupervisorBatchSize());
        HtxDeltaHedgeIntentExecutor.RecoveryBacklog before = executor.recoveryBacklog();
        List<HtxDeltaHedgeIntentExecutor.ExecutionResult> hedgeReconciled =
                executor.reconcileSentHedgeIntents(batchSize);
        List<HtxDeltaHedgeIntentExecutor.ExecutionResult> recycleReconciled =
                executor.reconcileSentRecycleIntents(batchSize);
        HtxDeltaHedgeIntentExecutor.RecoveryBacklog after = executor.recoveryBacklog();
        int reconciled = hedgeReconciled.size() + recycleReconciled.size();
        int blockingResults = countBlockingResults(hedgeReconciled) + countBlockingResults(recycleReconciled);
        if (before.blocksAutoSend() || after.blocksAutoSend() || hasBlockingResult(hedgeReconciled)
                || hasBlockingResult(recycleReconciled)) {
            return new RunResult(RunCode.MANUAL_INTERVENTION_REQUIRED, reconciled, 0,
                    Math.max(after.totalBlockingRows(), blockingResults), after,
                    "HEDGE_DEGRADED/UNKNOWN exists; freeze pending hedge sends and follow runbook");
        }
        if (props.isHedgeSupervisorShadowOnly()) {
            return new RunResult(RunCode.SHADOW_ONLY, reconciled, 0, 0, after,
                    "shadow-only mode; reconciled SENT rows but did not submit pending intents");
        }
        if (!props.isHedgeAutoSendEnabled()) {
            return new RunResult(RunCode.AUTO_SEND_DISABLED, reconciled, 0, 0, after,
                    "hedge auto-send disabled");
        }
        if (props.isDryRun() || !props.isLiveEnabled()) {
            return new RunResult(RunCode.LIVE_GATE_CLOSED, reconciled, 0, 0, after,
                    "live gates closed; pending intents left untouched");
        }
        int sent = executor.executePendingHedgeIntents(batchSize).size()
                + executor.executePendingRecycleIntents(batchSize).size();
        return new RunResult(RunCode.EXECUTED_PENDING, reconciled, sent, 0, after,
                "pending intents submitted after reconciliation gates passed");
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
                            String reason) {
    }
}
