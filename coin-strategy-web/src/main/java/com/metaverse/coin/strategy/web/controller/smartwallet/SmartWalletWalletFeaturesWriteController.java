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
 * SmartWallet wallet-features 写接口（命令模式，同步等待短任务）：
 *
 * <ul>
 *   <li>POST /smart-wallet/wallet-features/daily — 单钱包日特征回填</li>
 *   <li>POST /smart-wallet/wallet-features/daily/backfill — 批量回填</li>
 * </ul>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletWalletFeaturesWriteController {

    private final ManualTriggerDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public SmartWalletWalletFeaturesWriteController(ManualTriggerDispatcher dispatcher,
                                                    ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/wallet-features/daily")
    public ResponseEntity<JsonNode> buildWalletFeatureDaily(@RequestParam String walletAddress,
                                                            @RequestParam(required = false) String featureDate)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("walletAddress", walletAddress);
        if (featureDate != null && !featureDate.isBlank()) {
            payload.put("featureDate", featureDate);
        }
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_WALLET_FEATURES_DAILY,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/wallet-features/daily/backfill")
    public ResponseEntity<JsonNode> buildRecentWalletFeatureDaily(
            @RequestParam(defaultValue = "30") Integer walletLimit,
            @RequestParam(defaultValue = "1") Integer days,
            @RequestParam(defaultValue = "true") boolean missingOnly) throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("walletLimit", walletLimit);
        payload.put("days", days);
        payload.put("missingOnly", missingOnly);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_WALLET_FEATURES_DAILY_BACKFILL,
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
