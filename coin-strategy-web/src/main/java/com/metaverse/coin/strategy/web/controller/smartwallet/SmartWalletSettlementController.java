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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SmartWallet settlement 写接口（命令模式）：长任务，仅 enqueue + GET status。
 *
 * <ul>
 *   <li>POST /smart-wallet/settlement/sync</li>
 *   <li>POST /smart-wallet/settlement/review-paper-history</li>
 *   <li>POST /smart-wallet/settlement/retries/process</li>
 *   <li>POST /smart-wallet/settlement/manual-confirm-local</li>
 * </ul>
 *
 * <p>对应 4 个 GET 端点（/settlement/status、/settlement/runs、/settlement/retries、
 * /settlement/retries/report）已在 {@code SmartWalletReadController} 实现，本类不再重复。</p>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletSettlementController {

    private final ManualTriggerDispatcher dispatcher;
    private final StrategyManualTriggerMapper triggerMapper;
    private final ObjectMapper objectMapper;

    public SmartWalletSettlementController(ManualTriggerDispatcher dispatcher,
                                           StrategyManualTriggerMapper triggerMapper,
                                           ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.triggerMapper = triggerMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/settlement/sync")
    public Map<String, Object> syncSettlements(@RequestParam(defaultValue = "500") Integer positionLimit,
                                               @RequestParam(defaultValue = "100") Integer marketLimit,
                                               @RequestParam(defaultValue = "false") boolean refreshCache) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("positionLimit", positionLimit);
        payload.put("marketLimit", marketLimit);
        payload.put("refreshCache", refreshCache);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_SYNC, payload);
    }

    @GetMapping("/settlement/sync/{triggerId}")
    public ResponseEntity<Map<String, Object>> syncSettlementsStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_SYNC);
    }

    @PostMapping("/settlement/review-paper-history")
    public Map<String, Object> reviewPaperHistory(@RequestParam(defaultValue = "1000") Integer limit,
                                                  @RequestParam(defaultValue = "true") boolean refreshCache) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        payload.put("refreshCache", refreshCache);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_REVIEW_PAPER, payload);
    }

    @GetMapping("/settlement/review-paper-history/{triggerId}")
    public ResponseEntity<Map<String, Object>> reviewPaperHistoryStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_REVIEW_PAPER);
    }

    @PostMapping("/settlement/retries/process")
    public Map<String, Object> processSettlementRetries(@RequestParam(required = false) Integer limit,
                                                        @RequestParam(defaultValue = "false") boolean refreshCache) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (limit != null) payload.put("limit", limit);
        payload.put("refreshCache", refreshCache);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_RETRIES_PROCESS, payload);
    }

    @GetMapping("/settlement/retries/process/{triggerId}")
    public ResponseEntity<Map<String, Object>> processSettlementRetriesStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_RETRIES_PROCESS);
    }

    @PostMapping("/settlement/manual-confirm-local")
    public Map<String, Object> confirmLocalSettlements(@RequestBody(required = false) Map<String, Object> request,
                                                       @RequestParam(defaultValue = "true") boolean refreshCache) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request", request == null ? Map.of() : request);
        payload.put("refreshCache", refreshCache);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_MANUAL_CONFIRM, payload);
    }

    @GetMapping("/settlement/manual-confirm-local/{triggerId}")
    public ResponseEntity<Map<String, Object>> confirmLocalSettlementsStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_SETTLEMENT_MANUAL_CONFIRM);
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
