package com.metaverse.coin.strategy.job.hedge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.HedgePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.hedge.model.HedgeOpportunity;
import com.metaverse.coin.strategy.job.hedge.model.HedgeRiskCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 对冲策略专属风控守卫
 *
 * 负责 hedge 策略内部的风控检查，完全独立于跟单策略的风控逻辑：
 *   1. 净利率门槛（扣费后仍有正收益）
 *   2. 流动性充足性（两侧订单簿深度）
 *   3. 单笔仓位上限
 *   4. 最大同时持仓数
 *   5. 对冲策略最大总敞口
 *   6. 重复开仓保护（同一 market 只开一次）
 *   7. 市场到期时间检查（不开将要结束的市场）
 *   8. 跨策略全局敞口（调用 GlobalRiskGuard）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgeRiskGuard {

    private final HedgePositionMapper hedgePositionMapper;
    private final HedgeProperties properties;
    private final HedgeSizeCalculatorService sizeCalculator;
    private final GlobalRiskGuard globalRiskGuard;

    /** 市场到期前最少剩余时间（ms），过近结束的市场不开仓 */
    private static final long MIN_MARKET_REMAINING_MS = 4L * 60 * 60 * 1000; // 4 小时

    /**
     * 对给定套利机会执行完整风控检查
     *
     * @param opportunity    套利机会
     * @param availableCapital 当前可用资金
     * @return 风控结果（包含建议仓位或拒绝原因）
     */
    public HedgeRiskCheckResult check(HedgeOpportunity opportunity, BigDecimal availableCapital) {
        List<String> violations = new ArrayList<>();

        // 1. 净利率
        if (opportunity.getNetEdgeRate().compareTo(properties.getMinNetEdgeRate()) < 0) {
            violations.add(String.format("净利率 %.4f 低于阈值 %.4f",
                    opportunity.getNetEdgeRate(), properties.getMinNetEdgeRate()));
        }

        // 2. 流动性
        if (opportunity.getYesLiquidityUsdc().compareTo(properties.getMinSideLiquidityUsdc()) < 0) {
            violations.add(String.format("YES 流动性 %.2f USDC 不足，要求 >= %.2f",
                    opportunity.getYesLiquidityUsdc(), properties.getMinSideLiquidityUsdc()));
        }
        if (opportunity.getNoLiquidityUsdc().compareTo(properties.getMinSideLiquidityUsdc()) < 0) {
            violations.add(String.format("NO 流动性 %.2f USDC 不足，要求 >= %.2f",
                    opportunity.getNoLiquidityUsdc(), properties.getMinSideLiquidityUsdc()));
        }

        // 3. 市场到期保护
        if (opportunity.getMarketEndAt() != null && opportunity.getMarketEndAt() > 0) {
            long remaining = opportunity.getMarketEndAt() - System.currentTimeMillis();
            if (remaining < MIN_MARKET_REMAINING_MS) {
                violations.add(String.format("市场剩余时间 %d 分钟不足 4 小时",
                        remaining / 60_000));
            }
        }

        // 4. 重复建仓保护
        Long existingOpen = hedgePositionMapper.selectCount(new LambdaQueryWrapper<HedgePositionEntity>()
                .eq(HedgePositionEntity::getMarketId, opportunity.getMarketId())
                .in(HedgePositionEntity::getStatus, List.of("OPEN", "SETTLING")));
        if (existingOpen > 0) {
            violations.add("该市场已有 OPEN/SETTLING 状态的对冲持仓");
        }

        if (!violations.isEmpty()) {
            return HedgeRiskCheckResult.fail(violations);
        }

        // 5. 计算建议仓位
        BigDecimal suggestedSize = sizeCalculator.calculatePositionUsdc(opportunity, availableCapital);

        // 6. 最小仓位
        if (suggestedSize.compareTo(properties.getMinSinglePositionUsdc()) < 0) {
            violations.add(String.format("建议仓位 %.2f USDC 低于最小值 %.2f",
                    suggestedSize, properties.getMinSinglePositionUsdc()));
            return HedgeRiskCheckResult.fail(violations);
        }

        // 7. 当前持仓数
        long openCount = hedgePositionMapper.selectCount(new LambdaQueryWrapper<HedgePositionEntity>()
                .eq(HedgePositionEntity::getStatus, "OPEN"));
        if (openCount >= properties.getMaxOpenPositions()) {
            violations.add(String.format("持仓数 %d 达到上限 %d", openCount, properties.getMaxOpenPositions()));
            return HedgeRiskCheckResult.fail(violations);
        }

        // 8 + 9. 一次性获取全局敞口快照（避免 currentHedgeExposureUsdc 被重复查询）
        GlobalRiskGuard.GlobalExposureSummary exposure = globalRiskGuard.getSummary();
        BigDecimal currentHedgeExposure = exposure.hedgeExposureUsdc();

        // 8. 对冲策略内部总敞口上限
        BigDecimal newHedgeExposure = currentHedgeExposure.add(suggestedSize);
        if (newHedgeExposure.compareTo(properties.getMaxTotalExposureUsdc()) > 0) {
            BigDecimal allowedSize = properties.getMaxTotalExposureUsdc().subtract(currentHedgeExposure);
            if (allowedSize.compareTo(properties.getMinSinglePositionUsdc()) < 0) {
                violations.add(String.format("对冲总敞口 %.2f 已达上限 %.2f",
                        currentHedgeExposure, properties.getMaxTotalExposureUsdc()));
                return HedgeRiskCheckResult.fail(violations);
            }
            suggestedSize = allowedSize.setScale(4, RoundingMode.HALF_DOWN);
        }

        // 9. 全局跨策略敞口（直接用已查询的快照，不再重复查库）
        BigDecimal globalMax = properties.getGlobalMaxTotalExposureUsdc();
        if (globalMax != null && globalMax.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal newGlobalTotal = exposure.followExposureUsdc()
                    .add(currentHedgeExposure).add(suggestedSize);
            if (newGlobalTotal.compareTo(globalMax) > 0) {
                violations.add("全局跨策略总敞口超限（跟单 + 对冲）");
                return HedgeRiskCheckResult.fail(violations);
            }
            BigDecimal maxHedgeShare = properties.getMaxHedgeShareOfGlobalExposure();
            if (maxHedgeShare != null && maxHedgeShare.compareTo(BigDecimal.ZERO) > 0
                    && newGlobalTotal.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal newHedgeShare = currentHedgeExposure.add(suggestedSize)
                        .divide(newGlobalTotal, 4, RoundingMode.HALF_UP);
                if (newHedgeShare.compareTo(maxHedgeShare) > 0) {
                    violations.add(String.format("对冲占全局敞口比例 %.2f 超出上限 %.2f",
                            newHedgeShare, maxHedgeShare));
                    return HedgeRiskCheckResult.fail(violations);
                }
            }
        }

        return HedgeRiskCheckResult.pass(suggestedSize);
    }
}
