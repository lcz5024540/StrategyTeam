package com.metaverse.coin.strategy.job.engine.controller;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyHealthSnapshotEntity;
import com.metaverse.coin.strategy.job.engine.monitor.HealthSnapshotScheduler;
import com.metaverse.coin.strategy.job.engine.monitor.StrategyAlertService;
import com.metaverse.coin.strategy.job.engine.monitor.StrategyReportService;
import com.metaverse.coin.strategy.job.engine.monitor.model.DashboardOverview;
import com.metaverse.coin.strategy.job.engine.monitor.model.StrategyDetailView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略监控 REST API。
 *
 * <p>提供 Dashboard 概览、策略详情、告警管理、快照历史等查询接口。</p>
 *
 * <p>Base path: /strategy-engine/monitor</p>
 */
@RestController
@RequestMapping("/strategy-engine/monitor")
@RequiredArgsConstructor
public class StrategyMonitorController {

    private final StrategyReportService reportService;
    private final StrategyAlertService alertService;
    private final HealthSnapshotScheduler snapshotScheduler;

    // ── Dashboard ──────────────────────────────────────────────────────

    /**
     * 获取全局 Dashboard 概览。
     *
     * GET /strategy-engine/monitor/overview
     */
    @GetMapping("/overview")
    public ResponseEntity<DashboardOverview> getOverview() {
        return ResponseEntity.ok(reportService.getDashboardOverview());
    }

    /**
     * 获取单策略详情。
     *
     * GET /strategy-engine/monitor/strategies/{strategyId}
     */
    @GetMapping("/strategies/{strategyId}")
    public ResponseEntity<?> getStrategyDetail(@PathVariable String strategyId) {
        StrategyDetailView detail = reportService.getStrategyDetail(strategyId);
        if (detail == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "策略不存在: " + strategyId);
            return ResponseEntity.status(404).body(error);
        }
        return ResponseEntity.ok(detail);
    }

    // ── 告警 ──────────────────────────────────────────────────────────

    /**
     * 获取活跃告警列表（未恢复）。
     *
     * GET /strategy-engine/monitor/alerts/active
     */
    @GetMapping("/alerts/active")
    public ResponseEntity<List<StrategyAlertEntity>> getActiveAlerts() {
        return ResponseEntity.ok(alertService.getActiveAlerts());
    }

    /**
     * 获取告警历史。
     *
     * GET /strategy-engine/monitor/alerts/history?strategyId=xxx&limit=50
     */
    @GetMapping("/alerts/history")
    public ResponseEntity<List<StrategyAlertEntity>> getAlertHistory(
            @RequestParam(required = false) String strategyId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(alertService.getAlertHistory(strategyId, limit));
    }

    /**
     * 确认告警。
     *
     * POST /strategy-engine/monitor/alerts/{alertId}/acknowledge
     */
    @PostMapping("/alerts/{alertId}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(@PathVariable Long alertId) {
        alertService.acknowledgeAlert(alertId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "告警已确认");
        return ResponseEntity.ok(result);
    }

    /**
     * 标记告警已恢复。
     *
     * POST /strategy-engine/monitor/alerts/{alertId}/resolve
     */
    @PostMapping("/alerts/{alertId}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAlert(@PathVariable Long alertId) {
        alertService.resolveAlert(alertId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "告警已恢复");
        return ResponseEntity.ok(result);
    }

    // ── 快照 ──────────────────────────────────────────────────────────

    /**
     * 获取最近健康快照。
     *
     * GET /strategy-engine/monitor/snapshots?strategyId=xxx&limit=60
     */
    @GetMapping("/snapshots")
    public ResponseEntity<List<StrategyHealthSnapshotEntity>> getRecentSnapshots(
            @RequestParam(required = false) String strategyId,
            @RequestParam(defaultValue = "60") int limit) {
        return ResponseEntity.ok(snapshotScheduler.getRecentSnapshots(strategyId, limit));
    }

    // ── 手动操作 ──────────────────────────────────────────────────────

    /**
     * 手动触发一次告警检查。
     *
     * POST /strategy-engine/monitor/alert-check
     */
    @PostMapping("/alert-check")
    public ResponseEntity<Map<String, Object>> manualAlertCheck() {
        int alerts = alertService.runAlertCheck();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("alertsFound", alerts);
        return ResponseEntity.ok(result);
    }

    /**
     * 手动触发一次快照采集。
     *
     * POST /strategy-engine/monitor/snapshot-collect
     */
    @PostMapping("/snapshot-collect")
    public ResponseEntity<Map<String, Object>> manualSnapshotCollect() {
        int collected = snapshotScheduler.collectSnapshots();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("snapshotsCollected", collected);
        return ResponseEntity.ok(result);
    }
}
