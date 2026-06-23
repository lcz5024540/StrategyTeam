package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单测：2026-06-05 补充场景库新增的两道 fail-closed 门（纯静态 helper）。
 * G15 临近结算「高价低赔率」门、G17 确定性净成本门。
 */
class SignalRiskGateG15G17Test {

    private static final long NOW = 1_700_000_000_000L;

    // ───────────────────── G15 高价低赔率门 ─────────────────────

    @Test
    void g15_nearExpiryHighBuyPrice_rejected() {
        // 距结算 1h（≤24h）+ BUY 价 0.96（≥0.95）→ 命中拒单
        String r = SmartWalletSignalRiskService.evaluateHighPriceLowReward(
                true, "BUY", "STANDARD", NOW + 3600_000L, NOW,
                new BigDecimal("0.96"), 24, new BigDecimal("0.95"));
        assertNotNull(r);
        assertTrue(r.startsWith("G15_HIGH_PRICE_LOW_REWARD"));
    }

    @Test
    void g15_priceBelowThreshold_passes() {
        // 价 0.80 < 0.95 → 放行（赔率仍可接受）
        assertNull(SmartWalletSignalRiskService.evaluateHighPriceLowReward(
                true, "BUY", "STANDARD", NOW + 3600_000L, NOW,
                new BigDecimal("0.80"), 24, new BigDecimal("0.95")));
    }

    @Test
    void g15_farFromExpiry_passes() {
        // 剩余 100h > 24h → 放行（长线高价仍有奔向 1.0 的时间）
        assertNull(SmartWalletSignalRiskService.evaluateHighPriceLowReward(
                true, "BUY", "STANDARD", NOW + 100L * 3600_000L, NOW,
                new BigDecimal("0.97"), 24, new BigDecimal("0.95")));
    }

    @Test
    void g15_sellOrFollowExit_notBlocked() {
        // SELL 不拦
        assertNull(SmartWalletSignalRiskService.evaluateHighPriceLowReward(
                true, "SELL", "STANDARD", NOW + 3600_000L, NOW,
                new BigDecimal("0.97"), 24, new BigDecimal("0.95")));
        // 跟随退出不拦（BUY 但 entryLane=DYNAMIC_FOLLOW_EXIT，对手 BUY 先平场景）
        assertNull(SmartWalletSignalRiskService.evaluateHighPriceLowReward(
                true, "BUY", "DYNAMIC_FOLLOW_EXIT", NOW + 3600_000L, NOW,
                new BigDecimal("0.97"), 24, new BigDecimal("0.95")));
    }

    @Test
    void g15_disabledOrAlreadyExpired_passes() {
        // 门关闭
        assertNull(SmartWalletSignalRiskService.evaluateHighPriceLowReward(
                false, "BUY", "STANDARD", NOW + 3600_000L, NOW,
                new BigDecimal("0.97"), 24, new BigDecimal("0.95")));
        // 已过期（remaining ≤ 0）交给 G6，不重复拦
        assertNull(SmartWalletSignalRiskService.evaluateHighPriceLowReward(
                true, "BUY", "STANDARD", NOW - 1000L, NOW,
                new BigDecimal("0.97"), 24, new BigDecimal("0.95")));
    }

    // ───────────────────── G17 净成本门 ─────────────────────

    @Test
    void g17_normalStake_passes() {
        // stake=50, fee=2%×2=4%, gas=0.05 → cost=2.05, ratio=4.1% ≤ 10% → 放行
        assertNull(SmartWalletSignalRiskService.evaluateNetOfCost(
                true, "BUY", "STANDARD", new BigDecimal("50"),
                new BigDecimal("0.02"), new BigDecimal("0.05"),
                new BigDecimal("1.00"), new BigDecimal("0.10")));
    }

    @Test
    void g17_dustStake_costDominates_rejected() {
        // stake=0.5, fee 4%×0.5=0.02, gas=0.05 → cost=0.07, ratio=14% > 10% → 拒（粉尘）
        String r = SmartWalletSignalRiskService.evaluateNetOfCost(
                true, "BUY", "STANDARD", new BigDecimal("0.50"),
                new BigDecimal("0.02"), new BigDecimal("0.05"),
                new BigDecimal("1.00"), new BigDecimal("0.10"));
        assertNotNull(r);
        assertTrue(r.startsWith("G17_NET_OF_COST"));
    }

    @Test
    void g17_gasSpike_rejected() {
        // gas=1.5 > maxGas=1.0 → gas 尖峰拒单（优先于比例判定）
        String r = SmartWalletSignalRiskService.evaluateNetOfCost(
                true, "BUY", "STANDARD", new BigDecimal("50"),
                new BigDecimal("0.02"), new BigDecimal("1.50"),
                new BigDecimal("1.00"), new BigDecimal("0.10"));
        assertNotNull(r);
        assertTrue(r.startsWith("G17_GAS_SPIKE"));
    }

    @Test
    void g17_followExitOrSell_notBlocked() {
        assertNull(SmartWalletSignalRiskService.evaluateNetOfCost(
                true, "BUY", "DYNAMIC_FOLLOW_EXIT", new BigDecimal("0.50"),
                new BigDecimal("0.02"), new BigDecimal("0.05"),
                new BigDecimal("1.00"), new BigDecimal("0.10")));
        assertNull(SmartWalletSignalRiskService.evaluateNetOfCost(
                true, "SELL", "STANDARD", new BigDecimal("0.50"),
                new BigDecimal("0.02"), new BigDecimal("0.05"),
                new BigDecimal("1.00"), new BigDecimal("0.10")));
    }

    @Test
    void g17_disabledOrZeroStake_passes() {
        assertNull(SmartWalletSignalRiskService.evaluateNetOfCost(
                false, "BUY", "STANDARD", new BigDecimal("0.50"),
                new BigDecimal("0.02"), new BigDecimal("0.05"),
                new BigDecimal("1.00"), new BigDecimal("0.10")));
        assertNull(SmartWalletSignalRiskService.evaluateNetOfCost(
                true, "BUY", "STANDARD", BigDecimal.ZERO,
                new BigDecimal("0.02"), new BigDecimal("0.05"),
                new BigDecimal("1.00"), new BigDecimal("0.10")));
    }

    // ───────────────────── G16 市场异常波动熔断（C3）─────────────────────

    private static final List<BigDecimal> bigSwing = Arrays.asList(
            new BigDecimal("0.30"), new BigDecimal("0.42"), new BigDecimal("0.55")); // 极差 0.25
    private static final List<BigDecimal> smallSwing = Arrays.asList(
            new BigDecimal("0.30"), new BigDecimal("0.34"), new BigDecimal("0.40")); // 极差 0.10

    @Test
    void g16_largeSwing_halts() {
        // 5min 内绝对摆动 0.25 > 0.20 → 熔断
        String r = SmartWalletSignalRiskService.evaluateVolatilityHalt(
                true, "BUY", "STANDARD", bigSwing, 3, new BigDecimal("0.20"));
        assertNotNull(r);
        assertTrue(r.startsWith("G16_MARKET_VOLATILITY_HALT"));
    }

    @Test
    void g16_smallSwing_passes() {
        assertNull(SmartWalletSignalRiskService.evaluateVolatilityHalt(
                true, "BUY", "STANDARD", smallSwing, 3, new BigDecimal("0.20")));
    }

    @Test
    void g16_insufficientSamples_passes() {
        // 仅 2 个有效价但 minSamples=3 → 样本不足，不熔断（fail-open，避免单笔噪声误杀）
        List<BigDecimal> twoPts = Arrays.asList(new BigDecimal("0.30"), new BigDecimal("0.60"));
        assertNull(SmartWalletSignalRiskService.evaluateVolatilityHalt(
                true, "BUY", "STANDARD", twoPts, 3, new BigDecimal("0.20")));
    }

    @Test
    void g16_sellOrFollowExit_notHalted() {
        // 出场方向不熔断（波动中我们要能出得来）
        assertNull(SmartWalletSignalRiskService.evaluateVolatilityHalt(
                true, "SELL", "STANDARD", bigSwing, 3, new BigDecimal("0.20")));
        assertNull(SmartWalletSignalRiskService.evaluateVolatilityHalt(
                true, "BUY", "DYNAMIC_FOLLOW_EXIT", bigSwing, 3, new BigDecimal("0.20")));
    }

    @Test
    void g16_disabledOrNoThreshold_passes() {
        assertNull(SmartWalletSignalRiskService.evaluateVolatilityHalt(
                false, "BUY", "STANDARD", bigSwing, 3, new BigDecimal("0.20")));
        assertNull(SmartWalletSignalRiskService.evaluateVolatilityHalt(
                true, "BUY", "STANDARD", bigSwing, 3, BigDecimal.ZERO));
    }

    // ───────────────────── E1 资金曲线自适应仓位 ─────────────────────

    @Test
    void e1_multiplier_belowStart_isOne() {
        assertEquals(BigDecimal.ONE, SmartWalletSignalRiskService.computeEquityCurveStakeMultiplier(
                new BigDecimal("0.03"), new BigDecimal("0.05"), new BigDecimal("0.20"), new BigDecimal("0.30")));
    }

    @Test
    void e1_multiplier_atOrAboveMax_isFloor() {
        assertEquals(new BigDecimal("0.30"), SmartWalletSignalRiskService.computeEquityCurveStakeMultiplier(
                new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("0.20"), new BigDecimal("0.30")));
    }

    @Test
    void e1_multiplier_midpoint_linearInterpolation() {
        // dd=0.125 处于 [0.05,0.20] 中点 → 1 - 0.5*(1-0.3) = 0.65
        assertEquals(new BigDecimal("0.6500"), SmartWalletSignalRiskService.computeEquityCurveStakeMultiplier(
                new BigDecimal("0.125"), new BigDecimal("0.05"), new BigDecimal("0.20"), new BigDecimal("0.30")));
    }

    @Test
    void e1_multiplier_nullDrawdown_isOne() {
        assertEquals(BigDecimal.ONE, SmartWalletSignalRiskService.computeEquityCurveStakeMultiplier(
                null, new BigDecimal("0.05"), new BigDecimal("0.20"), new BigDecimal("0.30")));
    }

    @Test
    void e1_drawdownRate_fromPnlCurve() {
        // 累计 10, -20, -15 → 峰值 10, 当前 -15, 回撤 25 / 基数 500 = 0.05
        List<BigDecimal> pnls = Arrays.asList(new BigDecimal("10"), new BigDecimal("-30"), new BigDecimal("5"));
        assertEquals(new BigDecimal("0.050000"), SmartWalletSignalRiskService.computeDrawdownRateFromDailyPnls(
                pnls, new BigDecimal("500")));
    }

    @Test
    void e1_drawdownRate_noDrawdownOrNoBase_isZero() {
        // 单调上升 → 无回撤
        assertEquals(BigDecimal.ZERO, SmartWalletSignalRiskService.computeDrawdownRateFromDailyPnls(
                Arrays.asList(new BigDecimal("10"), new BigDecimal("20")), new BigDecimal("500")));
        // 基数 ≤ 0 → 0
        assertEquals(BigDecimal.ZERO, SmartWalletSignalRiskService.computeDrawdownRateFromDailyPnls(
                Arrays.asList(new BigDecimal("-50")), BigDecimal.ZERO));
        // 空 → 0
        assertEquals(BigDecimal.ZERO, SmartWalletSignalRiskService.computeDrawdownRateFromDailyPnls(
                null, new BigDecimal("500")));
    }

    // ───────────────────── B1 刷量 leader 检测 ─────────────────────

    @Test
    void b1_highChurnNearFlatNet_flagged() {
        // gross 1000, net 20 → ratio 0.02 < 0.05, trades 10 ≥ 8, gross ≥ 500 → 疑似刷量
        String r = SmartWalletSignalRiskService.evaluateWashSuspicion(
                true, "BUY", "STANDARD", new BigDecimal("1000"), new BigDecimal("20"), 10,
                8, new BigDecimal("500"), new BigDecimal("0.05"));
        assertNotNull(r);
        assertTrue(r.startsWith("G2_SUSPECTED_WASH"));
    }

    @Test
    void b1_directionalNet_passes() {
        // net 200 / gross 1000 = 0.2 > 0.05 → 有方向性，不判刷量
        assertNull(SmartWalletSignalRiskService.evaluateWashSuspicion(
                true, "BUY", "STANDARD", new BigDecimal("1000"), new BigDecimal("200"), 10,
                8, new BigDecimal("500"), new BigDecimal("0.05")));
    }

    @Test
    void b1_insufficientTradesOrGross_passes() {
        // 笔数不足
        assertNull(SmartWalletSignalRiskService.evaluateWashSuspicion(
                true, "BUY", "STANDARD", new BigDecimal("1000"), new BigDecimal("20"), 5,
                8, new BigDecimal("500"), new BigDecimal("0.05")));
        // gross 不足
        assertNull(SmartWalletSignalRiskService.evaluateWashSuspicion(
                true, "BUY", "STANDARD", new BigDecimal("300"), new BigDecimal("5"), 10,
                8, new BigDecimal("500"), new BigDecimal("0.05")));
    }

    @Test
    void b1_sellOrFollowExitOrDisabled_passes() {
        assertNull(SmartWalletSignalRiskService.evaluateWashSuspicion(
                true, "SELL", "STANDARD", new BigDecimal("1000"), new BigDecimal("20"), 10,
                8, new BigDecimal("500"), new BigDecimal("0.05")));
        assertNull(SmartWalletSignalRiskService.evaluateWashSuspicion(
                true, "BUY", "DYNAMIC_FOLLOW_EXIT", new BigDecimal("1000"), new BigDecimal("20"), 10,
                8, new BigDecimal("500"), new BigDecimal("0.05")));
        assertNull(SmartWalletSignalRiskService.evaluateWashSuspicion(
                false, "BUY", "STANDARD", new BigDecimal("1000"), new BigDecimal("20"), 10,
                8, new BigDecimal("500"), new BigDecimal("0.05")));
    }
}
