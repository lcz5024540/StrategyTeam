package com.metaverse.coin.strategy.job.btcminute.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.BtcMinutePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.BtcMinutePositionEntity;
import com.metaverse.coin.strategy.job.btcminute.config.BtcMinuteStrategyProperties;
import com.metaverse.coin.strategy.job.btcminute.model.BtcMinuteSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * BTC 5 分钟策略风控服务。
 *
 * <p>检查项：</p>
 * <ul>
 *   <li>最小 Edge 阈值</li>
 *   <li>最大同时持仓数</li>
 *   <li>单笔最大仓位</li>
 *   <li>每日最大亏损</li>
 *   <li>同一市场不重复建仓</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BtcMinuteRiskService {

    private static final String LOG_PREFIX = "[BTC_RISK]";

    private final BtcMinuteStrategyProperties properties;
    private final BtcMinutePositionMapper positionMapper;

    /**
     * 对信号进行风控检查。
     *
     * @param signal 交易信号
     * @return null 表示通过，否则返回拒绝原因
     */
    public String evaluate(BtcMinuteSignal signal) {
        // 1. Edge 阈值
        if (signal.getEdge() == null || signal.getEdge().compareTo(properties.getMinEdgeThreshold()) < 0) {
            return String.format("Edge 不足: %.4f < %.4f",
                    signal.getEdge(), properties.getMinEdgeThreshold());
        }

        // 2. 最大持仓数
        long openCount = positionMapper.selectCount(
                new LambdaQueryWrapper<BtcMinutePositionEntity>()
                        .eq(BtcMinutePositionEntity::getStatus, "OPEN")
                        .eq(BtcMinutePositionEntity::getStrategyId, "BTC_MINUTE"));
        if (openCount >= properties.getMaxOpenPositions()) {
            return String.format("持仓数已满: %d/%d", openCount, properties.getMaxOpenPositions());
        }

        // 3. 同一市场不重复
        Long sameMarketOpen = positionMapper.selectCount(
                new LambdaQueryWrapper<BtcMinutePositionEntity>()
                        .eq(BtcMinutePositionEntity::getMarketId, signal.getMarketId())
                        .eq(BtcMinutePositionEntity::getStatus, "OPEN"));
        if (sameMarketOpen > 0) {
            return "该市场已有持仓";
        }

        // 4. 单笔仓位上限
        BigDecimal maxStake = properties.getMaxSingleStakeUsdc();
        if (signal.getSuggestedStakeUsdc() != null && signal.getSuggestedStakeUsdc().compareTo(maxStake) > 0) {
            // 不拒绝，但后续会截断
            log.info("{} 信号仓位超限，将截断: {} → {}", LOG_PREFIX, signal.getSuggestedStakeUsdc(), maxStake);
        }

        // 5. 每日亏损检查
        if (!isDailyLossWithinLimit()) {
            return "每日亏损已达上限";
        }

        return null; // 通过
    }

    /**
     * 计算推荐仓位（USDC）。
     * 基于 edge 大小线性映射，受 maxSingleStakeUsdc 上限约束。
     */
    public BigDecimal calculateStake(BtcMinuteSignal signal) {
        BigDecimal edge = signal.getEdge();
        if (edge == null) return BigDecimal.ZERO;

        BigDecimal maxStake = properties.getMaxSingleStakeUsdc();
        BigDecimal minEdge = properties.getMinEdgeThreshold();

        // stake = maxStake * min(edge / (2 * minEdge), 1.0)
        // edge = 8% → stake = maxStake * 0.5
        // edge = 16%+ → stake = maxStake
        BigDecimal ratio = edge.divide(minEdge.multiply(BigDecimal.valueOf(2)), 4, BigDecimal.ROUND_HALF_UP);
        if (ratio.compareTo(BigDecimal.ONE) > 0) ratio = BigDecimal.ONE;

        BigDecimal stake = maxStake.multiply(ratio).setScale(2, BigDecimal.ROUND_HALF_UP);
        return stake.max(BigDecimal.ONE); // 最少 1 USDC
    }

    private boolean isDailyLossWithinLimit() {
        try {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            Date todayStart = cal.getTime();

            List<BtcMinutePositionEntity> todayClosed = positionMapper.selectList(
                    new LambdaQueryWrapper<BtcMinutePositionEntity>()
                            .eq(BtcMinutePositionEntity::getStatus, "CLOSED")
                            .eq(BtcMinutePositionEntity::getStrategyId, "BTC_MINUTE")
                            .ge(BtcMinutePositionEntity::getUpdatedAt, todayStart));

            BigDecimal totalPnl = todayClosed.stream()
                    .filter(p -> p.getRealizedPnlUsdc() != null)
                    .map(BtcMinutePositionEntity::getRealizedPnlUsdc)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return totalPnl.negate().compareTo(properties.getMaxDailyLossUsdc()) <= 0;
        } catch (Exception e) {
            log.error("{} 每日亏损检查异常，放行: {}", LOG_PREFIX, e.getMessage());
            return true;
        }
    }
}
