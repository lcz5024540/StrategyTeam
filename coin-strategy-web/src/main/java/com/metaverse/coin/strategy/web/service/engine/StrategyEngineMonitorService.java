package com.metaverse.coin.strategy.web.service.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyAlertMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyHealthSnapshotMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyRegistryStateMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyRunLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyHealthSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRegistryStateEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRunLogEntity;
import com.metaverse.coin.strategy.web.model.engine.DashboardOverview;
import com.metaverse.coin.strategy.web.model.engine.StrategyDetailView;
import com.metaverse.coin.strategy.web.model.engine.StrategyStatusView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 策略引擎监控查询服务（web 侧）。
 *
 * <p>所有 /strategy-engine/monitor/** 的查询业务实现：</p>
 * <ul>
 *   <li>overview / strategy detail：拼装 strategy_registry_state + 最新 strategy_health_snapshot
 *       + strategy_alert + strategy_run_log</li>
 *   <li>告警查询/确认/恢复：直接操作 strategy_alert</li>
 *   <li>快照查询：直接读 strategy_health_snapshot</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyEngineMonitorService {

    private static final String LOG_PREFIX = "[ENGINE_MONITOR]";

    private final StrategyRegistryStateMapper registryStateMapper;
    private final StrategyAlertMapper alertMapper;
    private final StrategyRunLogMapper runLogMapper;
    private final StrategyHealthSnapshotMapper snapshotMapper;

    // ── Dashboard ──────────────────────────────────────────────────────

    public DashboardOverview getDashboardOverview() {
        List<StrategyRegistryStateEntity> states = registryStateMapper.selectList(
                new LambdaQueryWrapper<StrategyRegistryStateEntity>()
                        .orderByAsc(StrategyRegistryStateEntity::getStrategyId));

        int totalStrategies = states.size();
        int enabledStrategies = 0;
        int healthyCount = 0;
        int totalPositions = 0;
        BigDecimal totalExposure = BigDecimal.ZERO;
        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        List<StrategyStatusView> views = new ArrayList<>(totalStrategies);

        for (StrategyRegistryStateEntity state : states) {
            boolean enabled = Boolean.TRUE.equals(state.getEnabled());
            boolean modeActive = isModeActive(state.getMode());
            if (enabled && modeActive) {
                enabledStrategies++;
            }

            StrategyHealthSnapshotEntity latest = findLatestSnapshot(state.getStrategyId());
            boolean healthy = latest != null && Boolean.TRUE.equals(latest.getHealthy());
            int openPos = latest != null && latest.getOpenPositions() != null ? latest.getOpenPositions() : 0;
            BigDecimal exposure = latest != null && latest.getTotalExposureUsdc() != null
                    ? latest.getTotalExposureUsdc() : BigDecimal.ZERO;
            BigDecimal todayRealized = latest != null && latest.getTodayRealizedPnl() != null
                    ? latest.getTodayRealizedPnl() : BigDecimal.ZERO;
            BigDecimal unrealized = latest != null && latest.getUnrealizedPnl() != null
                    ? latest.getUnrealizedPnl() : BigDecimal.ZERO;

            if (healthy) healthyCount++;
            totalPositions += openPos;
            totalExposure = totalExposure.add(exposure);
            totalRealizedPnl = totalRealizedPnl.add(todayRealized);
            totalUnrealizedPnl = totalUnrealizedPnl.add(unrealized);

            views.add(StrategyStatusView.builder()
                    .strategyId(state.getStrategyId())
                    .displayName(state.getDisplayName())
                    .enabled(enabled)
                    .mode(state.getMode())
                    .healthy(healthy)
                    .openPositions(openPos)
                    .totalExposureUsdc(exposure)
                    .todayRealizedPnlUsdc(todayRealized)
                    .unrealizedPnlUsdc(unrealized)
                    .build());
        }

        Long activeAlerts = alertMapper.selectCount(
                new LambdaQueryWrapper<StrategyAlertEntity>()
                        .eq(StrategyAlertEntity::getResolved, false));

        Date todayStart = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        Long todayRuns = runLogMapper.selectCount(
                new LambdaQueryWrapper<StrategyRunLogEntity>()
                        .ge(StrategyRunLogEntity::getStartedAt, todayStart));

        return DashboardOverview.builder()
                .totalStrategies(totalStrategies)
                .enabledStrategies(enabledStrategies)
                .healthyStrategies(healthyCount)
                .totalOpenPositions(totalPositions)
                .totalExposureUsdc(totalExposure)
                .todayRealizedPnlUsdc(totalRealizedPnl)
                .unrealizedPnlUsdc(totalUnrealizedPnl)
                .activeAlerts(activeAlerts != null ? activeAlerts.intValue() : 0)
                .todayRunCount(todayRuns != null ? todayRuns.intValue() : 0)
                .strategies(views)
                .build();
    }

    public StrategyDetailView getStrategyDetail(String strategyId) {
        StrategyRegistryStateEntity state = registryStateMapper.selectOne(
                new LambdaQueryWrapper<StrategyRegistryStateEntity>()
                        .eq(StrategyRegistryStateEntity::getStrategyId, strategyId)
                        .last("LIMIT 1"));
        if (state == null) {
            return null;
        }

        StrategyHealthSnapshotEntity latest = findLatestSnapshot(strategyId);

        // 最近运行日志
        List<StrategyRunLogEntity> recentLogs = runLogMapper.selectList(
                new LambdaQueryWrapper<StrategyRunLogEntity>()
                        .eq(StrategyRunLogEntity::getStrategyId, strategyId)
                        .orderByDesc(StrategyRunLogEntity::getStartedAt)
                        .last("LIMIT 20"));

        // 今日运行统计
        Date todayStart = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        List<StrategyRunLogEntity> todayLogs = runLogMapper.selectList(
                new LambdaQueryWrapper<StrategyRunLogEntity>()
                        .eq(StrategyRunLogEntity::getStrategyId, strategyId)
                        .ge(StrategyRunLogEntity::getStartedAt, todayStart));
        int todayRuns = todayLogs.size();
        int todaySignals = todayLogs.stream().mapToInt(l -> l.getSignalsFound() != null ? l.getSignalsFound() : 0).sum();
        int todayOrders = todayLogs.stream().mapToInt(l -> l.getOrdersPlaced() != null ? l.getOrdersPlaced() : 0).sum();
        int todayErrors = todayLogs.stream().mapToInt(l -> l.getErrors() != null ? l.getErrors() : 0).sum();

        // 策略告警
        List<StrategyAlertEntity> alerts = alertMapper.selectList(
                new LambdaQueryWrapper<StrategyAlertEntity>()
                        .eq(StrategyAlertEntity::getStrategyId, strategyId)
                        .eq(StrategyAlertEntity::getResolved, false));

        return StrategyDetailView.builder()
                .strategyId(state.getStrategyId())
                .displayName(state.getDisplayName())
                .enabled(Boolean.TRUE.equals(state.getEnabled()))
                .mode(state.getMode())
                .walletId(state.getWalletId())
                .healthy(latest != null && Boolean.TRUE.equals(latest.getHealthy()))
                .openPositions(latest != null && latest.getOpenPositions() != null ? latest.getOpenPositions() : 0)
                .pendingOrders(latest != null && latest.getPendingOrders() != null ? latest.getPendingOrders() : 0)
                .walletBalanceUsdc(latest != null ? latest.getWalletBalanceUsdc() : null)
                .totalExposureUsdc(latest != null ? latest.getTotalExposureUsdc() : null)
                .todayRealizedPnlUsdc(latest != null ? latest.getTodayRealizedPnl() : null)
                .unrealizedPnlUsdc(latest != null ? latest.getUnrealizedPnl() : null)
                .notes(latest != null ? latest.getNotes() : null)
                .todayRunCount(todayRuns)
                .todaySignalsFound(todaySignals)
                .todayOrdersPlaced(todayOrders)
                .todayErrors(todayErrors)
                .activeAlerts(alerts)
                .recentLogs(recentLogs)
                .build();
    }

    // ── 告警 ──────────────────────────────────────────────────────────

    public List<StrategyAlertEntity> getActiveAlerts() {
        return alertMapper.selectList(
                new LambdaQueryWrapper<StrategyAlertEntity>()
                        .eq(StrategyAlertEntity::getResolved, false)
                        .orderByDesc(StrategyAlertEntity::getCreatedAt)
                        .last("LIMIT 100"));
    }

    public List<StrategyAlertEntity> getAlertHistory(String strategyId, int limit) {
        LambdaQueryWrapper<StrategyAlertEntity> wrapper = new LambdaQueryWrapper<StrategyAlertEntity>()
                .orderByDesc(StrategyAlertEntity::getCreatedAt)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 500));
        if (strategyId != null && !strategyId.isEmpty()) {
            wrapper.eq(StrategyAlertEntity::getStrategyId, strategyId);
        }
        return alertMapper.selectList(wrapper);
    }

    public Map<String, Object> acknowledgeAlert(Long alertId) {
        Map<String, Object> result = new HashMap<>();
        StrategyAlertEntity alert = alertMapper.selectById(alertId);
        if (alert == null) {
            result.put("success", false);
            result.put("message", "告警不存在: " + alertId);
            return result;
        }
        if (!Boolean.TRUE.equals(alert.getAcknowledged())) {
            alert.setAcknowledged(true);
            alert.setAcknowledgedAt(new Date());
            alertMapper.updateById(alert);
            log.info("{} 告警已确认: id={} type={}", LOG_PREFIX, alertId, alert.getAlertType());
        }
        result.put("success", true);
        result.put("message", "告警已确认");
        return result;
    }

    public Map<String, Object> resolveAlert(Long alertId) {
        Map<String, Object> result = new HashMap<>();
        StrategyAlertEntity alert = alertMapper.selectById(alertId);
        if (alert == null) {
            result.put("success", false);
            result.put("message", "告警不存在: " + alertId);
            return result;
        }
        if (!Boolean.TRUE.equals(alert.getResolved())) {
            alert.setResolved(true);
            alert.setResolvedAt(new Date());
            alertMapper.updateById(alert);
            log.info("{} 告警已恢复: id={} type={}", LOG_PREFIX, alertId, alert.getAlertType());
        }
        result.put("success", true);
        result.put("message", "告警已恢复");
        return result;
    }

    // ── 快照 ──────────────────────────────────────────────────────────

    public List<StrategyHealthSnapshotEntity> getRecentSnapshots(String strategyId, int limit) {
        LambdaQueryWrapper<StrategyHealthSnapshotEntity> wrapper = new LambdaQueryWrapper<StrategyHealthSnapshotEntity>()
                .orderByDesc(StrategyHealthSnapshotEntity::getSnapshotTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 1000));
        if (strategyId != null && !strategyId.isEmpty()) {
            wrapper.eq(StrategyHealthSnapshotEntity::getStrategyId, strategyId);
        }
        return snapshotMapper.selectList(wrapper);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────

    private StrategyHealthSnapshotEntity findLatestSnapshot(String strategyId) {
        List<StrategyHealthSnapshotEntity> list = snapshotMapper.selectList(
                new LambdaQueryWrapper<StrategyHealthSnapshotEntity>()
                        .eq(StrategyHealthSnapshotEntity::getStrategyId, strategyId)
                        .orderByDesc(StrategyHealthSnapshotEntity::getSnapshotTime)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private boolean isModeActive(String mode) {
        if (mode == null) return false;
        return "SHADOW".equalsIgnoreCase(mode) || "PAPER".equalsIgnoreCase(mode) || "LIVE".equalsIgnoreCase(mode);
    }
}
