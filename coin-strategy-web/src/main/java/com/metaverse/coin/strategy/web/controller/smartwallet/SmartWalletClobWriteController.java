package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerAction;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerStatus;
import com.metaverse.coin.strategy.web.service.trigger.ManualTriggerDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SmartWallet clob 写接口（命令模式，同步等待短任务）：
 *
 * <ul>
 *   <li>POST /smart-wallet/clob/markets/{conditionId}/snapshot — 采集市场快照</li>
 *   <li>POST /smart-wallet/clob/markets/snapshots/backfill — 批量采集快照</li>
 * </ul>
 *
 * <p>note: POST /clob/dry-run、POST /clob/orders/{orderId}/cancel 涉及外部 HTTP 调用，
 * 暂留 SmartWalletController；前端通过 SmartWalletWriteProxyController 透传。</p>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletClobWriteController {

    private final ManualTriggerDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public SmartWalletClobWriteController(ManualTriggerDispatcher dispatcher,
                                          ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/clob/markets/{conditionId}/snapshot")
    public ResponseEntity<JsonNode> collectMarketSnapshot(@PathVariable String conditionId,
                                                          @RequestParam(defaultValue = "YES") String outcome)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conditionId", conditionId);
        payload.put("outcome", outcome);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_CLOB_MARKET_SNAPSHOT,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/clob/markets/snapshots/backfill")
    public ResponseEntity<JsonNode> collectRecentMarketSnapshots(@RequestParam(defaultValue = "20") Integer limit)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_CLOB_MARKETS_SNAPSHOTS_BACKFILL,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    // ── 工具方法 ────────────────────────────────────────────────────

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
                // fallback below
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
