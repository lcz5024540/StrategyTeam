package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.stats.WinRateStatistics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 单测：Beta 后验 / Wilson 下界可信胜率（2026-06-05 方案 §2.3 worked example）。
 * 期望值由独立 JDK 运行同公式得到（4 位小数 HALF_UP）。
 */
class WinRateStatisticsTest {

    // ───────── Wilson 下界（z=1.96 双侧95%，对照方案文档示例）─────────

    @Test
    void wilson_smallSample_heavilyPenalized() {
        // 7胜3负：点估计 0.70 → 下界 0.3968
        assertEquals(new BigDecimal("0.3968"), WinRateStatistics.wilsonLowerBound(7, 3, 1.96));
    }

    @Test
    void wilson_largeSample_tighter() {
        // 70胜30负：同为 0.70 → 下界 0.6041（样本量区分度）
        assertEquals(new BigDecimal("0.6041"), WinRateStatistics.wilsonLowerBound(70, 30, 1.96));
    }

    @Test
    void wilson_oneWinZeroLoss_notHundredPercent() {
        // 1胜0负：点估计 100% → 下界仅 0.2065，假高手不再霸榜
        assertEquals(new BigDecimal("0.2065"), WinRateStatistics.wilsonLowerBound(1, 0, 1.96));
    }

    @Test
    void wilson_defaultZ_oneSided95() {
        // 默认 z=1.645（单侧95%）：7胜3负 → 0.4417
        assertEquals(new BigDecimal("0.4417"), WinRateStatistics.wilsonLowerBound(7, 3));
    }

    @Test
    void wilson_noData_returnsNull() {
        assertNull(WinRateStatistics.wilsonLowerBound(0, 0));
        assertNull(WinRateStatistics.wilsonLowerBound(-1, 5, 1.96));
        assertNull(WinRateStatistics.wilsonLowerBound(5, 3, 0));
    }

    // ───────── Beta 后验均值（先验 m=4,p0=0.5 即 Beta(2,2)，对照文档示例）─────────

    @Test
    void beta_shrinksSmallSampleTowardPrior() {
        // 7胜3负 → (2+7)/(4+10) = 0.6429（从 0.70 向 0.5 收缩）
        assertEquals(new BigDecimal("0.6429"), WinRateStatistics.betaPosteriorMean(7, 3, 4, 0.5));
    }

    @Test
    void beta_largeSample_barelyShrunk() {
        // 70胜30负 → 72/104 = 0.6923
        assertEquals(new BigDecimal("0.6923"), WinRateStatistics.betaPosteriorMean(70, 30, 4, 0.5));
    }

    @Test
    void beta_oneWinZeroLoss_pulledToPrior() {
        // 1胜0负 → 3/5 = 0.60（而非 100%）
        assertEquals(new BigDecimal("0.6000"), WinRateStatistics.betaPosteriorMean(1, 0, 4, 0.5));
    }

    @Test
    void beta_defaults_m8_center05() {
        // 默认 Beta(4,4)：7胜3负 → 11/18 = 0.6111；无数据 → 先验中心 0.5
        assertEquals(new BigDecimal("0.6111"), WinRateStatistics.betaPosteriorMean(7, 3));
        assertEquals(new BigDecimal("0.5000"), WinRateStatistics.betaPosteriorMean(0, 0));
    }
}
