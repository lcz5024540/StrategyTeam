package com.metaverse.coin.strategy.job.engine.monitor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyAlertMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyRunLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRunLogEntity;
import com.metaverse.coin.strategy.job.engine.PolymarketStrategy;
import com.metaverse.coin.strategy.job.engine.StrategyRegistry;
import com.metaverse.coin.strategy.job.engine.model.StrategyHealthSnapshot;
import com.metaverse.coin.strategy.job.engine.monitor.model.DashboardOverview;
import com.metaverse.coin.strategy.job.engine.monitor.model.StrategyDetailView;
import com.metaverse.coin.strategy.job.engine.monitor.model.StrategyStatusView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 策略聚合报表服务。
 *
 * <p>汇总所有策略的实时状态、PnL、曝险、胜率等数据，
 * 为监控 Dashboard 和 REST API 提供数据源。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyReportService {

    private static final String LOG_PREFIX = "[STRATEGY_REPORT]";

    private final StrategyRegistry strategyRegistry;
    private final StrategyAlertMapper alertMapper;
    private final StrategyRunLogMapper runLogMapper;

    /**
     * 获取全局 Dashboard 概览。
     */
    public DashboardOverview getDashboardOverview() {
        List<PolymarketStrategy> allStrategies = strategyRegistry.listAll();
        List<PolymarketStrategy> enabledStrategies = strategyRegistry.listEnabled();

        BigDecimal totalExposure = BigDecimal.ZERO;
        BigDecimal totalRealizedPnl = BigDecimal.ZERO;
        BigDecimal totalUnrealizedPnl = BigDecimal.ZERO;
        int totalPositions = 0;
        int healthyCount = 0;
        List<StrategyStatusView> strategyStatuses = new ArrayList<>();

        for (PolymarketStrategy strategy : allStrategies) {
            try {
                StrategyHealthSnapshot health = strategy.isEnabled() && strategy.getMode().isActive()
                        ? strategy.health()
                        : null;

                StrategyStatusView status = StrategyStatusView.builder()
                        .strategyId(strategy.getStrategyId())
                        .displayName(strategy.getDisplayName())
                        .enabled(strategy.isEnabled())
                        .mode(strategy.getMode().name())
                        .healthy(health != null && health.isHealthy())
                        .openPositions(health != null ? health.getOpenPositions() : 0)
                        .totalExposureUsdc(health != null ? health.getTotalExposureUsdc() : BigDecimal.ZERO)
                        .todayRealizedPnlUsdc(health != null ? health.getTodayRealizedPnlUsdc() : BigDecimal.ZERO)
                        .unrealizedPnlUsdc(health != null ? health.getUnrealizedPnlUsdc() : BigDecimal.ZERO)
                        .build();

                strategyStatuses.add(status);

                if (health != null) {
                    if (health.isHealthy()) healthyCount++;
                    if (health.getTotalExposureUsdc() != null) totalExposure = totalExposure.add(health.getTotalExposureUsdc());
                    if (health.getTodayRealizedPnlUsdc() != null) totalRealizedPnl = totalRealizedPnl.add(health.getTodayRealizedPnlUsdc());
                    if (health.getUnrealizedPnlUsdc() != null) totalUnrealizedPnl = totalUnrealizedPnl.add(health.getUnrealizedPnlUsdc());
                    totalPositions += health.getOpenPositions();
                }

            } catch (Exception e) {
                log.error("{} 获取策略 {} 状态失败: {}", LOG_PREFIX, strategy.getStrategyId(), e.getMessage());
                strategyStatuses.add(StrategyStatusView.builder()
                        .strategyId(strategy.getStrategyId())
                        .displayName(strategy.getDisplayName())
                        .enabled(strategy.isEnabled())
                        .mode(strategy.getMode().name())
                        .healthy(false)
                        .build());
            }
        }

        // 活跃告警数
        Long activeAlerts = alertMapper.selectCount(
                new LambdaQueryWrapper<StrategyAlertEntity>()
                        .eq(StrategyAlertEntity::getResolved, false));

        // 今日运行次数
        Date todayStart = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        Long todayRuns = runLogMapper.selectCount(
                new LambdaQueryWrapper<StrategyRunLogEntity>()
                        .ge(StrategyRunLogEntity::getStartedAt, todayStart));

        return DashboardOverview.builder()
                .totalStrategies(allStrategies.size())
                .enabledStrategies(enabledStrategies.size())
                .healthyStrategies(healthyCount)
                .totalOpenPositions(totalPositions)
                .totalExposureUsdc(totalExposure)
                .todayRealizedPnlUsdc(totalRealizedPnl)
                .unrealizedPnlUsdc(totalUnrealizedPnl)
                .activeAlerts(activeAlerts != null ? activeAlerts.intValue() : 0)
                .todayRunCount(todayRuns != null ? todayRuns.intValue() : 0)
                .strategies(strategyStatuses)
                .build();
    }

    /**
     * 获取单个策略的详细信息。
     */
    public StrategyDetailView getStrategyDetail(String strategyId) {
        PolymarketStrategy strategy = strategyRegistry.getStrategy(strategyId);
        if (strategy == null) return null;

        StrategyHealthSnapshot health = null;
        if (strategy.isEnabled() && strategy.getMode().isActive()) {
            try {
                health = strategy.health();
            } catch (Exception e) {
                log.error("{} 获取策略详情失败: {}", LOG_PREFIX, e.getMessage());
            }
        }

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
                .strategyId(strategy.getStrategyId())
                .displayName(strategy.getDisplayName())
                .enabled(strategy.isEnabled())
                .mode(strategy.getMode().name())
                .walletId(strategy.getWalletId())
                .healthy(health != null && health.isHealthy())
                .openPositions(health != null ? health.getOpenPositions() : 0)
                .pendingOrders(health != null ? health.getPendingOrders() : 0)
                .walletBalanceUsdc(health != null ? health.getWalletBalanceUsdc() : null)
                .totalExposureUsdc(health != null ? health.getTotalExposureUsdc() : null)
                .todayRealizedPnlUsdc(health != null ? health.getTodayRealizedPnlUsdc() : null)
                .unrealizedPnlUsdc(health != null ? health.getUnrealizedPnlUsdc() : null)
                .notes(health != null ? health.getNotes() : null)
                .todayRunCount(todayRuns)
                .todaySignalsFound(todaySignals)
                .todayOrdersPlaced(todayOrders)
                .todayErrors(todayErrors)
                .activeAlerts(alerts)
                .recentLogs(recentLogs)
                .build();
    }
}
