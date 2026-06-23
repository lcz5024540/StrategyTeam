package com.metaverse.coin.strategy.web.controller.engine;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyHealthSnapshotEntity;
import com.metaverse.coin.strategy.web.model.engine.ControlResult;
import com.metaverse.coin.strategy.web.model.engine.DashboardOverview;
import com.metaverse.coin.strategy.web.model.engine.StrategyDetailView;
import com.metaverse.coin.strategy.web.service.engine.StrategyEngineControlService;
import com.metaverse.coin.strategy.web.service.engine.StrategyEngineMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略监控 REST API（迁移自 job 模块 StrategyMonitorController）。
 *
 * <p>Base path: /strategy-engine/monitor</p>
 *
 * <p>手动触发（alert-check / snapshot-collect）改为派发到 strategy_manual_trigger，
 * 同步返回 triggerId；job 端 consumer 异步消费。</p>
 */
@RestController
@RequestMapping("/strategy-engine/monitor")
@RequiredArgsConstructor
public class StrategyEngineMonitorController {

    private final StrategyEngineMonitorService monitorService;
    private final StrategyEngineControlService controlService;

    // ── Dashboard ──────────────────────────────────────────────────────

    @GetMapping("/overview")
    public ResponseEntity<DashboardOverview> getOverview() {
        return ResponseEntity.ok(monitorService.getDashboardOverview());
    }

    @GetMapping("/strategies/{strategyId}")
    public ResponseEntity<?> getStrategyDetail(@PathVariable String strategyId) {
        StrategyDetailView detail = monitorService.getStrategyDetail(strategyId);
        if (detail == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "策略不存在: " + strategyId);
            return ResponseEntity.status(404).body(error);
        }
        return ResponseEntity.ok(detail);
    }

    // ── 告警 ──────────────────────────────────────────────────────────

    @GetMapping("/alerts/active")
    public ResponseEntity<List<StrategyAlertEntity>> getActiveAlerts() {
        return ResponseEntity.ok(monitorService.getActiveAlerts());
    }

    @GetMapping("/alerts/history")
    public ResponseEntity<List<StrategyAlertEntity>> getAlertHistory(
            @RequestParam(required = false) String strategyId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(monitorService.getAlertHistory(strategyId, limit));
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(@PathVariable Long alertId) {
        return ResponseEntity.ok(monitorService.acknowledgeAlert(alertId));
    }

    @PostMapping("/alerts/{alertId}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAlert(@PathVariable Long alertId) {
        return ResponseEntity.ok(monitorService.resolveAlert(alertId));
    }

    // ── 快照 ──────────────────────────────────────────────────────────

    @GetMapping("/snapshots")
    public ResponseEntity<List<StrategyHealthSnapshotEntity>> getRecentSnapshots(
            @RequestParam(required = false) String strategyId,
            @RequestParam(defaultValue = "60") int limit) {
        return ResponseEntity.ok(monitorService.getRecentSnapshots(strategyId, limit));
    }

    // ── 手动操作（异步派发） ──────────────────────────────────────────

    @PostMapping("/alert-check")
    public ResponseEntity<Map<String, Object>> manualAlertCheck() {
        ControlResult result = controlService.dispatchAlertCheck();
        return ResponseEntity.ok(toAsyncResponse(result));
    }

    @PostMapping("/snapshot-collect")
    public ResponseEntity<Map<String, Object>> manualSnapshotCollect() {
        ControlResult result = controlService.dispatchSnapshotCollect();
        return ResponseEntity.ok(toAsyncResponse(result));
    }

    private Map<String, Object> toAsyncResponse(ControlResult result) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", result.isSuccess());
        resp.put("message", result.getMessage());
        resp.put("triggerId", result.getTriggerId());
        return resp;
    }
}
