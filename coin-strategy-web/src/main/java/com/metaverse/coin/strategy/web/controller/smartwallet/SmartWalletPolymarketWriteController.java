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
 * SmartWallet polymarket 写接口（命令模式，同步等待短任务）：
 *
 * <ul>
 *   <li>POST /smart-wallet/polymarket/select — 刷新观察名单</li>
 *   <li>POST /smart-wallet/polymarket/analyze — 分析 Top 钱包</li>
 *   <li>POST /smart-wallet/polymarket/watchlist/admit — 单地址 watchlist 准入</li>
 *   <li>POST /smart-wallet/polymarket/sync-events — 同步观察名单事件</li>
 *   <li>POST /smart-wallet/polymarket/closed-positions/backfill-local — 本地 closed-position 回灌</li>
 *   <li>POST /smart-wallet/polymarket/market-data/collect-snapshot — 手动采集市场快照</li>
 * </ul>
 */
@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletPolymarketWriteController {

    private final ManualTriggerDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public SmartWalletPolymarketWriteController(ManualTriggerDispatcher dispatcher,
                                                ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/polymarket/select")
    public ResponseEntity<JsonNode> refreshSelection() throws InterruptedException {
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_SELECT,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                Map.of(),
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/polymarket/analyze")
    public ResponseEntity<JsonNode> analyzeTopWallets() throws InterruptedException {
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_ANALYZE,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                Map.of(),
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/polymarket/watchlist/admit")
    public ResponseEntity<JsonNode> admitWalletToWatchlist(@RequestParam String walletAddress)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("walletAddress", walletAddress);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_WATCHLIST_ADMIT,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/polymarket/sync-events")
    public ResponseEntity<JsonNode> syncWatchlistEvents() throws InterruptedException {
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_SYNC_EVENTS,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                Map.of(),
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/polymarket/closed-positions/backfill-local")
    public ResponseEntity<JsonNode> backfillLocalClosedPositions(
            @RequestParam(defaultValue = "/tmp/poly_api") String directory,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "false") boolean dryRun) throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("directory", directory);
        if (limit != null) payload.put("limit", limit);
        payload.put("dryRun", dryRun);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_CLOSED_POSITIONS_BACKFILL_LOCAL,
                StrategyManualTriggerAction.GLOBAL_STRATEGY_ID,
                payload,
                ManualTriggerDispatcher.DEFAULT_MAX_WAIT_MS));
    }

    @PostMapping("/polymarket/market-data/collect-snapshot")
    public ResponseEntity<JsonNode> collectDiagnosticMarketSnapshot(@RequestParam String marketId,
                                                                    @RequestParam(defaultValue = "YES") String outcome)
            throws InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("marketId", marketId);
        payload.put("outcome", outcome);
        return toJsonResponse(dispatcher.dispatchAndWait(
                StrategyManualTriggerAction.SMART_WALLET_POLYMARKET_MARKET_DATA_COLLECT_SNAPSHOT,
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
