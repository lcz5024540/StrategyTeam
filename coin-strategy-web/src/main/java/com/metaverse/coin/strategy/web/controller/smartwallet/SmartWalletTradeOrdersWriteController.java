package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerAction;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerStatus;
import com.metaverse.coin.strategy.web.service.trigger.ManualTriggerDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SmartWallet trade-orders / markets 写接口（命令模式，同步等待短任务）：
 *
 * <ul>
 *   <li>POST /smart-wallet/trade-orders/backfill — 重建订单记录</li>
 *   <li>POST /smart-wallet/trade-orders/repair-stale — 修复滞留订单</li>
 *   <li>POST /smart-wallet/markets/recategorize — 重新分类市场</li>
 * </ul>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletTradeOrdersWriteController {

    private final ManualTriggerDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public SmartWalletTradeOrdersWriteController(ManualTriggerDispatcher dispatcher,
                                                 ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/trade-orders/backfill")
    public ResponseEntity<JsonNode> backfillTradeOrders(@RequestParam(defaultValue = "10000") Integer limit)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit", limit);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_TRADE_ORDERS_BACKFILL,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/trade-orders/repair-stale")
    public ResponseEntity<JsonNode> repairStaleTradeOrders() throws InterruptedException {
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_TRADE_ORDERS_REPAIR_STALE,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                Map.of(),
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/markets/recategorize")
    public ResponseEntity<JsonNode> recategorizeMarkets() throws InterruptedException {
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_MARKETS_RECATEGORIZE,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                Map.of(),
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
