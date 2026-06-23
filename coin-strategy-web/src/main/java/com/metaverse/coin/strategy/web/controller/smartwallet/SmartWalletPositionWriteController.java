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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SmartWallet position 写接口（命令模式，同步等待）：
 *
 * <ul>
 *   <li>POST /smart-wallet/positions/{positionId}/claim — 单笔领取，同步等待 ≤60s</li>
 *   <li>POST /smart-wallet/positions/{positionId}/exit — 单笔提前退出（PAPER），同步等待 ≤60s</li>
 * </ul>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletPositionWriteController {

    private final ManualTriggerDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public SmartWalletPositionWriteController(ManualTriggerDispatcher dispatcher,
                                              ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/positions/{positionId}/claim")
    public ResponseEntity<JsonNode> claimPosition(@PathVariable Long positionId,
                                                  @RequestParam(defaultValue = "false") boolean confirmExternalClaim)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("positionId", positionId);
        payload.put("confirmExternalClaim", confirmExternalClaim);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_POSITION_CLAIM,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/positions/{positionId}/exit")
    public ResponseEntity<JsonNode> exitPosition(@PathVariable Long positionId,
                                                 @RequestBody(required = false) Map<String, Object> request)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("positionId", positionId);
        payload.put("request", request == null ? Map.of() : request);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_POSITION_EXIT,
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
