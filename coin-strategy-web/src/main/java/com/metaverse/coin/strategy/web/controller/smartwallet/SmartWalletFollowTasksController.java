package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyManualTriggerMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerAction;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerStatus;
import com.metaverse.coin.strategy.web.service.trigger.ManualTriggerDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SmartWallet follow-tasks / follow-trades 端点（命令模式入口）。
 *
 * <p>所有端点都通过 {@link ManualTriggerDispatcher} 写入 strategy_manual_trigger 表，由 job 端
 * {@code StrategyManualTriggerConsumer} 异步消费并回写 result_json。短任务（GET 读 + 几乎所有
 * POST）走 {@code dispatchAndWait} 同步等待，长任务（如 process）只 enqueue 并配 GET status。</p>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletFollowTasksController {

    private final ManualTriggerDispatcher dispatcher;
    private final StrategyManualTriggerMapper triggerMapper;
    private final ObjectMapper objectMapper;

    public SmartWalletFollowTasksController(ManualTriggerDispatcher dispatcher,
                                            StrategyManualTriggerMapper triggerMapper,
                                            ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.triggerMapper = triggerMapper;
        this.objectMapper = objectMapper;
    }

    // ── A 类读接口 ────────────────────────────────────────────────────

    @GetMapping("/follow-tasks/executable")
    public ResponseEntity<JsonNode> executableFollowTasks(@RequestParam(defaultValue = "50") Integer limit,
                                                          @RequestParam(required = false) Integer scanLimit)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        if (scanLimit != null) payload.put("scanLimit", scanLimit);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_EXECUTABLE,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @GetMapping("/follow-tasks/manual-allowlist")
    public ResponseEntity<JsonNode> manualAllowlistFollowTasks(@RequestParam(defaultValue = "50") Integer limit,
                                                               @RequestParam(defaultValue = "false") Boolean includeBlocked)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        payload.put("includeBlocked", includeBlocked);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_MANUAL_ALLOWLIST,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @GetMapping("/follow-tasks/single-wallet-override")
    public ResponseEntity<JsonNode> singleWalletOverrideFollowTasks(
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(required = false) String preset,
            @RequestParam(required = false) Integer taskLimit,
            @RequestParam(required = false) String policyTag,
            @RequestParam(required = false) Integer maxSignalAgeSeconds,
            @RequestParam(required = false) Integer minDistinctWallets,
            @RequestParam(required = false) Integer minDistinctEntities,
            @RequestParam(required = false) BigDecimal minConsensusScore,
            @RequestParam(required = false) Boolean qualityWeightedConsensusEnabled,
            @RequestParam(required = false) Boolean enableSingleWalletOverrideCanary,
            @RequestParam(required = false) BigDecimal singleWalletOverrideCanaryRate,
            @RequestParam(required = false) BigDecimal singleWalletOverrideMinWalletScore,
            @RequestParam(required = false) BigDecimal singleWalletOverrideMinWinRate,
            @RequestParam(required = false) Integer singleWalletOverrideMinTrades,
            @RequestParam(required = false) Integer singleWalletOverrideMinRecentTrades7d,
            @RequestParam(required = false) BigDecimal singleWalletOverrideMinRecentWinRate7d,
            @RequestParam(required = false) BigDecimal singleWalletOverrideMinReplicabilityScore,
            @RequestParam(required = false) BigDecimal singleWalletOverrideMinQualityScore,
            @RequestParam(required = false) BigDecimal singleWalletOverrideMinTriggerSizeUsdc,
            @RequestParam(required = false) BigDecimal singleWalletOverrideMinRecommendedStakeUsdc)
            throws InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "taskLimit", taskLimit);
        putIfPresent(request, "policyTag", policyTag);
        putIfPresent(request, "maxSignalAgeSeconds", maxSignalAgeSeconds);
        putIfPresent(request, "minDistinctWallets", minDistinctWallets);
        putIfPresent(request, "minDistinctEntities", minDistinctEntities);
        putIfPresent(request, "minConsensusScore", minConsensusScore);
        putIfPresent(request, "qualityWeightedConsensusEnabled", qualityWeightedConsensusEnabled);
        putIfPresent(request, "enableSingleWalletOverrideCanary", enableSingleWalletOverrideCanary);
        putIfPresent(request, "singleWalletOverrideCanaryRate", singleWalletOverrideCanaryRate);
        putIfPresent(request, "singleWalletOverrideMinWalletScore", singleWalletOverrideMinWalletScore);
        putIfPresent(request, "singleWalletOverrideMinWinRate", singleWalletOverrideMinWinRate);
        putIfPresent(request, "singleWalletOverrideMinTrades", singleWalletOverrideMinTrades);
        putIfPresent(request, "singleWalletOverrideMinRecentTrades7d", singleWalletOverrideMinRecentTrades7d);
        putIfPresent(request, "singleWalletOverrideMinRecentWinRate7d", singleWalletOverrideMinRecentWinRate7d);
        putIfPresent(request, "singleWalletOverrideMinReplicabilityScore", singleWalletOverrideMinReplicabilityScore);
        putIfPresent(request, "singleWalletOverrideMinQualityScore", singleWalletOverrideMinQualityScore);
        putIfPresent(request, "singleWalletOverrideMinTriggerSizeUsdc", singleWalletOverrideMinTriggerSizeUsdc);
        putIfPresent(request, "singleWalletOverrideMinRecommendedStakeUsdc", singleWalletOverrideMinRecommendedStakeUsdc);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        if (preset != null) payload.put("preset", preset);
        payload.put("request", request);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_SINGLE_WALLET_OVERRIDE,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @GetMapping("/follow-tasks/opportunities")
    public ResponseEntity<JsonNode> opportunityFollowTasks(@RequestParam(defaultValue = "20") Integer limit,
                                                           @RequestParam(defaultValue = "3") Integer maxBlockers,
                                                           @RequestParam(defaultValue = "false") boolean includeExecutable)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        payload.put("maxBlockers", maxBlockers);
        payload.put("includeExecutable", includeExecutable);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_OPPORTUNITIES,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @GetMapping("/follow-tasks/coverage-report")
    public ResponseEntity<JsonNode> followCoverageReport(@RequestParam(required = false) String walletAddress,
                                                         @RequestParam(defaultValue = "5000") Integer limit)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (walletAddress != null) payload.put("walletAddress", walletAddress);
        payload.put("limit", limit);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_COVERAGE_REPORT,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @GetMapping("/follow-trades/attribution")
    public ResponseEntity<JsonNode> followTradeAttribution(@RequestParam(defaultValue = "EXIT_REASON") String groupBy,
                                                           @RequestParam(defaultValue = "500") Integer limit)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("groupBy", groupBy);
        payload.put("limit", limit);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TRADES_ATTRIBUTION,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    // ── B 类写接口 ────────────────────────────────────────────────────

    @PostMapping("/follow-tasks/intelligence/reevaluate-deferred")
    public ResponseEntity<JsonNode> reevaluateDeferred(@RequestParam(defaultValue = "50") Integer limit)
            throws InterruptedException {
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REEVALUATE_DEFERRED,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                Map.of("limit", limit),
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/follow-tasks/intelligence/replay-recent")
    public ResponseEntity<JsonNode> replayRecentIntelligence(@RequestParam(defaultValue = "100") Integer limit,
                                                             @RequestParam(defaultValue = "false") boolean includeProcessed)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        payload.put("includeProcessed", includeProcessed);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REPLAY_RECENT,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/follow-tasks/simulate-risk")
    public ResponseEntity<JsonNode> simulateRisk(@RequestBody(required = false) Map<String, Object> request)
            throws InterruptedException {
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_SIMULATE_RISK,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                request == null ? Map.of() : request,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/follow-tasks/replay-backtest")
    public ResponseEntity<JsonNode> replayBacktest(@RequestBody(required = false) Map<String, Object> request)
            throws InterruptedException {
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                request == null ? Map.of() : request,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/follow-tasks/replay-backtest/concentration-summary")
    public ResponseEntity<JsonNode> replayConcentrationSummary(@RequestBody(required = false) Map<String, Object> request)
            throws InterruptedException {
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST_CONCENTRATION,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                request == null ? Map.of() : request,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    // 长任务：仅 enqueue，立即返回 triggerId；前端用 GET status 轮询
    @PostMapping("/follow-tasks/process")
    public Map<String, Object> processFollowTasks() {
        Long triggerId = dispatcher.enqueue(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_PROCESS,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                Map.of());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", String.valueOf(triggerId));
        body.put("triggerId", triggerId);
        body.put("status", "ACCEPTED");
        return body;
    }

    @GetMapping("/follow-tasks/process/{triggerId}")
    public ResponseEntity<Map<String, Object>> processFollowTasksStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId,
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_PROCESS);
    }

    @PostMapping("/follow-tasks/{followTaskId}/dry-run")
    public ResponseEntity<JsonNode> followTaskDryRun(@PathVariable Long followTaskId) throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("followTaskId", followTaskId);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_DRY_RUN,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/follow-tasks/{followTaskId}/execute-live")
    public ResponseEntity<JsonNode> executeFollowTaskLive(@PathVariable Long followTaskId) throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("followTaskId", followTaskId);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_FOLLOW_TASKS_EXECUTE_LIVE,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    // ── 工具方法 ────────────────────────────────────────────────────

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private ResponseEntity<JsonNode> toJsonResponse(ManualTriggerDispatcher.Result r) {
        if (r.notFound()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(objectMapper.valueToTree(Map.of(
                            "status", "NOT_FOUND",
                            "message", r.message())));
        }
        if (r.timedOut()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(objectMapper.valueToTree(Map.of(
                            "status", StrategyManualTriggerStatus.RUNNING,
                            "message", r.message(),
                            "triggerId", r.triggerId())));
        }
        if (Boolean.TRUE.equals(r.success()) && r.resultJson() != null && !r.resultJson().isBlank()) {
            try {
                return ResponseEntity.ok(objectMapper.readTree(r.resultJson()));
            } catch (Exception ignore) {
                // 落到下面 fallback
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", r.status());
        body.put("success", r.success());
        body.put("message", r.message());
        body.put("triggerId", r.triggerId());
        HttpStatus statusCode = Boolean.TRUE.equals(r.success()) ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(statusCode).body(objectMapper.valueToTree(body));
    }

    private ResponseEntity<Map<String, Object>> triggerStatusResponse(String triggerIdStr, String expectedAction) {
        Long triggerId;
        try {
            triggerId = Long.parseLong(triggerIdStr);
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "NOT_FOUND", "triggerId", triggerIdStr));
        }
        StrategyManualTriggerEntity row = triggerMapper.selectById(triggerId);
        if (row == null || (expectedAction != null && !expectedAction.equals(row.getAction()))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "NOT_FOUND", "triggerId", triggerIdStr));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("triggerId", row.getId());
        body.put("jobId", String.valueOf(row.getId()));
        body.put("status", row.getStatus());
        body.put("requestedAt", row.getRequestedAt());
        body.put("processedAt", row.getProcessedAt());
        body.put("completedAt", row.getCompletedAt());
        body.put("success", row.getSuccess());
        body.put("message", row.getMessage());
        if (row.getResultJson() != null && !row.getResultJson().isBlank()) {
            try {
                body.put("result", objectMapper.readTree(row.getResultJson()));
            } catch (Exception e) {
                body.put("resultRaw", row.getResultJson());
                body.put("resultParseError", e.getMessage());
            }
        }
        return ResponseEntity.ok(body);
    }
}
