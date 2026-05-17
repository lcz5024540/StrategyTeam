package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingAggregationView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingAggregationView.LowWinRateLeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web BFF 端: W2-T2 Leader 滑动窗口胜率灰度观察 (第十六批 #1).
 *
 * <p>实现策略: 直查 DB, 按 leader 聚合最近 N 天已关闭仓位的盈亏分布. 算法与 job 端
 * {@code LeaderRollingPerformanceService} 算法对齐 (取最近 windowSize=20 笔每 leader).</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十四节.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderRollingAggregationReadService {

    private static final int DEFAULT_LOOKBACK_DAYS = 14;
    private static final int MAX_LOOKBACK_DAYS = 90;
    private static final int DEFAULT_WINDOW_SIZE = 20;
    private static final int DEFAULT_MIN_SAMPLES = 10;
    private static final BigDecimal DEFAULT_MIN_WIN_RATE = new BigDecimal("0.40");
    private static final int TOP_LEADERS = 15;

    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletPositionMapper positionMapper;

    public LeaderRollingAggregationView aggregate(Integer lookbackDays) {
        int days = lookbackDays == null || lookbackDays <= 0
                ? DEFAULT_LOOKBACK_DAYS
                : Math.min(lookbackDays, MAX_LOOKBACK_DAYS);
        Date now = new Date();
        Date since = new Date(now.getTime() - days * 86_400_000L);

        // 1) 取该窗口内所有 BUY+ENTRY 订单, distinct walletAddress
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .eq(SmartWalletOrderEntity::getSide, "BUY")
                        .eq(SmartWalletOrderEntity::getOrderType, "ENTRY")
                        .isNotNull(SmartWalletOrderEntity::getPositionId)
                        .isNotNull(SmartWalletOrderEntity::getFilledAt)
                        .ge(SmartWalletOrderEntity::getFilledAt, since)
                        .orderByDesc(SmartWalletOrderEntity::getFilledAt));

        // walletAddress -> ordered positionIds (按 filledAt desc, 各 leader 取最近 windowSize 个)
        Map<String, List<Long>> walletToPositions = new HashMap<>();
        for (SmartWalletOrderEntity o : orders) {
            if (o.getWalletAddress() == null || o.getPositionId() == null) continue;
            walletToPositions.computeIfAbsent(o.getWalletAddress(), k -> new ArrayList<>())
                    .add(o.getPositionId());
        }
        int activeLeaders = walletToPositions.size();

        // 2) 一次性 batch 查所有 positions; 后续在内存按 leader 切片
        List<Long> allPositionIds = new ArrayList<>();
        for (List<Long> ids : walletToPositions.values()) {
            allPositionIds.addAll(ids);
        }
        Map<Long, SmartWalletPositionEntity> closedById = new HashMap<>();
        if (!allPositionIds.isEmpty()) {
            positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                            .in(SmartWalletPositionEntity::getId, allPositionIds)
                            .in(SmartWalletPositionEntity::getStatus, List.of("CLOSED", "SETTLED", "CLAIMED")))
                    .forEach(p -> closedById.put(p.getId(), p));
        }

        // 3) 对每个 leader, 取最近 windowSize 个已关闭 position, 计算 winRate
        int activeCount = 0, coolingCount = 0, coldCount = 0;
        int totalSamples = 0, totalWins = 0;
        List<LowWinRateLeader> aggregatedLeaders = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : walletToPositions.entrySet()) {
            String leader = entry.getKey();
            int wins = 0, losses = 0;
            BigDecimal totalPnl = BigDecimal.ZERO;
            Date latestClosed = null;
            int taken = 0;
            for (Long pid : entry.getValue()) {
                if (taken >= DEFAULT_WINDOW_SIZE) break;
                SmartWalletPositionEntity p = closedById.get(pid);
                if (p == null) continue;
                BigDecimal pnl = p.getRealizedPnlUsdc() == null ? BigDecimal.ZERO : p.getRealizedPnlUsdc();
                if (pnl.signum() > 0) wins++; else losses++;
                totalPnl = totalPnl.add(pnl);
                if (p.getClosedAt() != null && (latestClosed == null || p.getClosedAt().after(latestClosed))) {
                    latestClosed = p.getClosedAt();
                }
                taken++;
            }
            int samples = wins + losses;
            BigDecimal winRate = samples == 0 ? null
                    : new BigDecimal(wins).divide(new BigDecimal(samples), 4, RoundingMode.HALF_UP);

            String status;
            if (samples < DEFAULT_MIN_SAMPLES) {
                status = "COLD";
                coldCount++;
            } else if (winRate.compareTo(DEFAULT_MIN_WIN_RATE) < 0) {
                status = "COOLING";
                coolingCount++;
            } else {
                status = "ACTIVE";
                activeCount++;
            }
            totalSamples += samples;
            totalWins += wins;

            aggregatedLeaders.add(LowWinRateLeader.builder()
                    .leaderAddress(leader)
                    .sampleCount(samples)
                    .wins(wins)
                    .losses(losses)
                    .winRate(winRate)
                    .totalPnlUsdc(totalPnl.setScale(4, RoundingMode.HALF_UP))
                    .latestClosedAt(latestClosed)
                    .status(status)
                    .build());
        }

        // 4) top cooling: 按 winRate asc 取前 N (cooling+cold 优先)
        aggregatedLeaders.sort(Comparator
                .comparing((LowWinRateLeader l) -> {
                    if ("COOLING".equals(l.getStatus())) return 0;
                    if ("COLD".equals(l.getStatus())) return 1;
                    return 2;
                })
                .thenComparing(LowWinRateLeader::getWinRate, Comparator.nullsLast(Comparator.naturalOrder())));
        List<LowWinRateLeader> topCooling = aggregatedLeaders.size() > TOP_LEADERS
                ? new ArrayList<>(aggregatedLeaders.subList(0, TOP_LEADERS))
                : aggregatedLeaders;

        BigDecimal overallWinRate = totalSamples == 0 ? null
                : new BigDecimal(totalWins).divide(new BigDecimal(totalSamples), 4, RoundingMode.HALF_UP);

        return LeaderRollingAggregationView.builder()
                .generatedAt(now)
                .lookbackDays(days)
                .activeLeaders(activeLeaders)
                .evaluated(activeCount + coolingCount + coldCount)
                .activeCount(activeCount)
                .coolingCount(coolingCount)
                .coldCount(coldCount)
                .overallWinRate(overallWinRate)
                .minSamples(DEFAULT_MIN_SAMPLES)
                .minWinRate(DEFAULT_MIN_WIN_RATE)
                .windowSize(DEFAULT_WINDOW_SIZE)
                .topCoolingLeaders(topCooling)
                .build();
    }
}
