package com.metaverse.coin.strategy.web.service.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyAlertMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyEngineFlagMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyManualTriggerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyRegistryStateMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyEngineFlagEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerAction;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyManualTriggerStatus;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRegistryStateEntity;
import com.metaverse.coin.strategy.web.model.engine.ControlAction;
import com.metaverse.coin.strategy.web.model.engine.ControlResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略引擎控制服务（web 侧）。
 *
 * <p>所有 /strategy-engine/control/** 接口的业务实现：</p>
 * <ul>
 *   <li>kill-switch：直接 UPSERT/UPDATE strategy_engine_flag + 写 strategy_alert</li>
 *   <li>策略状态：读 strategy_registry_state + 拼 kill-switch 状态</li>
 *   <li>异步命令（run-cycle/exit-scan）：写 strategy_manual_trigger 由 job 端消费</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyEngineControlService {

    private static final String LOG_PREFIX = "[ENGINE_CONTROL]";

    /** kill_switch 标志位 key */
    public static final String FLAG_KILL_SWITCH = "kill_switch";

    /** kill_switch 激活值 */
    public static final String VALUE_ACTIVE = "ACTIVE";

    /** kill_switch 失活值 */
    public static final String VALUE_INACTIVE = "INACTIVE";

    /** kill_switch 对应的 alert 类型（与 job 端 AlertType.KILL_SWITCH 字符串保持一致） */
    private static final String ALERT_TYPE_KILL_SWITCH = "KILL_SWITCH";

    /** kill_switch 对应的告警级别（与 job 端 AlertLevel.FATAL 字符串保持一致） */
    private static final String ALERT_LEVEL_FATAL = "FATAL";

    private final StrategyEngineFlagMapper flagMapper;
    private final StrategyManualTriggerMapper triggerMapper;
    private final StrategyRegistryStateMapper registryStateMapper;
    private final StrategyAlertMapper alertMapper;

    // ── 熔断控制 ──────────────────────────────────────────────────────

    public Map<String, Object> getKillSwitchStatus() {
        StrategyEngineFlagEntity flag = findFlag(FLAG_KILL_SWITCH);
        boolean active = flag != null && VALUE_ACTIVE.equalsIgnoreCase(flag.getFlagValue());
        Map<String, Object> result = new HashMap<>();
        result.put("active", active);
        result.put("activatedAt", active && flag.getActivatedAt() != null
                ? flag.getActivatedAt().toInstant() : null);
        result.put("reason", active ? flag.getReason() : null);
        return result;
    }

    public ControlResult activateKillSwitch(String reason) {
        Date now = new Date();
        StrategyEngineFlagEntity existing = findFlag(FLAG_KILL_SWITCH);
        if (existing == null) {
            StrategyEngineFlagEntity entity = new StrategyEngineFlagEntity()
                    .setFlagKey(FLAG_KILL_SWITCH)
                    .setFlagValue(VALUE_ACTIVE)
                    .setReason(reason)
                    .setActivatedAt(now)
                    .setUpdatedAt(now);
            flagMapper.insert(entity);
        } else {
            existing.setFlagValue(VALUE_ACTIVE)
                    .setReason(reason)
                    .setActivatedAt(now)
                    .setUpdatedAt(now);
            flagMapper.updateById(existing);
        }
        log.error("{} 紧急熔断已激活: {}", LOG_PREFIX, reason);

        // 写 strategy_alert（带去重，避免反复点 activate 时刷屏）
        Long existingAlertCount = alertMapper.selectCount(new LambdaQueryWrapper<StrategyAlertEntity>()
                .eq(StrategyAlertEntity::getStrategyId, "ALL")
                .eq(StrategyAlertEntity::getAlertType, ALERT_TYPE_KILL_SWITCH)
                .eq(StrategyAlertEntity::getResolved, false));
        if (existingAlertCount == null || existingAlertCount == 0) {
            StrategyAlertEntity alert = new StrategyAlertEntity()
                    .setStrategyId("ALL")
                    .setAlertLevel(ALERT_LEVEL_FATAL)
                    .setAlertType(ALERT_TYPE_KILL_SWITCH)
                    .setTitle("紧急熔断已激活")
                    .setMessage(reason)
                    .setAcknowledged(false)
                    .setResolved(false)
                    .setCreatedAt(now);
            alertMapper.insert(alert);
        }

        return ControlResult.builder()
                .success(true)
                .action(ControlAction.KILL_SWITCH_ACTIVATE)
                .message("紧急熔断已激活: " + reason)
                .timestamp(Instant.now())
                .build();
    }

    public ControlResult deactivateKillSwitch() {
        Date now = new Date();
        StrategyEngineFlagEntity existing = findFlag(FLAG_KILL_SWITCH);
        if (existing != null) {
            existing.setFlagValue(VALUE_INACTIVE)
                    .setActivatedAt(null)
                    .setReason(null)
                    .setUpdatedAt(now);
            flagMapper.updateById(existing);
        }
        log.info("{} 紧急熔断已解除", LOG_PREFIX);

        // 自动 resolve 未恢复的 KILL_SWITCH 告警
        alertMapper.update(null, new LambdaUpdateWrapper<StrategyAlertEntity>()
                .eq(StrategyAlertEntity::getStrategyId, "ALL")
                .eq(StrategyAlertEntity::getAlertType, ALERT_TYPE_KILL_SWITCH)
                .eq(StrategyAlertEntity::getResolved, false)
                .set(StrategyAlertEntity::getResolved, true)
                .set(StrategyAlertEntity::getResolvedAt, now));

        return ControlResult.builder()
                .success(true)
                .action(ControlAction.KILL_SWITCH_DEACTIVATE)
                .message("紧急熔断已解除")
                .timestamp(Instant.now())
                .build();
    }

    // ── 异步命令派发 ──────────────────────────────────────────────────

    public ControlResult dispatchRunCycle(String strategyId) {
        return dispatchTrigger(strategyId, StrategyManualTriggerAction.RUN_CYCLE,
                ControlAction.MANUAL_RUN_CYCLE);
    }

    public ControlResult dispatchExitScan(String strategyId) {
        return dispatchTrigger(strategyId, StrategyManualTriggerAction.EXIT_SCAN,
                ControlAction.MANUAL_EXIT_SCAN);
    }

    public ControlResult dispatchAlertCheck() {
        return dispatchGlobalTrigger(StrategyManualTriggerAction.ALERT_CHECK,
                ControlAction.STATUS_QUERY, "已派发告警检查");
    }

    public ControlResult dispatchSnapshotCollect() {
        return dispatchGlobalTrigger(StrategyManualTriggerAction.SNAPSHOT_COLLECT,
                ControlAction.STATUS_QUERY, "已派发快照采集");
    }

    private ControlResult dispatchTrigger(String strategyId, String action, ControlAction controlAction) {
        StrategyManualTriggerEntity entity = new StrategyManualTriggerEntity()
                .setStrategyId(strategyId)
                .setAction(action)
                .setStatus(StrategyManualTriggerStatus.PENDING)
                .setRequestedAt(new Date());
        triggerMapper.insert(entity);
        log.info("{} 派发异步命令: action={} strategy={} triggerId={}",
                LOG_PREFIX, action, strategyId, entity.getId());
        return ControlResult.builder()
                .success(true)
                .action(controlAction)
                .strategyId(strategyId)
                .triggerId(entity.getId())
                .message("已派发 trigger=" + entity.getId())
                .timestamp(Instant.now())
                .build();
    }

    private ControlResult dispatchGlobalTrigger(String action, ControlAction controlAction, String prefix) {
        StrategyManualTriggerEntity entity = new StrategyManualTriggerEntity()
                .setStrategyId(StrategyManualTriggerAction.GLOBAL_STRATEGY_ID)
                .setAction(action)
                .setStatus(StrategyManualTriggerStatus.PENDING)
                .setRequestedAt(new Date());
        triggerMapper.insert(entity);
        log.info("{} 派发全局异步命令: action={} triggerId={}",
                LOG_PREFIX, action, entity.getId());
        return ControlResult.builder()
                .success(true)
                .action(controlAction)
                .triggerId(entity.getId())
                .message(prefix + ", trigger=" + entity.getId())
                .timestamp(Instant.now())
                .build();
    }

    public StrategyManualTriggerEntity getTrigger(Long triggerId) {
        return triggerMapper.selectById(triggerId);
    }

    // ── 状态查询 ──────────────────────────────────────────────────────

    public List<ControlResult> listStrategyStatus() {
        boolean killSwitchActive = isKillSwitchActive();
        List<StrategyRegistryStateEntity> states = registryStateMapper.selectList(
                new LambdaQueryWrapper<StrategyRegistryStateEntity>()
                        .orderByAsc(StrategyRegistryStateEntity::getStrategyId));
        List<ControlResult> results = new ArrayList<>(states.size());
        for (StrategyRegistryStateEntity state : states) {
            results.add(ControlResult.builder()
                    .success(true)
                    .action(ControlAction.STATUS_QUERY)
                    .strategyId(state.getStrategyId())
                    .message(String.format("enabled=%s mode=%s killSwitch=%s",
                            Boolean.TRUE.equals(state.getEnabled()),
                            state.getMode(),
                            killSwitchActive))
                    .timestamp(Instant.now())
                    .build());
        }
        return results;
    }

    public boolean isKillSwitchActive() {
        StrategyEngineFlagEntity flag = findFlag(FLAG_KILL_SWITCH);
        return flag != null && VALUE_ACTIVE.equalsIgnoreCase(flag.getFlagValue());
    }

    private StrategyEngineFlagEntity findFlag(String flagKey) {
        return flagMapper.selectOne(new LambdaQueryWrapper<StrategyEngineFlagEntity>()
                .eq(StrategyEngineFlagEntity::getFlagKey, flagKey)
                .last("LIMIT 1"));
    }
}
