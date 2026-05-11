package com.metaverse.coin.strategy.job.btcminute.service;

import com.metaverse.coin.strategy.job.btcminute.config.BtcMinuteStrategyProperties;
import com.metaverse.coin.strategy.job.btcminute.model.BtcPriceSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * BTC 短期波动率估计器。
 *
 * <p>基于最近 N 分钟的 K 线收盘价计算已实现波动率（Realized Volatility），
 * 用于估计 BTC 价格在未来 T 分钟内的波动范围。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BtcVolatilityEstimator {

    private final BtcMinuteStrategyProperties properties;
    private final BtcPriceFeedService priceFeedService;

    /**
     * 计算短期已实现波动率（年化）。
     *
     * @return 年化波动率，如 0.45 表示 45%；数据不足时返回 null
     */
    public BigDecimal calculateAnnualizedVolatility() {
        int windowMinutes = properties.getMomentum().getVolatilityWindowSeconds() / 60;
        List<BtcPriceSnapshot> klines = priceFeedService.getRecentKlines(windowMinutes + 1);

        List<BigDecimal> closes = klines.stream()
                .map(BtcPriceSnapshot::getKlineClose)
                .filter(c -> c != null && c.compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        if (closes.size() < 5) {
            return null; // 数据不足
        }

        // 计算对数收益率
        double[] logReturns = new double[closes.size() - 1];
        for (int i = 1; i < closes.size(); i++) {
            double ratio = closes.get(i).doubleValue() / closes.get(i - 1).doubleValue();
            logReturns[i - 1] = Math.log(ratio);
        }

        // 标准差
        double mean = 0;
        for (double lr : logReturns) mean += lr;
        mean /= logReturns.length;

        double sumSqDiff = 0;
        for (double lr : logReturns) {
            double diff = lr - mean;
            sumSqDiff += diff * diff;
        }
        double variance = sumSqDiff / (logReturns.length - 1);
        double stdDev = Math.sqrt(variance);

        // 年化：1 分钟周期 → 年化 = stdDev * sqrt(525600)
        double annualized = stdDev * Math.sqrt(525600);

        return BigDecimal.valueOf(annualized).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 估计 BTC 价格在未来 T 分钟内偏离当前价格超过 X% 的概率。
     *
     * <p>假设对数正态分布，使用正态 CDF 近似。</p>
     *
     * @param currentPrice 当前 BTC 价格
     * @param targetPrice  目标价格
     * @param minutesAhead 未来分钟数
     * @return 概率 [0, 1]
     */
    public BigDecimal estimateProbability(BigDecimal currentPrice, BigDecimal targetPrice, int minutesAhead) {
        BigDecimal annualVol = calculateAnnualizedVolatility();
        if (annualVol == null || annualVol.compareTo(BigDecimal.ZERO) == 0) {
            return new BigDecimal("0.5"); // 数据不足，返回中性
        }

        // 将年化波动率转换为 T 分钟的波动率
        // σ_T = σ_annual * sqrt(T / 525600)
        double sigmaAnnual = annualVol.doubleValue();
        double sigmaT = sigmaAnnual * Math.sqrt((double) minutesAhead / 525600.0);

        // 对数正态模型：P(S_T > K) = Φ(d)
        // d = [ln(S/K) + 0.5*σ²*T] / (σ*√T)
        // 简化：假设 drift ≈ 0（短期内漂移可忽略）
        // d = ln(S/K) / (σ*√T)
        double s = currentPrice.doubleValue();
        double k = targetPrice.doubleValue();

        if (s <= 0 || k <= 0 || sigmaT <= 0) {
            return new BigDecimal("0.5");
        }

        double d = Math.log(s / k) / sigmaT;

        // P(BTC > targetPrice) = Φ(d)
        double prob = normalCDF(d);

        return BigDecimal.valueOf(prob).setScale(6, RoundingMode.HALF_UP);
    }

    // ── 正态 CDF 近似（Abramowitz & Stegun） ──────────────────────────

    private double normalCDF(double x) {
        if (x > 8) return 1.0;
        if (x < -8) return 0.0;

        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;

        int sign = x < 0 ? -1 : 1;
        x = Math.abs(x) / Math.sqrt(2);

        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);

        return 0.5 * (1.0 + sign * y);
    }
}
