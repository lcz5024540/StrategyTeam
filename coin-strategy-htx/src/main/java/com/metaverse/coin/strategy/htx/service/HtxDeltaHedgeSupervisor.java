package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.config.HtxMarketMakerProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxHedgeRecycleRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity;
import com.metaverse.coin.strategy.htx.strategy.DeltaHedgeManager;
import com.metaverse.coin.strategy.htx.strategy.HtxDeltaHedgeSnapshotProvider;
import com.metaverse.coin.strategy.htx.strategy.HtxGridWindowStore;
import com.metaverse.coin.strategy.htx.util.HtxSymbolUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shadow-first supervisor for moving-grid hedge/recycle intents.
 *
 * <p>It plans durable aggregate hedge intents from the moving-grid inventory, then reconciles and
 * surfaces manual-intervention cases. Pending sends are deliberately gated behind a separate opt-in
 * so enabling the supervisor cannot create real orders by itself.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean({HtxDeltaHedgeIntentExecutor.class, HtxDeltaHedgeSnapshotProvider.class,
        HtxGridWindowStore.class, HtxHedgeRecycleRecorder.class})
@ConditionalOnExpression("${htx.contract.hedge-supervisor-enabled:false}")
public class HtxDeltaHedgeSupervisor {

    private static final String CLIENT_ORDER_ID_PREFIX = "CS-MM-HEDGE-";

    private final HtxDeltaHedgeIntentExecutor executor;
    private final HtxContractProperties props;
    private final HtxMarketMakerProperties marketMakerProps;
    private final HtxDeltaHedgeSnapshotProvider snapshotProvider;
    private final HtxGridWindowStore gridWindowStore;
    private final HtxHedgeRecycleRecorder recorder;
    private final DeltaHedgeManager hedgeManager = new DeltaHedgeManager();

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
            return result(RunCode.DISABLED, 0, 0, PlanningSummary.empty(), List.of(), List.of(),
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
        int reconciled = hedgeReconciled.size() + recycleReconciled.size();
        int blockingResults = countBlockingResults(hedgeReconciled) + countBlockingResults(recycleReconciled);
        if (before.blocksAutoSend() || after.blocksAutoSend() || hasBlockingResult(hedgeReconciled)
                || hasBlockingResult(recycleReconciled)) {
            return result(RunCode.MANUAL_INTERVENTION_REQUIRED, reconciled, 0,
                    PlanningSummary.empty(), hedgeReconciled, recycleReconciled,
                    after, executor.pendingBacklog(),
                    Math.max(after.totalBlockingRows(), blockingResults),
                    "HEDGE_DEGRADED/UNKNOWN exists; freeze pending hedge sends and follow runbook");
        }
        PlanningSummary planning = planHedgeIntents();
        HtxDeltaHedgeIntentExecutor.PendingBacklog afterPending = executor.pendingBacklog();
        if (props.isHedgeSupervisorShadowOnly()) {
            return result(RunCode.SHADOW_ONLY, reconciled, 0, planning, hedgeReconciled,
                    recycleReconciled, after, afterPending, 0,
                    "shadow-only mode; reconciled SENT rows but did not submit pending intents");
        }
        if (!props.isHedgeAutoSendEnabled()) {
            return result(RunCode.AUTO_SEND_DISABLED, reconciled, 0, planning, hedgeReconciled,
                    recycleReconciled, after, afterPending, 0,
                    "hedge auto-send disabled");
        }
        if (props.isDryRun() || !props.isLiveEnabled()) {
            return result(RunCode.LIVE_GATE_CLOSED, reconciled, 0, planning, hedgeReconciled,
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
                    planning, hedgeReconciled, recycleReconciled, finalBacklog, finalPending,
                    Math.max(finalBacklog.totalBlockingRows(), postSubmitBlocking),
                    "pending send produced HEDGE_DEGRADED/UNKNOWN; follow runbook before next send");
        }
        return result(RunCode.EXECUTED_PENDING, reconciled, sent, planning, hedgeReconciled,
                recycleReconciled, finalBacklog, finalPending, 0,
                "pending intents submitted after reconciliation gates passed");
    }

    private PlanningSummary planHedgeIntents() {
        if (!props.isHedgePlannerEnabled()) {
            return PlanningSummary.empty();
        }
        if (!marketMakerProps.isEnabled() || !marketMakerProps.isGridModeEnabled()) {
            return PlanningSummary.empty();
        }
        if (positive(props.getHedgeRatio()).signum() <= 0
                || positive(props.getHedgeMaxShortBase()).signum() <= 0) {
            log.warn("MM hedge planner skipped: hedge ratio or max short base is not positive");
            return PlanningSummary.empty();
        }
        List<String> symbols = safeSymbols(marketMakerProps.getSymbols());
        PlanningSummary summary = PlanningSummary.empty();
        for (String symbol : symbols) {
            summary = summary.merge(planSymbol(symbol));
        }
        return summary;
    }

    private PlanningSummary planSymbol(String symbol) {
        HtxGridWindowEntity window = gridWindowStore.find(symbol);
        if (window == null || window.getEpoch() == null) {
            log.debug("MM hedge planner skipped symbol={} reason=no grid window", symbol);
            return new PlanningSummary(1, 0, 0, 1, 0);
        }
        HtxDeltaHedgeSnapshotProvider.HedgeSourceRequest request =
                new HtxDeltaHedgeSnapshotProvider.HedgeSourceRequest(
                        true,
                        symbol,
                        contractCode(symbol),
                        null,
                        props.getHedgeRatio(),
                        props.getHedgeMaxShortBase(),
                        true,
                        true,
                        props.getHedgeMinimumMarginAvailable(),
                        props.getHedgeMaxAbsFundingRate(),
                        props.getHedgeMaxBasisRate(),
                        props.getHedgeMinimumLiquidationDistanceRate(),
                        clientOrderIdPrefix(symbol),
                        window.getEpoch());
        HtxDeltaHedgeSnapshotProvider.HedgeSnapshot snapshot = snapshotProvider.snapshot(request);
        if (snapshot == null || snapshot.input() == null) {
            log.warn("MM hedge planner skipped symbol={} epoch={} reason=snapshot missing",
                    symbol, window.getEpoch());
            return new PlanningSummary(1, 0, 0, 1, 1);
        }
        DeltaHedgeManager.HedgeInput input = withPlanClientOrderIdPrefix(symbol, snapshot.input());
        DeltaHedgeManager.HedgeDecision decision = hedgeManager.plan(input);
        HtxHedgeRecycleRecorder.RecordResult recorded =
                recorder.recordHedgeIntent(symbol, window.getEpoch(), decision);
        int degraded = snapshot.available() ? 0 : 1;
        if (recorded.appended()) {
            log.info("MM hedge intent appended symbol={} epoch={} action={} targetShortBase={} state={}",
                    symbol, window.getEpoch(), decision.action(), decision.targetShortBase(), decision.state());
            return new PlanningSummary(1, 1, 0, 0, degraded);
        }
        if (recorded.duplicate()) {
            return new PlanningSummary(1, 0, 1, 0, degraded);
        }
        log.debug("MM hedge planner skipped symbol={} epoch={} reason={} snapshotReason={}",
                symbol, window.getEpoch(), recorded.skippedReason(), snapshot.reason());
        return new PlanningSummary(1, 0, 0, 1, degraded);
    }

    private RunResult result(RunCode code, int reconciledRows, int submittedRows,
                             PlanningSummary planningSummary,
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
                planningSummary.evaluatedSymbols(),
                planningSummary.appendedHedgeIntents(),
                planningSummary.duplicatedHedgeIntents(),
                planningSummary.skippedHedgePlans(),
                planningSummary.degradedSnapshots(),
                countBlockingResults(hedgeReconciled) + countBlockingResults(recycleReconciled),
                backlog,
                blockingRows > 0 ? AlertLevel.MANUAL_INTERVENTION_REQUIRED : AlertLevel.NONE,
                blockingRows > 0 ? reason : "");
        return new RunResult(code, reconciledRows, submittedRows, blockingRows, backlog, report, reason);
    }

    private static List<String> safeSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            normalized.add(symbol.trim().toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private static String contractCode(String symbol) {
        String base = HtxSymbolUtil.base(symbol);
        String quote = HtxSymbolUtil.quote(symbol);
        if (base.isBlank() || quote.isBlank()) {
            return symbol.toUpperCase(Locale.ROOT);
        }
        return base.toUpperCase(Locale.ROOT) + "-" + quote.toUpperCase(Locale.ROOT);
    }

    private static String clientOrderIdPrefix(String symbol) {
        return CLIENT_ORDER_ID_PREFIX + symbol.toUpperCase(Locale.ROOT);
    }

    private static DeltaHedgeManager.HedgeInput withPlanClientOrderIdPrefix(
            String symbol, DeltaHedgeManager.HedgeInput input) {
        return new DeltaHedgeManager.HedgeInput(
                input.enabled(),
                input.symbol(),
                input.contractCode(),
                input.spotBaseInventory(),
                input.currentShortContracts(),
                input.contractSize(),
                input.hedgeRatio(),
                input.maxShortBase(),
                input.fundingAllowed(),
                input.basisAllowed(),
                input.marginSafe(),
                clientOrderIdPrefix(symbol, input),
                input.epoch());
    }

    private static String clientOrderIdPrefix(String symbol, DeltaHedgeManager.HedgeInput input) {
        return clientOrderIdPrefix(symbol) + "-T" + targetContracts(input)
                + "-C" + Math.max(0L, input.currentShortContracts());
    }

    private static long targetContracts(DeltaHedgeManager.HedgeInput input) {
        BigDecimal contractSize = positive(input.contractSize());
        if (contractSize.signum() <= 0) {
            return 0L;
        }
        BigDecimal target = positive(input.spotBaseInventory()).multiply(positive(input.hedgeRatio()));
        BigDecimal cap = positive(input.maxShortBase());
        if (target.compareTo(cap) > 0) {
            target = cap;
        }
        return target.divide(contractSize, 0, RoundingMode.DOWN).longValue();
    }

    private static BigDecimal positive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return value;
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
                               int evaluatedHedgeSymbols, int appendedHedgeIntents,
                               int duplicatedHedgeIntents, int skippedHedgePlans,
                               int degradedHedgeSnapshots,
                               int newBlockingResults,
                               HtxDeltaHedgeIntentExecutor.RecoveryBacklog recoveryBacklog,
                               AlertLevel alertLevel, String alertReason) {
        public boolean requiresManualIntervention() {
            return AlertLevel.MANUAL_INTERVENTION_REQUIRED.equals(alertLevel);
        }
    }

    private record PlanningSummary(int evaluatedSymbols, int appendedHedgeIntents,
                                   int duplicatedHedgeIntents, int skippedHedgePlans,
                                   int degradedSnapshots) {
        private static PlanningSummary empty() {
            return new PlanningSummary(0, 0, 0, 0, 0);
        }

        private PlanningSummary merge(PlanningSummary other) {
            return new PlanningSummary(
                    evaluatedSymbols + other.evaluatedSymbols,
                    appendedHedgeIntents + other.appendedHedgeIntents,
                    duplicatedHedgeIntents + other.duplicatedHedgeIntents,
                    skippedHedgePlans + other.skippedHedgePlans,
                    degradedSnapshots + other.degradedSnapshots);
        }
    }
}
