package com.metaverse.coin.strategy.web.controller.engine;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerEntity;
import com.metaverse.coin.strategy.web.model.engine.ControlResult;
import com.metaverse.coin.strategy.web.service.engine.StrategyEngineControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略控制 REST API（迁移自 job 模块 StrategyControlController）。
 *
 * <p>Base path: /strategy-engine/control</p>
 *
 * <p>异步命令（run-cycle / exit-scan）改为派发到 strategy_manual_trigger，
 * 同步返回 triggerId；job 端 consumer 异步消费。</p>
 */
@RestController
@RequestMapping("/strategy-engine/control")
@RequiredArgsConstructor
public class StrategyEngineControlController {

    private final StrategyEngineControlService controlService;

    // ── 熔断控制 ──────────────────────────────────────────────────────

    @GetMapping("/kill-switch")
    public ResponseEntity<Map<String, Object>> getKillSwitchStatus() {
        return ResponseEntity.ok(controlService.getKillSwitchStatus());
    }

    @PostMapping("/kill-switch/activate")
    public ResponseEntity<ControlResult> activateKillSwitch(@RequestBody(required = false) Map<String, String> body) {
        String reason = (body != null && body.get("reason") != null) ? body.get("reason") : "手动触发熔断";
        return ResponseEntity.ok(controlService.activateKillSwitch(reason));
    }

    @PostMapping("/kill-switch/deactivate")
    public ResponseEntity<ControlResult> deactivateKillSwitch() {
        return ResponseEntity.ok(controlService.deactivateKillSwitch());
    }

    // ── 手动触发（异步派发） ──────────────────────────────────────────

    @PostMapping("/strategies/{strategyId}/run-cycle")
    public ResponseEntity<ControlResult> manualRunCycle(@PathVariable String strategyId) {
        return ResponseEntity.ok(controlService.dispatchRunCycle(strategyId));
    }

    @PostMapping("/strategies/{strategyId}/exit-scan")
    public ResponseEntity<ControlResult> manualRunExitScan(@PathVariable String strategyId) {
        return ResponseEntity.ok(controlService.dispatchExitScan(strategyId));
    }

    // ── 状态查询 ──────────────────────────────────────────────────────

    @GetMapping("/status")
    public ResponseEntity<List<ControlResult>> listStrategyStatus() {
        return ResponseEntity.ok(controlService.listStrategyStatus());
    }

    // ── 命令结果查询（新增可选接口） ──────────────────────────────────

    @GetMapping("/triggers/{id}")
    public ResponseEntity<?> getTrigger(@PathVariable Long id) {
        StrategyManualTriggerEntity entity = controlService.getTrigger(id);
        if (entity == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "trigger 不存在: " + id);
            return ResponseEntity.status(404).body(error);
        }
        return ResponseEntity.ok(entity);
    }
}
