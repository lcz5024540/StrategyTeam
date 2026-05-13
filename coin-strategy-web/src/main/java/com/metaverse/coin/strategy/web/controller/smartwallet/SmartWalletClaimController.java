package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyManualTriggerMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerAction;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerEntity;
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
 * SmartWallet claim 写接口（命令模式）：长任务，仅 enqueue + GET status。
 *
 * <ul>
 *   <li>POST /smart-wallet/claim/process</li>
 *   <li>POST /smart-wallet/claim/retries/process</li>
 * </ul>
 *
 * <p>对应 4 个 GET 端点（/claim/status、/claim/runs、/claim/retries、/claim/retries/report）已在
 * {@code SmartWalletReadController} 实现，本类不再重复。</p>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletClaimController {

    private final ManualTriggerDispatcher dispatcher;
    private final StrategyManualTriggerMapper triggerMapper;
    private final ObjectMapper objectMapper;

    public SmartWalletClaimController(ManualTriggerDispatcher dispatcher,
                                      StrategyManualTriggerMapper triggerMapper,
                                      ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.triggerMapper = triggerMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/claim/process")
    public Map<String, Object> processClaims(@RequestParam(required = false) Integer limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (limit != null) payload.put("limit", limit);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_CLAIM_PROCESS, payload);
    }

    @GetMapping("/claim/process/{triggerId}")
    public ResponseEntity<Map<String, Object>> processClaimsStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_CLAIM_PROCESS);
    }

    @PostMapping("/claim/retries/process")
    public Map<String, Object> processClaimRetries(@RequestParam(required = false) Integer limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (limit != null) payload.put("limit", limit);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_CLAIM_RETRIES_PROCESS, payload);
    }

    @GetMapping("/claim/retries/process/{triggerId}")
    public ResponseEntity<Map<String, Object>> processClaimRetriesStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_CLAIM_RETRIES_PROCESS);
    }

    // ── 工具方法 ────────────────────────────────────────────────────

    private Map<String, Object> enqueueAccepted(String action, Object payload) {
        Long triggerId = dispatcher.enqueue(action,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID, payload);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", String.valueOf(triggerId));
        body.put("triggerId", triggerId);
        body.put("status", "ACCEPTED");
        return body;
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
