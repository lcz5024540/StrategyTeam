package com.metaverse.coin.strategy.job.engine.monitor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyHealthSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyHealthSnapshotEntity;
import com.metaverse.coin.strategy.job.engine.PolymarketStrategy;
import com.metaverse.coin.strategy.job.engine.StrategyRegistry;
import com.metaverse.coin.strategy.job.engine.model.StrategyHealthSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 健康快照定时采集器。
 *
 * <p>按照配置的间隔，定时采集所有启用策略的健康状态写入 strategy_health_snapshot 表，
 * 用于历史趋势分析和运维回溯。</p>
 *
 * <p>同时负责过期快照的自动清理。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HealthSnapshotScheduler {

    private static final String LOG_PREFIX = "[HEALTH_SNAPSHOT]";

    private final StrategyRegistry strategyRegistry;
    private final MonitorProperties monitorProperties;
    private final StrategyHealthSnapshotMapper snapshotMapper;

    /**
     * 定时采集健康快照。
     */
    @Scheduled(fixedDelayString = "${strategy-monitor.snapshot-interval-ms:60000}")
    public void scheduledSnapshot() {
        if (!monitorProperties.isEnabled()) return;
        try {
            collectSnapshots();
        } catch (Exception e) {
            log.error("{} 快照采集异常: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }

    /**
     * 执行一轮快照采集。
     *
     * @return 采集的快照数
     */
    public int collectSnapshots() {
        List<PolymarketStrategy> strategies = strategyRegistry.listAll();
        if (strategies.isEmpty()) return 0;

        Date now = new Date();
        int collected = 0;

        for (PolymarketStrategy strategy : strategies) {
            if (!strategy.isEnabled()) continue;

            try {
                StrategyHealthSnapshot health = strategy.health();

                StrategyHealthSnapshotEntity entity = new StrategyHealthSnapshotEntity()
                        .setStrategyId(strategy.getStrategyId())
                        .setSnapshotTime(now)
                        .setHealthy(health.isHealthy())
                        .setMode(strategy.getMode().name())
                        .setOpenPositions(health.getOpenPositions())
                        .setPendingOrders(health.getPendingOrders())
                        .setWalletBalanceUsdc(health.getWalletBalanceUsdc())
                        .setTotalExposureUsdc(health.getTotalExposureUsdc())
                        .setTodayRealizedPnl(health.getTodayRealizedPnlUsdc())
                        .setUnrealizedPnl(health.getUnrealizedPnlUsdc())
                        .setNotes(health.getNotes())
                        .setCreatedAt(now);

                snapshotMapper.insert(entity);
                collected++;

            } catch (Exception e) {
                log.error("{} 采集策略 {} 快照失败: {}", LOG_PREFIX, strategy.getStrategyId(), e.getMessage(), e);
            }
        }

        log.debug("{} 本轮采集 {} 个策略快照", LOG_PREFIX, collected);
        return collected;
    }

    /**
     * 查询指定策略的最近快照列表。
     */
    public List<StrategyHealthSnapshotEntity> getRecentSnapshots(String strategyId, int limit) {
        LambdaQueryWrapper<StrategyHealthSnapshotEntity> wrapper = new LambdaQueryWrapper<StrategyHealthSnapshotEntity>()
                .orderByDesc(StrategyHealthSnapshotEntity::getSnapshotTime)
                .last("LIMIT " + Math.min(limit, 1000));

        if (strategyId != null && !strategyId.isEmpty()) {
            wrapper.eq(StrategyHealthSnapshotEntity::getStrategyId, strategyId);
        }

        return snapshotMapper.selectList(wrapper);
    }

    /**
     * 查询指定策略在指定时间范围内的快照。
     */
    public List<StrategyHealthSnapshotEntity> getSnapshotsBetween(String strategyId, Date from, Date to) {
        LambdaQueryWrapper<StrategyHealthSnapshotEntity> wrapper = new LambdaQueryWrapper<StrategyHealthSnapshotEntity>()
                .ge(StrategyHealthSnapshotEntity::getSnapshotTime, from)
                .le(StrategyHealthSnapshotEntity::getSnapshotTime, to)
                .orderByAsc(StrategyHealthSnapshotEntity::getSnapshotTime);

        if (strategyId != null && !strategyId.isEmpty()) {
            wrapper.eq(StrategyHealthSnapshotEntity::getStrategyId, strategyId);
        }

        return snapshotMapper.selectList(wrapper);
    }

    /**
     * 清理过期快照（每日执行一次）。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldSnapshots() {
        if (!monitorProperties.isEnabled()) return;

        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(monitorProperties.getSnapshotRetentionDays());
            Date cutoffDate = Date.from(cutoff.atZone(ZoneId.systemDefault()).toInstant());

            int deleted = snapshotMapper.delete(
                    new LambdaQueryWrapper<StrategyHealthSnapshotEntity>()
                            .lt(StrategyHealthSnapshotEntity::getSnapshotTime, cutoffDate));

            if (deleted > 0) {
                log.info("{} 清理过期快照: {} 条 (截止 {})", LOG_PREFIX, deleted, cutoffDate);
            }
        } catch (Exception e) {
            log.error("{} 清理过期快照异常: {}", LOG_PREFIX, e.getMessage(), e);
        }
    }
}
