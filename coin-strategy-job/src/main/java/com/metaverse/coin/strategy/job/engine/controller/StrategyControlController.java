package com.metaverse.coin.strategy.job.engine.controller;

import com.metaverse.coin.strategy.job.engine.control.StrategyControlService;
import com.metaverse.coin.strategy.job.engine.control.model.ControlResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略控制 REST API。
 *
 * <p>提供策略运行时控制能力：熔断、手动触发等。</p>
 *
 * <p>Base path: /strategy-engine/control</p>
 */
@RestController
@RequestMapping("/strategy-engine/control")
@RequiredArgsConstructor
public class StrategyControlController {

    private final StrategyControlService controlService;

    // ── 熔断控制 ──────────────────────────────────────────────────────

    /**
     * 获取熔断状态。
     *
     * GET /strategy-engine/control/kill-switch
     */
    @GetMapping("/kill-switch")
    public ResponseEntity<Map<String, Object>> getKillSwitchStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("active", controlService.isKillSwitchActive());
        result.put("activatedAt", controlService.getKillSwitchActivatedAt());
        result.put("reason", controlService.getKillSwitchReason());
        return ResponseEntity.ok(result);
    }

    /**
     * 激活紧急熔断。
     *
     * POST /strategy-engine/control/kill-switch/activate
     * Body: { "reason": "发现异常" }
     */
    @PostMapping("/kill-switch/activate")
    public ResponseEntity<ControlResult> activateKillSwitch(@RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "手动触发熔断");
        return ResponseEntity.ok(controlService.activateKillSwitch(reason));
    }

    /**
     * 解除紧急熔断。
     *
     * POST /strategy-engine/control/kill-switch/deactivate
     */
    @PostMapping("/kill-switch/deactivate")
    public ResponseEntity<ControlResult> deactivateKillSwitch() {
        return ResponseEntity.ok(controlService.deactivateKillSwitch());
    }

    // ── 手动触发 ──────────────────────────────────────────────────────

    /**
     * 手动触发指定策略的 runCycle。
     *
     * POST /strategy-engine/control/strategies/{strategyId}/run-cycle
     */
    @PostMapping("/strategies/{strategyId}/run-cycle")
    public ResponseEntity<ControlResult> manualRunCycle(@PathVariable String strategyId) {
        return ResponseEntity.ok(controlService.manualRunCycle(strategyId));
    }

    /**
     * 手动触发指定策略的 runExitScan。
     *
     * POST /strategy-engine/control/strategies/{strategyId}/exit-scan
     */
    @PostMapping("/strategies/{strategyId}/exit-scan")
    public ResponseEntity<ControlResult> manualRunExitScan(@PathVariable String strategyId) {
        return ResponseEntity.ok(controlService.manualRunExitScan(strategyId));
    }

    // ── 状态查询 ──────────────────────────────────────────────────────

    /**
     * 列出所有策略的控制状态。
     *
     * GET /strategy-engine/control/status
     */
    @GetMapping("/status")
    public ResponseEntity<List<ControlResult>> listStrategyStatus() {
        return ResponseEntity.ok(controlService.listStrategyStatus());
    }
}
