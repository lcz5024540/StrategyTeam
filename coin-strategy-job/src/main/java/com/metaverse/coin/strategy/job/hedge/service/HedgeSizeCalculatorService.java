package com.metaverse.coin.strategy.job.hedge.service;

import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.hedge.model.HedgeOpportunity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 对冲仓位计算服务
 *
 * 策略：等量双边建仓（delta 中性）+ 半 Kelly 资金管理
 *
 * 推导：
 *   买入 Q 股 YES（单价 ask_yes） + Q 股 NO（单价 ask_no）
 *   总成本  = Q × (ask_yes + ask_no)
 *   必然收入 = Q × 1.0   （一边赢即得 $1/股）
 *   毛利    = Q × probDeviation
 *
 * Kelly 公式适配（二元套利场景下 b ≈ probDeviation / (1 - probDeviation)）：
 *   f* = edge / b = probDeviation²/ (1 - probDeviation) ≈ probDeviation（小边际下近似）
 *   实际比例 = f* × kellyFraction（半 Kelly）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HedgeSizeCalculatorService {

    private final HedgeProperties properties;

    /**
     * 计算建议投入 USDC（双边合计）
     *
     * @param opportunity 套利机会
     * @param availableCapital 当前可用资金
     * @return 建议投入 USDC（单位：双边总成本）
     */
    public BigDecimal calculatePositionUsdc(HedgeOpportunity opportunity, BigDecimal availableCapital) {
        BigDecimal edge = opportunity.getNetEdgeRate();
        BigDecimal totalPrice = opportunity.getYesBestAsk().add(opportunity.getNoBestAsk());

        // Kelly 分数：f* ≈ edge / (1 - minPrice)，使用 totalPrice 近似分母
        BigDecimal denominator = BigDecimal.ONE.subtract(
                opportunity.getYesBestAsk().min(opportunity.getNoBestAsk()));
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            denominator = new BigDecimal("0.50");
        }

        BigDecimal kellyFrac = edge.divide(denominator, 8, RoundingMode.HALF_UP)
                .multiply(properties.getKellyFraction())
                .setScale(6, RoundingMode.HALF_UP);

        // 对 Kelly 分数限制上界 (max 30%)，防止极端值
        if (kellyFrac.compareTo(new BigDecimal("0.30")) > 0) {
            kellyFrac = new BigDecimal("0.30");
        }

        BigDecimal kellySize = availableCapital.multiply(kellyFrac).setScale(4, RoundingMode.HALF_UP);

        // 应用配置上下限
        BigDecimal size = kellySize
                .min(properties.getMaxSinglePositionUsdc())
                .max(properties.getMinSinglePositionUsdc());

        // 不得超过可用资金
        size = size.min(availableCapital);

        log.debug("[HedgeSizeCalc] market={} edge={} kelly={} size={}",
                opportunity.getMarketId(), edge, kellyFrac, size);
        return size;
    }

    /**
     * 根据总投入 USDC 计算双边等量数量 Q
     *
     *   Q = totalUsdc / (ask_yes + ask_no)
     */
    public BigDecimal calculateQuantity(HedgeOpportunity opportunity, BigDecimal totalUsdc) {
        BigDecimal totalPrice = opportunity.getYesBestAsk().add(opportunity.getNoBestAsk());
        if (totalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return totalUsdc.divide(totalPrice, 4, RoundingMode.HALF_DOWN);
    }

    /**
     * 预估理论利润 USDC
     *   profit = Q × (1 - ask_yes - ask_no) - Q × totalFeeRate
     *          = Q × netEdgeRate
     */
    public BigDecimal estimateProfitUsdc(HedgeOpportunity opportunity, BigDecimal quantity) {
        return quantity.multiply(opportunity.getNetEdgeRate()).setScale(4, RoundingMode.HALF_UP);
    }
}
