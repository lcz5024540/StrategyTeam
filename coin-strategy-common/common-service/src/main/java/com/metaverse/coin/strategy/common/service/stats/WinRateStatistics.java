package com.metaverse.coin.strategy.common.service.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Beta 后验 / Wilson 下界胜率统计（2026-06-05 方案，详见
 * docs/polymarket/方案-Beta后验与Wilson胜率-2026-06-05.md）。
 *
 * <p>背景：leader 胜率是小样本伯努利比例，点估计 wins/(wins+losses) 不稳且选 TOP-N 有
 * 幸存者偏差（审计 R13）。本类提供两种带样本量惩罚的可信估计：
 * <ul>
 *   <li><b>Wilson 得分区间下界</b> —— 用于「过/不过」门槛比较（保守，n 越小下界越低）；</li>
 *   <li><b>Beta-Bernoulli 后验均值</b> —— 用于排序/收缩（小样本向先验中心收缩）。</li>
 * </ul></p>
 *
 * <p>worked example（z=1.96 双侧95%；Beta 先验 m=4,p0=0.5 即 Beta(2,2)）：
 * 7胜3负 → Wilson LB≈0.3968、Beta 均值=9/14≈0.6429；
 * 70胜30负 → LB≈0.6042、Beta=72/104≈0.6923；
 * 1胜0负 → LB≈0.2065、Beta=3/5=0.60 —— 同为 70%/100% 点估计，小样本被显著惩罚。</p>
 *
 * <p><b>纯 POJO</b>：无 Spring / 无 IO / 无状态，任意策略可直接调用（与 sizing.KellyCalculator 同风格）。
 * 本类常量（单侧95% z=1.645、先验 m=8 中心 0.5）为默认/兜底值；运营可调参数已提升为
 * SmartWalletProperties.winRateWilsonZ / winRatePriorStrength / winRatePriorMean，由
 * ProfileTruthRefreshService 计算时按配置传入（配置缺省时回退到这里的默认常量）。</p>
 */
public final class WinRateStatistics {

    /** 默认 Wilson 置信系数：单侧 95%（z=1.645）。门槛比较建议单侧（只关心下界）。 */
    public static final double DEFAULT_WILSON_Z = 1.645;
    /** 默认 Beta 先验伪计数 m = α₀+β₀（相当于先验贡献 m 笔决断样本）。 */
    public static final double DEFAULT_PRIOR_STRENGTH = 8.0;
    /** 默认 Beta 先验中心 p₀（无群体统计时取 0.5；校准期可换成同期 leader 群体均胜率）。 */
    public static final double DEFAULT_PRIOR_MEAN = 0.5;

    private static final int SCALE = 4;

    private WinRateStatistics() {
    }

    /**
     * Wilson 得分区间下界。
     * <pre>
     * LB = [ p̂ + z²/(2n) − z·√( p̂(1−p̂)/n + z²/(4n²) ) ] / (1 + z²/n)
     * </pre>
     * @return 下界 ∈ [0,1]（4 位小数 HALF_UP）；n=0 / 非法入参返回 null（调用方按「未知」处理）。
     */
    public static BigDecimal wilsonLowerBound(long wins, long losses, double z) {
        long n = wins + losses;
        if (wins < 0 || losses < 0 || n <= 0 || z <= 0 || Double.isNaN(z) || Double.isInfinite(z)) {
            return null;
        }
        double p = (double) wins / n;
        double z2 = z * z;
        double denom = 1.0 + z2 / n;
        double center = p + z2 / (2.0 * n);
        double margin = z * Math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * n));
        double lb = (center - margin) / denom;
        if (lb < 0.0) {
            lb = 0.0;
        }
        if (lb > 1.0) {
            lb = 1.0;
        }
        return BigDecimal.valueOf(lb).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Wilson 下界（默认单侧 95%，z=1.645）。 */
    public static BigDecimal wilsonLowerBound(long wins, long losses) {
        return wilsonLowerBound(wins, losses, DEFAULT_WILSON_Z);
    }

    /**
     * Beta-Bernoulli 后验均值（收缩估计）。
     * <pre>
     * 先验 Beta(α₀,β₀)，α₀=m·p₀、β₀=m·(1−p₀)
     * 后验均值 = (α₀ + wins) / (m + n)
     * </pre>
     * @return 均值 ∈ [0,1]（4 位小数 HALF_UP）；非法入参 / 分母≤0 返回 null。
     */
    public static BigDecimal betaPosteriorMean(long wins, long losses, double priorStrength, double priorMean) {
        long n = wins + losses;
        if (wins < 0 || losses < 0 || n < 0
                || Double.isNaN(priorStrength) || Double.isInfinite(priorStrength)
                || Double.isNaN(priorMean) || Double.isInfinite(priorMean)) {
            return null;
        }
        double m = Math.max(0.0, priorStrength);
        double p0 = Math.min(1.0, Math.max(0.0, priorMean));
        double alpha0 = m * p0;
        double denom = m + n;
        if (denom <= 0.0) {
            return null;
        }
        double mean = (alpha0 + wins) / denom;
        if (mean < 0.0) {
            mean = 0.0;
        }
        if (mean > 1.0) {
            mean = 1.0;
        }
        return BigDecimal.valueOf(mean).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Beta 后验均值（默认先验 m=8、中心 0.5，即 Beta(4,4)）。 */
    public static BigDecimal betaPosteriorMean(long wins, long losses) {
        return betaPosteriorMean(wins, losses, DEFAULT_PRIOR_STRENGTH, DEFAULT_PRIOR_MEAN);
    }
}
