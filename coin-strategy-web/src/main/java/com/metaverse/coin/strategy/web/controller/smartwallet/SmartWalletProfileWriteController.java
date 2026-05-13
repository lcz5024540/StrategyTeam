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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SmartWallet profiles 写接口（命令模式）：
 *
 * <ul>
 *   <li>POST /smart-wallet/profiles/{walletAddress}/override — 同步等待（≤60s）</li>
 *   <li>POST /smart-wallet/profiles/overrides/batch — 同步等待（≤60s）</li>
 *   <li>POST /smart-wallet/profiles/sync-all-positions — 立即返回 triggerId（长任务）</li>
 *   <li>POST /smart-wallet/profiles/metrics/rebuild — 立即返回 triggerId（长任务）</li>
 *   <li>GET /smart-wallet/profiles/sync-all-positions/{triggerId} — 查命令状态</li>
 *   <li>GET /smart-wallet/profiles/metrics/rebuild/{triggerId} — 查命令状态</li>
 * </ul>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletProfileWriteController {

    private final ManualTriggerDispatcher dispatcher;
    private final StrategyManualTriggerMapper triggerMapper;
    private final ObjectMapper objectMapper;

    public SmartWalletProfileWriteController(ManualTriggerDispatcher dispatcher,
                                             StrategyManualTriggerMapper triggerMapper,
                                             ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.triggerMapper = triggerMapper;
        this.objectMapper = objectMapper;
    }

    // ── 同步语义（短任务）────────────────────────────────────────────

    @PostMapping("/profiles/{walletAddress}/override")
    public ResponseEntity<JsonNode> updateProfileOverride(@PathVariable String walletAddress,
                                                          @RequestBody(required = false) Map<String, Object> request)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("walletAddress", walletAddress);
        payload.put("request", request == null ? Map.of() : request);
        ManualTriggerDispatcher.Result r = dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_PROFILE_OVERRIDE_UPDATE,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS);
        return toResponse(r);
    }

    @PostMapping("/profiles/overrides/batch")
    public ResponseEntity<JsonNode> batchUpdateProfileOverrides(@RequestBody Map<String, Object> request)
            throws InterruptedException {
        ManualTriggerDispatcher.Result r = dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_PROFILE_OVERRIDE_BATCH,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                request == null ? Map.of() : request,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS);
        return toResponse(r);
    }

    // ── 异步语义（长任务）：仅 enqueue，立即返回 ────────────────────

    @PostMapping("/profiles/sync-all-positions")
    public Map<String, Object> syncAllPositions(@RequestParam(defaultValue = "50") Integer batchSize) {
        Long triggerId = dispatcher.enqueue(
                StrategyManualTriggerAction.SMART_WALLET_PROFILES_SYNC_ALL_POSITIONS,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                Map.of("batchSize", batchSize));
        return Map.of(
                "jobId", String.valueOf(triggerId),
                "triggerId", triggerId,
                "status", "ACCEPTED",
                "batchSize", batchSize);
    }

    @GetMapping("/profiles/sync-all-positions/{triggerId}")
    public ResponseEntity<Map<String, Object>> syncAllPositionsStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId,
                StrategyManualTriggerAction.SMART_WALLET_PROFILES_SYNC_ALL_POSITIONS);
    }

    @PostMapping("/profiles/metrics/rebuild")
    public Map<String, Object> rebuildProfileMetrics(@RequestParam(defaultValue = "candidates") String scope,
                                                     @RequestParam(defaultValue = "30") Integer limit) {
        Long triggerId = dispatcher.enqueue(
                StrategyManualTriggerAction.SMART_WALLET_PROFILES_METRICS_REBUILD,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                Map.of("scope", scope, "limit", limit));
        return Map.of(
                "jobId", String.valueOf(triggerId),
                "triggerId", triggerId,
                "status", "ACCEPTED",
                "scope", scope,
                "limit", limit);
    }

    @GetMapping("/profiles/metrics/rebuild/{triggerId}")
    public ResponseEntity<Map<String, Object>> rebuildProfileMetricsStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId,
                StrategyManualTriggerAction.SMART_WALLET_PROFILES_METRICS_REBUILD);
    }

    // ── 工具方法 ────────────────────────────────────────────────────

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

    private ResponseEntity<JsonNode> toResponse(ManualTriggerDispatcher.Result r) {
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
        // COMPLETED 或 FAILED：直接把 result_json 解出来返回（兼容原 controller 同步 JSON 体）；
        // 如果没有结构化结果，回 message。
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
}
