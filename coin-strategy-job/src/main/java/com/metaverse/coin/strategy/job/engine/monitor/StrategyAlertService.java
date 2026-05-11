package com.metaverse.coin.strategy.job.engine.monitor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyAlertMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import com.metaverse.coin.strategy.job.engine.PolymarketStrategy;
import com.metaverse.coin.strategy.job.engine.StrategyRegistry;
import com.metaverse.coin.strategy.job.engine.StrategyRunLogService;
import com.metaverse.coin.strategy.job.engine.config.StrategyEngineProperties;
import com.metaverse.coin.strategy.job.engine.model.StrategyHealthSnapshot;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRunLogEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 策略告警服务。
 *
 * <p>定时检查所有策略的健康状态，发现异常时生成告警记录并写入日志。</p>
 *
 * <p>告警规则：</p>
 * <ul>
 *   <li>健康检查失败 → CRITICAL</li>
 *   <li>全局曝险超限 → CRITICAL</li>
 *   <li>单策略曝险超限 → WARN</li>
 *   <li>每日亏损超限 → CRITICAL</li>
 *   <li>连续错误过多 → WARN</li>
 *   <li>心跳丢失 → CRITICAL</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyAlertService {

    private static final String LOG_PREFIX = "[STRATEGY_ALERT]";

    private final StrategyRegistry strategyRegistry;
    private final StrategyEngineProperties engineProperties;
    private final MonitorProperties monitorProperties;
    private final StrategyAlertMapper alertMapper;
    private final StrategyRunLogService runLogService;

    /**
     * 定时告警检查。
     */
    @Scheduled(fixedDelayString = "${strategy-monitor.alert-check-interval-ms:30000}")
    public void scheduledAlertCheck() {
        if (!monitorProperties.isEnabled()) return;
        try {
            runAlertCheck();
        } catch (Exception e) {
            log.error("{} 告警检查异常: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    /**
     * 执行一轮告警检查。
     *
     * @return 本轮产生的告警数
     */
    public int runAlertCheck() {
        int alertCount = 0;
        List<PolymarketStrategy> allStrategies = strategyRegistry.listAll();

        // 1. 逐策略健康检查
        BigDecimal totalExposure = BigDecimal.ZERO;
        BigDecimal totalDailyPnl = BigDecimal.ZERO;

        for (PolymarketStrategy strategy : allStrategies) {
            if (!strategy.isEnabled() || !strategy.getMode().isActive()) continue;

            try {
                StrategyHealthSnapshot snapshot = strategy.health();

                // 1a. 健康检查失败
                if (!snapshot.isHealthy()) {
                    alertCount += fireAlert(strategy.getStrategyId(),
                            AlertType.HEALTH_CHECK_FAIL,
                            "策略 " + strategy.getDisplayName() + " 健康检查失败",
                            snapshot.getNotes());
                }

                // 1b. 单策略曝险检查
                if (snapshot.getTotalExposureUsdc() != null) {
                    totalExposure = totalExposure.add(snapshot.getTotalExposureUsdc());

                    if (snapshot.getTotalExposureUsdc().compareTo(monitorProperties.getStrategyExposureWarnUsdc()) > 0) {
                        alertCount += fireAlert(strategy.getStrategyId(),
                                AlertType.STRATEGY_EXPOSURE_LIMIT,
                                String.format("策略 %s 曝险 %.2f USDC 超过阈值 %.2f",
                                        strategy.getDisplayName(),
                                        snapshot.getTotalExposureUsdc(),
                                        monitorProperties.getStrategyExposureWarnUsdc()),
                                null,
                                snapshot.getTotalExposureUsdc(),
                                monitorProperties.getStrategyExposureWarnUsdc());
                    }
                }

                // 1c. 累计PnL
                if (snapshot.getTodayRealizedPnlUsdc() != null) {
                    totalDailyPnl = totalDailyPnl.add(snapshot.getTodayRealizedPnlUsdc());
                }

                // 1d. 连续错误检查
                checkConsecutiveErrors(strategy);

            } catch (Exception e) {
                log.error("{} 检查策略 {} 时异常: {}", LOG_PREFIX, strategy.getStrategyId(), e.getMessage(), e);
                alertCount += fireAlert(strategy.getStrategyId(),
                        AlertType.HEALTH_CHECK_FAIL,
                        "策略 " + strategy.getDisplayName() + " 健康检查异常",
                        e.getMessage());
            }
        }

        // 2. 全局曝险检查
        BigDecimal maxExposure = engineProperties.getGlobalRisk().getMaxTotalExposureUsdc();
        if (totalExposure.compareTo(maxExposure) > 0) {
            alertCount += fireAlert("ALL",
                    AlertType.EXPOSURE_LIMIT,
                    String.format("全局总曝险 %.2f USDC 超过限额 %.2f", totalExposure, maxExposure),
                    null,
                    totalExposure,
                    maxExposure);
        }

        // 3. 每日亏损检查
        BigDecimal maxDailyLoss = engineProperties.getGlobalRisk().getMaxTotalDailyLossUsdc();
        if (totalDailyPnl.negate().compareTo(maxDailyLoss) > 0) {
            alertCount += fireAlert("ALL",
                    AlertType.DAILY_LOSS_LIMIT,
                    String.format("全局每日亏损 %.2f USDC 超过限额 %.2f", totalDailyPnl.negate(), maxDailyLoss),
                    null,
                    totalDailyPnl.negate(),
                    maxDailyLoss);
        }

        if (alertCount > 0) {
            log.warn("{} 本轮告警检查发现 {} 条告警", LOG_PREFIX, alertCount);
        }
        return alertCount;
    }

    /**
     * 检查策略连续错误数。
     */
    private void checkConsecutiveErrors(PolymarketStrategy strategy) {
        List<StrategyRunLogEntity> recentLogs = runLogService.getRecentLogs(
                strategy.getStrategyId(), monitorProperties.getMaxConsecutiveErrors());

        if (recentLogs.size() < monitorProperties.getMaxConsecutiveErrors()) return;

        boolean allErrors = recentLogs.stream().allMatch(log -> log.getErrors() != null && log.getErrors() > 0);
        if (allErrors) {
            fireAlert(strategy.getStrategyId(),
                    AlertType.CONSECUTIVE_ERRORS,
                    String.format("策略 %s 最近 %d 次运行均有错误",
                            strategy.getDisplayName(), monitorProperties.getMaxConsecutiveErrors()),
                    null);
        }
    }

    // ── 告警触发 ──────────────────────────────────────────────────────

    /**
     * 触发告警（检查去重后写入DB + 日志）。
     *
     * @return 1 = 新告警，0 = 已存在未恢复的同类告警（去重）
     */
    public int fireAlert(String strategyId, AlertType alertType, String title, String message) {
        return fireAlert(strategyId, alertType, title, message, null, null);
    }

    public int fireAlert(String strategyId, AlertType alertType, String title, String message,
                         BigDecimal metricValue, BigDecimal thresholdValue) {
        // 去重：同策略同类型的未恢复告警不重复创建
        Long existing = alertMapper.selectCount(
                new LambdaQueryWrapper<StrategyAlertEntity>()
                        .eq(StrategyAlertEntity::getStrategyId, strategyId)
                        .eq(StrategyAlertEntity::getAlertType, alertType.name())
                        .eq(StrategyAlertEntity::getResolved, false));
        if (existing > 0) return 0;

        StrategyAlertEntity alert = new StrategyAlertEntity()
                .setStrategyId(strategyId)
                .setAlertLevel(alertType.getDefaultLevel().name())
                .setAlertType(alertType.name())
                .setTitle(title)
                .setMessage(message)
                .setMetricValue(metricValue)
                .setThresholdValue(thresholdValue)
                .setAcknowledged(false)
                .setResolved(false)
                .setCreatedAt(new Date());

        alertMapper.insert(alert);

        // 日志输出（根据级别）
        AlertLevel level = alertType.getDefaultLevel();
        switch (level) {
            case FATAL:
            case CRITICAL:
                log.error("{} [{}] {} — strategy={} | {}", LOG_PREFIX, level, title, strategyId, message);
                break;
            case WARN:
                log.warn("{} [{}] {} — strategy={} | {}", LOG_PREFIX, level, title, strategyId, message);
                break;
            default:
                log.info("{} [{}] {} — strategy={} | {}", LOG_PREFIX, level, title, strategyId, message);
        }

        return 1;
    }

    // ── 告警管理 ──────────────────────────────────────────────────────

    /**
     * 确认告警。
     */
    public void acknowledgeAlert(Long alertId) {
        StrategyAlertEntity alert = alertMapper.selectById(alertId);
        if (alert != null && !alert.getAcknowledged()) {
            alert.setAcknowledged(true);
            alert.setAcknowledgedAt(new Date());
            alertMapper.updateById(alert);
            log.info("{} 告警已确认: id={} type={}", LOG_PREFIX, alertId, alert.getAlertType());
        }
    }

    /**
     * 标记告警已恢复。
     */
    public void resolveAlert(Long alertId) {
        StrategyAlertEntity alert = alertMapper.selectById(alertId);
        if (alert != null && !alert.getResolved()) {
            alert.setResolved(true);
            alert.setResolvedAt(new Date());
            alertMapper.updateById(alert);
            log.info("{} 告警已恢复: id={} type={}", LOG_PREFIX, alertId, alert.getAlertType());
        }
    }

    /**
     * 按策略自动恢复已解决的告警。
     */
    public void autoResolveByStrategy(String strategyId, AlertType alertType) {
        List<StrategyAlertEntity> unresolved = alertMapper.selectList(
                new LambdaQueryWrapper<StrategyAlertEntity>()
                        .eq(StrategyAlertEntity::getStrategyId, strategyId)
                        .eq(StrategyAlertEntity::getAlertType, alertType.name())
                        .eq(StrategyAlertEntity::getResolved, false));

        for (StrategyAlertEntity alert : unresolved) {
            alert.setResolved(true);
            alert.setResolvedAt(new Date());
            alertMapper.updateById(alert);
        }

        if (!unresolved.isEmpty()) {
            log.info("{} 自动恢复 {} 条 {} 告警: strategy={}", LOG_PREFIX,
                    unresolved.size(), alertType, strategyId);
        }
    }

    /**
     * 查询未恢复的告警列表。
     */
    public List<StrategyAlertEntity> getActiveAlerts() {
        return alertMapper.selectList(
                new LambdaQueryWrapper<StrategyAlertEntity>()
                        .eq(StrategyAlertEntity::getResolved, false)
                        .orderByDesc(StrategyAlertEntity::getCreatedAt)
                        .last("LIMIT 100"));
    }

    /**
     * 查询指定策略的告警历史。
     */
    public List<StrategyAlertEntity> getAlertHistory(String strategyId, int limit) {
        LambdaQueryWrapper<StrategyAlertEntity> wrapper = new LambdaQueryWrapper<StrategyAlertEntity>()
                .orderByDesc(StrategyAlertEntity::getCreatedAt)
                .last("LIMIT " + Math.min(limit, 500));

        if (strategyId != null && !strategyId.isEmpty()) {
            wrapper.eq(StrategyAlertEntity::getStrategyId, strategyId);
        }
        return alertMapper.selectList(wrapper);
    }
}
