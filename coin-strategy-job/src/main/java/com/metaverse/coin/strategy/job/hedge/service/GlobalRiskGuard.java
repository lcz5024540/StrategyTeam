package com.metaverse.coin.strategy.job.hedge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.HedgePositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 跨策略全局风控守卫
 *
 * 职责：在对冲策略和跟单策略之间实施共享的资金上限，
 * 防止两个策略竞争同一份资金导致超额风险敞口。
 *
 * 数据隔离说明：
 *   - 跟单仓位来自 smart_wallet_position（由 SmartWalletPositionMapper 管理）
 *   - 对冲仓位来自 hedge_position（由 HedgePositionMapper 管理）
 *   - 本类是唯一跨表读取两者数据的地方，且仅限只读查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalRiskGuard {

    private final HedgePositionMapper hedgePositionMapper;
    private final SmartWalletPositionMapper smartWalletPositionMapper;
    private final HedgeProperties properties;

    /**
     * 计算当前跟单策略的总敞口（OPEN 状态持仓的 cost_basis 之和）
     */
    public BigDecimal currentFollowExposureUsdc() {
        List<SmartWalletPositionEntity> openPositions = smartWalletPositionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getStatus, "OPEN")
                        .select(SmartWalletPositionEntity::getCostBasisUsdc));
        return openPositions.stream()
                .map(p -> p.getCostBasisUsdc() != null ? p.getCostBasisUsdc() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算当前对冲策略的总敞口（OPEN 状态持仓的 total_cost_usdc 之和）
     *
     * 默认仅统计 LIVE 仓（PAPER 不消耗真实资金，不应占全局敞口）。
     * 通过 {@code polymarket.hedge.paper.include-in-global-exposure=true} 可以恢复旧行为。
     */
    public BigDecimal currentHedgeExposureUsdc() {
        LambdaQueryWrapper<HedgePositionEntity> wrapper = new LambdaQueryWrapper<HedgePositionEntity>()
                .eq(HedgePositionEntity::getStatus, "OPEN")
                .select(HedgePositionEntity::getTotalCostUsdc);

        boolean includePaper = properties.getPaper() != null
                && properties.getPaper().isIncludeInGlobalExposure();
        if (!includePaper) {
            // 兼容历史数据：mode 为空时视为 LIVE
            wrapper.and(w -> w.eq(HedgePositionEntity::getMode, "LIVE")
                    .or().isNull(HedgePositionEntity::getMode));
        }

        List<HedgePositionEntity> openPositions = hedgePositionMapper.selectList(wrapper);
        return openPositions.stream()
                .map(p -> p.getTotalCostUsdc() != null ? p.getTotalCostUsdc() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 校验对冲策略新增指定规模的持仓是否违反全局风控
     *
     * @param proposedHedgeSizeUsdc 拟开仓金额
     * @return 校验通过返回 true，否则返回 false 并打印日志
     */
    public boolean checkGlobalExposure(BigDecimal proposedHedgeSizeUsdc) {
        BigDecimal globalMax = properties.getGlobalMaxTotalExposureUsdc();
        if (globalMax == null || globalMax.compareTo(BigDecimal.ZERO) <= 0) {
            return true; // 未配置全局限制
        }

        BigDecimal followExp = currentFollowExposureUsdc();
        BigDecimal hedgeExp = currentHedgeExposureUsdc();
        BigDecimal newHedgeTotal = hedgeExp.add(proposedHedgeSizeUsdc);
        BigDecimal newGlobalTotal = followExp.add(newHedgeTotal);

        // 检查1：全局总敞口
        if (newGlobalTotal.compareTo(globalMax) > 0) {
            log.warn("[GlobalRiskGuard] BLOCKED: global exposure {}{} would exceed max {} (follow={}, hedgeCurrent={}, proposed={})",
                    newGlobalTotal.compareTo(globalMax) > 0 ? "+" : "", proposedHedgeSizeUsdc,
                    globalMax, followExp, hedgeExp, proposedHedgeSizeUsdc);
            return false;
        }

        // 检查2：对冲占全局比例
        BigDecimal maxHedgeShare = properties.getMaxHedgeShareOfGlobalExposure();
        if (maxHedgeShare != null && maxHedgeShare.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal hedgeShare = newHedgeTotal.divide(newGlobalTotal, 4, RoundingMode.HALF_UP);
            if (hedgeShare.compareTo(maxHedgeShare) > 0) {
                log.warn("[GlobalRiskGuard] BLOCKED: hedge share {:.2%} would exceed max {:.2%} (hedgeTotal={}, globalTotal={})",
                        hedgeShare, maxHedgeShare, newHedgeTotal, newGlobalTotal);
                return false;
            }
        }

        return true;
    }

    /**
     * 返回当前全局风控概览（供 Dashboard 展示）
     */
    public GlobalExposureSummary getSummary() {
        BigDecimal followExp = currentFollowExposureUsdc();
        BigDecimal hedgeExp = currentHedgeExposureUsdc();
        return new GlobalExposureSummary(followExp, hedgeExp, followExp.add(hedgeExp),
                properties.getGlobalMaxTotalExposureUsdc());
    }

    public record GlobalExposureSummary(
            BigDecimal followExposureUsdc,
            BigDecimal hedgeExposureUsdc,
            BigDecimal totalExposureUsdc,
            BigDecimal globalMaxExposureUsdc) {
    }
}
