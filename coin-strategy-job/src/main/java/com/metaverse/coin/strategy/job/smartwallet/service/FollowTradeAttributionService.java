package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.FollowTradeAttributionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Read-only aggregation service for follow-trade PnL attribution.
 * Does not modify any position or task data.
 *
 * Supported groupBy values:
 *   WALLET_SCORE   — group by wallet score tier (LOW / MEDIUM / HIGH)
 *   EXIT_REASON    — group by SmartWalletOrderEntity.exitReason
 *   MARKET_TIMING  — group by remaining market hours at position open time
 */
@Service
@RequiredArgsConstructor
public class FollowTradeAttributionService {

    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletMarketInfoService marketInfoService;

    /**
     * Build attribution report grouped by the specified dimension.
     *
     * @param groupBy   "WALLET_SCORE" | "EXIT_REASON" | "MARKET_TIMING"
     * @param limit     max closed positions to scan (default 500)
     */
    public List<FollowTradeAttributionView> buildAttribution(String groupBy, Integer limit) {
        int scanLimit = (limit == null || limit <= 0) ? 500 : Math.min(limit, 2000);

        // Load closed positions
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .in(SmartWalletPositionEntity::getStatus, List.of("CLOSED", "SETTLED", "CLAIMED"))
                        .orderByDesc(SmartWalletPositionEntity::getClosedAt)
                        .last("limit " + scanLimit));
        marketInfoService.populatePositions(positions);

        // Load exit orders for these positions (side=SELL, orderType=EXIT)
        List<Long> positionIds = positions.stream()
                .map(SmartWalletPositionEntity::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, String> exitReasonByPositionId = new LinkedHashMap<>();
        if (!positionIds.isEmpty()) {
            List<SmartWalletOrderEntity> exitOrders = orderMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletOrderEntity>()
                            .in(SmartWalletOrderEntity::getPositionId, positionIds)
                            .eq(SmartWalletOrderEntity::getSide, "SELL")
                            .eq(SmartWalletOrderEntity::getOrderType, "EXIT"));
            exitOrders.forEach(order -> {
                if (order.getPositionId() != null) {
                    exitReasonByPositionId.putIfAbsent(order.getPositionId(),
                            order.getExitReason() == null ? "UNKNOWN" : order.getExitReason());
                }
            });
        }

        // Group and aggregate
        String dimension = groupBy == null ? "EXIT_REASON" : groupBy.toUpperCase();
        return switch (dimension) {
            case "WALLET_SCORE" -> groupByWalletScore(positions, exitReasonByPositionId);
            case "MARKET_TIMING" -> groupByMarketTiming(positions, exitReasonByPositionId);
            default -> groupByExitReason(positions, exitReasonByPositionId);
        };
    }

    private List<FollowTradeAttributionView> groupByExitReason(
            List<SmartWalletPositionEntity> positions,
            Map<Long, String> exitReasonByPositionId) {
        Map<String, List<SmartWalletPositionEntity>> grouped = positions.stream()
                .collect(Collectors.groupingBy(p -> exitReasonByPositionId.getOrDefault(p.getId(), "UNKNOWN")));
        return buildViews(grouped, exitReasonByPositionId);
    }

    private List<FollowTradeAttributionView> groupByMarketTiming(
            List<SmartWalletPositionEntity> positions,
            Map<Long, String> exitReasonByPositionId) {
        Map<String, List<SmartWalletPositionEntity>> grouped = positions.stream()
                .collect(Collectors.groupingBy(this::resolveMarketTimingBucket));
        return buildViews(grouped, exitReasonByPositionId);
    }

    private String resolveMarketTimingBucket(SmartWalletPositionEntity position) {
        Long marketEndAt = position.getMarketEndAt();
        if (marketEndAt == null || position.getOpenedAt() == null) {
            return "UNKNOWN";
        }
        long remainingAtOpenMs = marketEndAt - position.getOpenedAt().getTime();
        long remainingHours = remainingAtOpenMs / 3600_000L;
        if (remainingHours < 24) return "<24h";
        if (remainingHours < 168) return "1-7d";
        return ">7d";
    }

    private List<FollowTradeAttributionView> groupByWalletScore(
            List<SmartWalletPositionEntity> positions,
            Map<Long, String> exitReasonByPositionId) {
        // Find related follow tasks for each position via marketInfoId
        Map<Long, String> walletAddressByMarketInfoId = new LinkedHashMap<>();
        List<Long> marketInfoIds = positions.stream()
                .map(SmartWalletPositionEntity::getMarketInfoId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!marketInfoIds.isEmpty()) {
            List<SmartWalletFollowTaskEntity> followTasks = followTaskMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                            .in(SmartWalletFollowTaskEntity::getMarketInfoId, marketInfoIds)
                            .eq(SmartWalletFollowTaskEntity::getStatus, "FOLLOWED"));
            followTasks.forEach(ft -> walletAddressByMarketInfoId.putIfAbsent(
                    ft.getMarketInfoId(), ft.getWalletAddress()));
        }

        // Fetch profiles
        List<String> walletAddresses = walletAddressByMarketInfoId.values().stream()
                .filter(Objects::nonNull).distinct().toList();
        Map<String, BigDecimal> walletScoreByAddress = new LinkedHashMap<>();
        if (!walletAddresses.isEmpty()) {
            List<SmartWalletProfileEntity> profiles = profileMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletProfileEntity>()
                            .in(SmartWalletProfileEntity::getWalletAddress, walletAddresses)
                            .eq(SmartWalletProfileEntity::getSource, "POLYMARKET"));
            profiles.forEach(p -> walletScoreByAddress.put(p.getWalletAddress(), p.getWalletScore()));
        }

        Map<String, List<SmartWalletPositionEntity>> grouped = positions.stream()
                .collect(Collectors.groupingBy(p -> {
                    String wallet = walletAddressByMarketInfoId.get(p.getMarketInfoId());
                    BigDecimal score = wallet == null ? null : walletScoreByAddress.get(wallet);
                    return resolveScoreBucket(score);
                }));
        return buildViews(grouped, exitReasonByPositionId);
    }

    private String resolveScoreBucket(BigDecimal walletScore) {
        if (walletScore == null) return "UNKNOWN";
        if (walletScore.compareTo(new BigDecimal("0.80")) >= 0) return "HIGH(≥0.80)";
        if (walletScore.compareTo(new BigDecimal("0.72")) >= 0) return "MEDIUM(0.72-0.80)";
        return "LOW(<0.72)";
    }

    private List<FollowTradeAttributionView> buildViews(
            Map<String, List<SmartWalletPositionEntity>> grouped,
            Map<Long, String> exitReasonByPositionId) {
        List<FollowTradeAttributionView> result = new ArrayList<>();
        grouped.forEach((groupKey, posGroup) -> {
            int tradeCount = posGroup.size();
            int winCount = 0;
            BigDecimal totalPnl = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;
            BigDecimal totalHoldingHours = BigDecimal.ZERO;
            Map<String, Integer> exitBreakdown = new LinkedHashMap<>();

            for (SmartWalletPositionEntity p : posGroup) {
                BigDecimal pnl = p.getRealizedPnlUsdc() == null ? BigDecimal.ZERO : p.getRealizedPnlUsdc();
                if (pnl.compareTo(BigDecimal.ZERO) > 0) winCount++;
                totalPnl = totalPnl.add(pnl);
                totalCost = totalCost.add(p.getCostBasisUsdc() == null ? BigDecimal.ZERO : p.getCostBasisUsdc());

                if (p.getOpenedAt() != null && p.getClosedAt() != null) {
                    long holdingMs = p.getClosedAt().getTime() - p.getOpenedAt().getTime();
                    totalHoldingHours = totalHoldingHours.add(
                            new BigDecimal(holdingMs).divide(new BigDecimal("3600000"), 2, RoundingMode.HALF_UP));
                }

                String reason = exitReasonByPositionId.getOrDefault(p.getId(), "UNKNOWN");
                exitBreakdown.merge(reason, 1, Integer::sum);
            }

            BigDecimal winRate = tradeCount == 0 ? BigDecimal.ZERO
                    : new BigDecimal(winCount).divide(new BigDecimal(tradeCount), 4, RoundingMode.HALF_UP);
            BigDecimal avgPnl = tradeCount == 0 ? BigDecimal.ZERO
                    : totalPnl.divide(new BigDecimal(tradeCount), 2, RoundingMode.HALF_UP);
            BigDecimal avgCost = tradeCount == 0 ? BigDecimal.ZERO
                    : totalCost.divide(new BigDecimal(tradeCount), 2, RoundingMode.HALF_UP);
            BigDecimal avgHours = tradeCount == 0 ? BigDecimal.ZERO
                    : totalHoldingHours.divide(new BigDecimal(tradeCount), 1, RoundingMode.HALF_UP);

            result.add(FollowTradeAttributionView.builder()
                    .groupKey(groupKey)
                    .tradeCount(tradeCount)
                    .winCount(winCount)
                    .winRate(winRate)
                    .totalRealizedPnlUsdc(totalPnl.setScale(2, RoundingMode.HALF_UP))
                    .avgRealizedPnlUsdc(avgPnl)
                    .avgCostBasisUsdc(avgCost)
                    .avgHoldingHours(avgHours)
                    .exitTypeBreakdown(exitBreakdown)
                    .build());
        });

        result.sort((a, b) -> b.getTotalRealizedPnlUsdc().compareTo(a.getTotalRealizedPnlUsdc()));
        return result;
    }
}
