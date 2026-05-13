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
 * SmartWallet accounting 写接口（命令模式）：
 * 全部 6 个端点均为长任务，仅 enqueue 立即返回 triggerId；前端用 GET status 轮询。
 *
 * <ul>
 *   <li>POST /smart-wallet/accounting/backfill</li>
 *   <li>POST /smart-wallet/accounting/backfill-market-end-at</li>
 *   <li>POST /smart-wallet/accounting/backfill-token-links</li>
 *   <li>POST /smart-wallet/accounting/repair-premature-settlements</li>
 *   <li>POST /smart-wallet/accounting/repair-premature-settlements/by-ids</li>
 *   <li>POST /smart-wallet/accounting/repair-market-ids/by-positions</li>
 * </ul>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletAccountingController {

    private final ManualTriggerDispatcher dispatcher;
    private final StrategyManualTriggerMapper triggerMapper;
    private final ObjectMapper objectMapper;

    public SmartWalletAccountingController(ManualTriggerDispatcher dispatcher,
                                           StrategyManualTriggerMapper triggerMapper,
                                           ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.triggerMapper = triggerMapper;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/accounting/backfill")
    public Map<String, Object> backfillAccounting(@RequestParam(defaultValue = "2000") Integer limit) {
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_BACKFILL,
                Map.of("limit", limit));
    }

    @GetMapping("/accounting/backfill/{triggerId}")
    public ResponseEntity<Map<String, Object>> backfillAccountingStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_BACKFILL);
    }

    @PostMapping("/accounting/backfill-market-end-at")
    public Map<String, Object> backfillMarketEndAt(@RequestParam(defaultValue = "500") Integer limit,
                                                   @RequestParam(defaultValue = "false") boolean refreshCache) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        payload.put("refreshCache", refreshCache);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_BACKFILL_MARKET_END_AT, payload);
    }

    @GetMapping("/accounting/backfill-market-end-at/{triggerId}")
    public ResponseEntity<Map<String, Object>> backfillMarketEndAtStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_BACKFILL_MARKET_END_AT);
    }

    @PostMapping("/accounting/backfill-token-links")
    public Map<String, Object> backfillTokenLinks(@RequestParam(defaultValue = "500") Integer limit,
                                                  @RequestParam(defaultValue = "false") boolean refreshCache) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        payload.put("refreshCache", refreshCache);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_BACKFILL_TOKEN_LINKS, payload);
    }

    @GetMapping("/accounting/backfill-token-links/{triggerId}")
    public ResponseEntity<Map<String, Object>> backfillTokenLinksStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_BACKFILL_TOKEN_LINKS);
    }

    @PostMapping("/accounting/repair-premature-settlements")
    public Map<String, Object> repairPrematureSettlements(@RequestParam(defaultValue = "500") Integer limit,
                                                          @RequestParam(defaultValue = "true") boolean refreshCache) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        payload.put("refreshCache", refreshCache);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS, payload);
    }

    @GetMapping("/accounting/repair-premature-settlements/{triggerId}")
    public ResponseEntity<Map<String, Object>> repairPrematureSettlementsStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS);
    }

    @PostMapping("/accounting/repair-premature-settlements/by-ids")
    public Map<String, Object> repairPrematureSettlementsByIds(@RequestBody(required = false) Map<String, Object> request) {
        Object payload = request == null ? Map.of() : request;
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS_BY_IDS, payload);
    }

    @GetMapping("/accounting/repair-premature-settlements/by-ids/{triggerId}")
    public ResponseEntity<Map<String, Object>> repairPrematureSettlementsByIdsStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_REPAIR_PREMATURE_SETTLEMENTS_BY_IDS);
    }

    @PostMapping("/accounting/repair-market-ids/by-positions")
    public Map<String, Object> repairMarketIdsByPositions(@RequestBody(required = false) Map<String, Object> request,
                                                          @RequestParam(defaultValue = "true") boolean syncAfterRepair,
                                                          @RequestParam(defaultValue = "true") boolean refreshCache) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request", request == null ? Map.of() : request);
        payload.put("syncAfterRepair", syncAfterRepair);
        payload.put("refreshCache", refreshCache);
        return enqueueAccepted(StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_REPAIR_MARKET_IDS_BY_POSITIONS, payload);
    }

    @GetMapping("/accounting/repair-market-ids/by-positions/{triggerId}")
    public ResponseEntity<Map<String, Object>> repairMarketIdsByPositionsStatus(@PathVariable String triggerId) {
        return triggerStatusResponse(triggerId, StrategyManualTriggerAction.SMART_WALLET_ACCOUNTING_REPAIR_MARKET_IDS_BY_POSITIONS);
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
