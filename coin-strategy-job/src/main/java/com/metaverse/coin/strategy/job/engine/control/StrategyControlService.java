package com.metaverse.coin.strategy.job.engine.control;

import com.metaverse.coin.strategy.job.engine.PolymarketStrategy;
import com.metaverse.coin.strategy.job.engine.StrategyMode;
import com.metaverse.coin.strategy.job.engine.StrategyRegistry;
import com.metaverse.coin.strategy.job.engine.model.StrategyCycleResult;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.engine.monitor.StrategyAlertService;
import com.metaverse.coin.strategy.job.engine.control.model.ControlAction;
import com.metaverse.coin.strategy.job.engine.control.model.ControlResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 策略控制服务。
 *
 * <p>提供策略的运行时控制能力：</p>
 * <ul>
 *   <li>启用/禁用策略</li>
 *   <li>切换运行模式（SHADOW / PAPER / LIVE）</li>
 *   <li>手动触发 runCycle</li>
 *   <li>紧急熔断 KILL_SWITCH（全量停止所有策略）</li>
 * </ul>
 *
 * <p>注意：当前实现通过内存状态控制。策略的 enabled/mode 本身由各策略的
 * ConfigurationProperties 管理，此服务提供的是运行时覆盖层。
 * KILL_SWITCH 是全局原子开关，一旦激活，所有策略的 runCycle 应检查此标志。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyControlService {

    private static final String LOG_PREFIX = "[STRATEGY_CONTROL]";

    private final StrategyRegistry strategyRegistry;
    private final StrategyAlertService alertService;

    /** 全局熔断开关 */
    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);

    /** 熔断激活时间 */
    private volatile Instant killSwitchActivatedAt = null;

    /** 熔断原因 */
    private volatile String killSwitchReason = null;

    // ── 熔断控制 ──────────────────────────────────────────────────────

    /**
     * 激活紧急熔断。
     * 所有策略应在 runCycle 前检查 {@link #isKillSwitchActive()}。
     *
     * @param reason 熔断原因
     * @return 控制结果
     */
    public ControlResult activateKillSwitch(String reason) {
        killSwitchActive.set(true);
        killSwitchActivatedAt = Instant.now();
        killSwitchReason = reason;

        log.error("{} ⚠⚠⚠ 紧急熔断已激活: {} ⚠⚠⚠", LOG_PREFIX, reason);

        // 记录熔断告警
        alertService.fireAlert("ALL", AlertType.KILL_SWITCH,
                "紧急熔断已激活", reason);

        return ControlResult.builder()
                .success(true)
                .action(ControlAction.KILL_SWITCH_ACTIVATE)
                .message("紧急熔断已激活: " + reason)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 解除紧急熔断。
     *
     * @return 控制结果
     */
    public ControlResult deactivateKillSwitch() {
        killSwitchActive.set(false);
        String previousReason = killSwitchReason;
        killSwitchReason = null;
        killSwitchActivatedAt = null;

        log.info("{} 紧急熔断已解除（之前原因: {}）", LOG_PREFIX, previousReason);

        // 自动恢复熔断告警
        alertService.autoResolveByStrategy("ALL", AlertType.KILL_SWITCH);

        return ControlResult.builder()
                .success(true)
                .action(ControlAction.KILL_SWITCH_DEACTIVATE)
                .message("紧急熔断已解除")
                .timestamp(Instant.now())
                .build();
    }

    /**
     * 查询熔断是否激活。
     * 策略的 runCycle 方法应在开头调用此方法检查。
     */
    public boolean isKillSwitchActive() {
        return killSwitchActive.get();
    }

    public Instant getKillSwitchActivatedAt() {
        return killSwitchActivatedAt;
    }

    public String getKillSwitchReason() {
        return killSwitchReason;
    }

    // ── 手动触发 ──────────────────────────────────────────────────────

    /**
     * 手动触发指定策略的 runCycle。
     *
     * @param strategyId 策略 ID
     * @return 控制结果
     */
    public ControlResult manualRunCycle(String strategyId) {
        if (isKillSwitchActive()) {
            return ControlResult.builder()
                    .success(false)
                    .action(ControlAction.MANUAL_RUN_CYCLE)
                    .strategyId(strategyId)
                    .message("熔断开关已激活，拒绝执行: " + killSwitchReason)
                    .timestamp(Instant.now())
                    .build();
        }

        PolymarketStrategy strategy = strategyRegistry.getStrategy(strategyId);
        if (strategy == null) {
            return ControlResult.builder()
                    .success(false)
                    .action(ControlAction.MANUAL_RUN_CYCLE)
                    .strategyId(strategyId)
                    .message("策略不存在: " + strategyId)
                    .timestamp(Instant.now())
                    .build();
        }

        if (!strategy.isEnabled() || !strategy.getMode().isActive()) {
            return ControlResult.builder()
                    .success(false)
                    .action(ControlAction.MANUAL_RUN_CYCLE)
                    .strategyId(strategyId)
                    .message(String.format("策略未启用或模式为OFF: enabled=%s mode=%s",
                            strategy.isEnabled(), strategy.getMode()))
                    .timestamp(Instant.now())
                    .build();
        }

        try {
            log.info("{} 手动触发 runCycle: strategy={}", LOG_PREFIX, strategyId);
            StrategyCycleResult cycleResult = strategy.runCycle();

            return ControlResult.builder()
                    .success(true)
                    .action(ControlAction.MANUAL_RUN_CYCLE)
                    .strategyId(strategyId)
                    .message(String.format("runCycle 完成: signals=%d orders=%d errors=%d",
                            cycleResult.getSignalsFound(), cycleResult.getOrdersPlaced(), cycleResult.getErrors()))
                    .timestamp(Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("{} 手动触发 runCycle 异常: strategy={} error={}", LOG_PREFIX, strategyId, e.getMessage(), e);
            return ControlResult.builder()
                    .success(false)
                    .action(ControlAction.MANUAL_RUN_CYCLE)
                    .strategyId(strategyId)
                    .message("runCycle 异常: " + e.getMessage())
                    .timestamp(Instant.now())
                    .build();
        }
    }

    /**
     * 手动触发指定策略的 runExitScan。
     */
    public ControlResult manualRunExitScan(String strategyId) {
        PolymarketStrategy strategy = strategyRegistry.getStrategy(strategyId);
        if (strategy == null) {
            return ControlResult.builder()
                    .success(false)
                    .action(ControlAction.MANUAL_EXIT_SCAN)
                    .strategyId(strategyId)
                    .message("策略不存在: " + strategyId)
                    .timestamp(Instant.now())
                    .build();
        }

        try {
            log.info("{} 手动触发 runExitScan: strategy={}", LOG_PREFIX, strategyId);
            strategy.runExitScan();

            return ControlResult.builder()
                    .success(true)
                    .action(ControlAction.MANUAL_EXIT_SCAN)
                    .strategyId(strategyId)
                    .message("runExitScan 完成")
                    .timestamp(Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("{} 手动触发 runExitScan 异常: strategy={}", LOG_PREFIX, strategyId, e);
            return ControlResult.builder()
                    .success(false)
                    .action(ControlAction.MANUAL_EXIT_SCAN)
                    .strategyId(strategyId)
                    .message("runExitScan 异常: " + e.getMessage())
                    .timestamp(Instant.now())
                    .build();
        }
    }

    // ── 批量操作 ──────────────────────────────────────────────────────

    /**
     * 列出所有策略的控制状态。
     */
    public List<ControlResult> listStrategyStatus() {
        List<ControlResult> results = new ArrayList<>();
        for (PolymarketStrategy strategy : strategyRegistry.listAll()) {
            results.add(ControlResult.builder()
                    .success(true)
                    .action(ControlAction.STATUS_QUERY)
                    .strategyId(strategy.getStrategyId())
                    .message(String.format("enabled=%s mode=%s killSwitch=%s",
                            strategy.isEnabled(), strategy.getMode(), isKillSwitchActive()))
                    .timestamp(Instant.now())
                    .build());
        }
        return results;
    }
}
