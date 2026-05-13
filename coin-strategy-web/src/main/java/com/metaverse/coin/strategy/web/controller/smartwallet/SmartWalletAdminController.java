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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SmartWallet admin 写接口（命令模式）：
 *
 * <ul>
 *   <li>POST /smart-wallet/admin/reset-data — 异步长任务（清表操作），仅 enqueue + GET status</li>
 *   <li>POST /smart-wallet/admin/recover-state — 异步长任务（全链路重梳理），仅 enqueue + GET status</li>
 * </ul>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletAdminController {

    private final ManualTriggerDispatcher dispatcher;
    private final StrategyManualTriggerMapper triggerMapper;
    private final ObjectMapper objectMapper;

    public SmartWalletAdminController(ManualTriggerDispatcher dispatcher,
                                      StrategyManualTriggerMapper triggerMapper,
                                      ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.triggerMapper = triggerMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/admin/reset-data")
    public Map<String, Object> resetData(@RequestParam(defaultValue = "false") boolean confirm,
                                         @RequestParam(defaultValue = "true") boolean includeProfiles,
                                         @RequestParam(defaultValue = "true") boolean includeRiskArtifacts,
                                         @RequestParam(defaultValue = "true") boolean includeRuntimeSeeds,
                                         @RequestParam(defaultValue = "true") boolean includeMetadataCache) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("confirm", confirm);
        payload.put("includeProfiles", includeProfiles);
        payload.put("includeRiskArtifacts", includeRiskArtifacts);
        payload.put("includeRuntimeSeeds", includeRuntimeSeeds);
        payload.put("includeMetadataCache", includeMetadataCache);
        Long triggerId = dispatcher.enqueue(
                StrategyManualTriggerAction.SMART_WALLET_ADMIN_RESET_DATA,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", String.valueOf(triggerId));
        body.put("triggerId", triggerId);
        body.put("status", "ACCEPTED");
        return body;
    }

    @GetMapping("/admin/reset-data/{triggerId}")
    public ResponseEntity<Map<String, Object>> resetDataStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_ADMIN_RESET_DATA);
    }

    @PostMapping("/admin/recover-state")
    public Map<String, Object> recoverState(@RequestParam(required = false) String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (reason != null) payload.put("reason", reason);
        Long triggerId = dispatcher.enqueue(
                StrategyManualTriggerAction.SMART_WALLET_ADMIN_RECOVER_STATE,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", String.valueOf(triggerId));
        body.put("triggerId", triggerId);
        body.put("status", "ACCEPTED");
        return body;
    }

    @GetMapping("/admin/recover-state/{triggerId}")
    public ResponseEntity<Map<String, Object>> recoverStateStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_ADMIN_RECOVER_STATE);
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
}
