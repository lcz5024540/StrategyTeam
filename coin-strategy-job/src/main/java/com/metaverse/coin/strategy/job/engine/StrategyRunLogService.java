package com.metaverse.coin.strategy.job.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyRunLogMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRunLogEntity;
import com.metaverse.coin.strategy.job.engine.model.StrategyCycleResult;
import com.metaverse.coin.strategy.job.engine.model.StrategyExitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 策略运行日志服务。
 *
 * <p>记录每次策略运行的摘要信息到 strategy_run_log 表，
 * 用于运维监控和历史回溯。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StrategyRunLogService {

    private static final String LOG_PREFIX = "[STRATEGY_RUN_LOG]";

    private final StrategyRunLogMapper runLogMapper;

    /**
     * 记录一次信号扫描运行结果。
     */
    public void recordCycleRun(PolymarketStrategy strategy, StrategyCycleResult result) {
        try {
            StrategyRunLogEntity entity = new StrategyRunLogEntity()
                    .setStrategyId(strategy.getStrategyId())
                    .setWalletId(strategy.getWalletId())
                    .setRunType("SIGNAL_SCAN")
                    .setRunMode(strategy.getMode().name())
                    .setStartedAt(Date.from(result.getStartedAt()))
                    .setFinishedAt(result.getDuration() != null
                            ? Date.from(result.getStartedAt().plus(result.getDuration()))
                            : new Date())
                    .setSignalsFound(result.getSignalsFound())
                    .setOrdersPlaced(result.getOrdersPlaced())
                    .setErrors(result.getErrors());

            runLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("{} 记录信号扫描日志失败: strategy={}, error={}", LOG_PREFIX,
                    strategy.getStrategyId(), e.getMessage(), e);
        }
    }

    /**
     * 记录一次退出扫描运行结果。
     */
    public void recordExitScan(PolymarketStrategy strategy, StrategyExitResult result) {
        try {
            StrategyRunLogEntity entity = new StrategyRunLogEntity()
                    .setStrategyId(strategy.getStrategyId())
                    .setWalletId(strategy.getWalletId())
                    .setRunType("EXIT_SCAN")
                    .setRunMode(strategy.getMode().name())
                    .setStartedAt(Date.from(result.getScannedAt()))
                    .setFinishedAt(new Date())
                    .setSignalsFound(result.getPositionsScanned())
                    .setOrdersPlaced(result.getExitsTriggered())
                    .setErrors(0);

            runLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("{} 记录退出扫描日志失败: strategy={}, error={}", LOG_PREFIX,
                    strategy.getStrategyId(), e.getMessage(), e);
        }
    }

    /**
     * 查询指定策略最近 N 条运行日志。
     */
    public List<StrategyRunLogEntity> getRecentLogs(String strategyId, int limit) {
        return runLogMapper.selectList(
                new LambdaQueryWrapper<StrategyRunLogEntity>()
                        .eq(StrategyRunLogEntity::getStrategyId, strategyId)
                        .orderByDesc(StrategyRunLogEntity::getStartedAt)
                        .last("LIMIT " + Math.min(limit, 200)));
    }
}
