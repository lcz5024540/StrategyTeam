package com.metaverse.coin.strategy.common.service.sizing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tier B (第十九批): 通用 Kelly Criterion 仓位计算器, 抽自跟单
 * {@code coin-strategy-job/.../smartwallet/service/KellyStakeCalculator}, 行为完全等价.
 *
 * <p>把跟单专属的 {@code @Component KellyStakeCalculator} 算法上移到 common-service,
 * 为天气策略 / BTC 5min 策略提供「第一天就能用」的共享仓位计算器, 防止重复实现导致
 * 数学不一致 (e.g. 价格边界 0.02 / Kelly fraction 默认 0.3 / consensusMultiplier 行为).</p>
 *
 * <p><b>Formula</b>: f* = (p * b - (1-p)) / b
 * <ul>
 *   <li>p = event win probability (converted from follow-worthiness score if edgeFactor provided)</li>
 *   <li>b = (1 - entryPrice) / entryPrice (payout odds)</li>
 * </ul></p>
 *
 * <p><b>语义保留</b>:
 * <ul>
 *   <li>{@code calibratedFollowProbability == null} → 返回 null (调用方走 legacy fallback)</li>
 *   <li>价格 &lt; 0.02 或 &gt; 0.98 (市场近 resolved) → 返回 ZERO (调用方 EV gate 拒绝)</li>
 *   <li>EV 负 (numerator ≤ 0) → 返回 ZERO</li>
 *   <li>{@code consensusMultiplier} &gt; 1 → 仓位放大 (W2-T3.2 加分项, 不降权)</li>
 *   <li>下界 minOrder, 上界 maxStake; 全 2 位小数 HALF_UP</li>
 * </ul></p>
 *
 * <p><b>纯 POJO 设计</b>: 无 Spring 注解 / 无 IO / 无可变状态, 任意策略可 {@code new} 也可
 * 把跟单 {@code KellyStakeCalculator extends KellyCalculator} 沿用 @Component 入参.</p>
 *
 * <p>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率: ⚪ 全部无影响 — 算法 bit-equivalent,
 * 跟单 hot path 的所有调用方继承 @Component KellyStakeCalculator, Spring bean 装配 / 方法签名
 * 完全保留. 详见 docs 二十八节.</p>
 */
public class KellyCalculator {

    protected static final BigDecimal PRICE_BOUNDARY = new BigDecimal("0.02");
    protected static final BigDecimal HALF = new BigDecimal("0.5");
    protected static final int SCALE = 8;

    /**
     * Compute recommended stake using Kelly Criterion.
     *
     * @param calibratedFollowProbability follow-worthiness score (null → return null for fallback)
     * @param entryPrice                  market entry price [0,1]
     * @param availableCapital            available capital in USDC
     * @param kellyFraction               Kelly fraction / discount factor (e.g. 0.3)
     * @param minOrderUsdc                minimum order size floor
     * @param maxStakeUsdc                maximum stake ceiling
     * @param kellySignalEdgeFactor       edge factor for follow→win-rate conversion (null = use followProb directly)
     * @return recommended stake, or null if probability unknown
     */
    public BigDecimal compute(BigDecimal calibratedFollowProbability,
                              BigDecimal entryPrice,
                              BigDecimal availableCapital,
                              BigDecimal kellyFraction,
                              BigDecimal minOrderUsdc,
                              BigDecimal maxStakeUsdc,
                              BigDecimal kellySignalEdgeFactor) {
        return compute(calibratedFollowProbability, entryPrice, availableCapital,
                kellyFraction, minOrderUsdc, maxStakeUsdc, kellySignalEdgeFactor, null);
    }

    /**
     * Overload accepting an explicit consensus multiplier (W2-T3.2).
     *
     * <p>共识倍率仅作为加分项使用：单实体 → 1.0；2 实体 → 1.20；3+ 实体 → 1.50。
     * 倍率应用在 Kelly 计算后、min/max 截断之前，因此最终 stake 仍受 maxStakeUsdc 限制。</p>
     *
     * @param consensusMultiplier multi-entity consensus stake multiplier (null or ≤1.0 → no boost)
     */
    public BigDecimal compute(BigDecimal calibratedFollowProbability,
                              BigDecimal entryPrice,
                              BigDecimal availableCapital,
                              BigDecimal kellyFraction,
                              BigDecimal minOrderUsdc,
                              BigDecimal maxStakeUsdc,
                              BigDecimal kellySignalEdgeFactor,
                              BigDecimal consensusMultiplier) {
        if (calibratedFollowProbability == null) {
            return null;
        }

        BigDecimal price = entryPrice == null ? BigDecimal.ZERO : entryPrice;
        BigDecimal capital = availableCapital == null ? BigDecimal.ZERO : availableCapital;
        BigDecimal fraction = kellyFraction == null ? new BigDecimal("0.3") : kellyFraction;
        BigDecimal minOrder = minOrderUsdc == null ? BigDecimal.ONE : minOrderUsdc;
        BigDecimal maxStake = maxStakeUsdc == null ? new BigDecimal("150") : maxStakeUsdc;

        // Abnormal price boundary → market nearly resolved, extreme slippage, reject signal
        if (price.compareTo(PRICE_BOUNDARY) < 0 || price.compareTo(BigDecimal.ONE.subtract(PRICE_BOUNDARY)) > 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        // Convert follow-worthiness score to event win probability.
        BigDecimal p;
        if (kellySignalEdgeFactor != null) {
            p = HALF.add(calibratedFollowProbability.subtract(HALF).multiply(kellySignalEdgeFactor))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        } else {
            p = calibratedFollowProbability;
        }

        // b = (1 - price) / price
        BigDecimal odds = BigDecimal.ONE.subtract(price).divide(price, SCALE, RoundingMode.HALF_UP);

        // f* = (p * b - (1-p)) / b
        BigDecimal numerator = p.multiply(odds).subtract(BigDecimal.ONE.subtract(p));
        if (numerator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal kellyRatio = numerator.divide(odds, SCALE, RoundingMode.HALF_UP);

        // stake = capital * fraction * kellyRatio
        BigDecimal stake = capital.multiply(fraction).multiply(kellyRatio).setScale(2, RoundingMode.HALF_UP);

        // W2-T3.2 共识倍率（仅加分，不降权）：单实体 1.0 / 2 实体 1.20 / 3+ 实体 1.50
        if (consensusMultiplier != null && consensusMultiplier.compareTo(BigDecimal.ONE) > 0) {
            stake = stake.multiply(consensusMultiplier).setScale(2, RoundingMode.HALF_UP);
        }

        // Apply floor and ceiling
        if (stake.compareTo(minOrder) < 0) {
            return minOrder.setScale(2, RoundingMode.HALF_UP);
        }
        return stake.min(maxStake).setScale(2, RoundingMode.HALF_UP);
    }
}
