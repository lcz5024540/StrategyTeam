package com.metaverse.coin.strategy.job.btcminute.service;

import com.metaverse.coin.strategy.job.btcminute.config.BtcMinuteStrategyProperties;
import com.metaverse.coin.strategy.job.btcminute.model.BtcPriceSnapshot;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * BTC 价格动量计算器。
 *
 * <p>基于 1 分钟 K 线收盘价计算：</p>
 * <ul>
 *   <li>EMA（指数移动平均）快慢线及其差值</li>
 *   <li>RSI（相对强弱指数）</li>
 *   <li>价格变化率</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BtcMomentumCalculator {

    private final BtcMinuteStrategyProperties properties;
    private final BtcPriceFeedService priceFeedService;

    @Data
    @Builder
    public static class MomentumResult {
        /** EMA 快线值 */
        private BigDecimal emaFast;
        /** EMA 慢线值 */
        private BigDecimal emaSlow;
        /** EMA 快慢线差值（正=看涨动量，负=看跌动量） */
        private BigDecimal emaDiff;
        /** RSI 值 [0, 100] */
        private BigDecimal rsi;
        /** 最近 N 根 K 线的价格变化率 */
        private BigDecimal priceChangeRate;
        /** 数据是否充足 */
        private boolean dataReady;
    }

    /**
     * 计算当前的动量指标。
     */
    public MomentumResult calculate() {
        int fastPeriod = properties.getMomentum().getEmaFastPeriod();
        int slowPeriod = properties.getMomentum().getEmaSlowPeriod();
        int rsiPeriod = properties.getMomentum().getRsiPeriod();
        int required = Math.max(slowPeriod, rsiPeriod) + 1;

        List<BtcPriceSnapshot> klines = priceFeedService.getRecentKlines(required + 5);
        if (klines.size() < required) {
            return MomentumResult.builder().dataReady(false).build();
        }

        BigDecimal[] closes = klines.stream()
                .map(BtcPriceSnapshot::getKlineClose)
                .filter(c -> c != null)
                .toArray(BigDecimal[]::new);

        if (closes.length < required) {
            return MomentumResult.builder().dataReady(false).build();
        }

        BigDecimal emaFast = calculateEMA(closes, fastPeriod);
        BigDecimal emaSlow = calculateEMA(closes, slowPeriod);
        BigDecimal emaDiff = emaFast.subtract(emaSlow);
        BigDecimal rsi = calculateRSI(closes, rsiPeriod);

        // 价格变化率：最新 vs 5 根前
        BigDecimal oldPrice = closes[Math.max(0, closes.length - 6)];
        BigDecimal newPrice = closes[closes.length - 1];
        BigDecimal changeRate = oldPrice.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : newPrice.subtract(oldPrice).divide(oldPrice, 8, RoundingMode.HALF_UP);

        return MomentumResult.builder()
                .emaFast(emaFast)
                .emaSlow(emaSlow)
                .emaDiff(emaDiff)
                .rsi(rsi)
                .priceChangeRate(changeRate)
                .dataReady(true)
                .build();
    }

    // ── EMA 计算 ──────────────────────────────────────────────────────

    private BigDecimal calculateEMA(BigDecimal[] prices, int period) {
        if (prices.length < period) return prices[prices.length - 1];

        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));
        BigDecimal oneMinusMultiplier = BigDecimal.ONE.subtract(multiplier);

        // 用前 period 个价格的 SMA 作为初始 EMA
        BigDecimal sma = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sma = sma.add(prices[i]);
        }
        sma = sma.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

        BigDecimal ema = sma;
        for (int i = period; i < prices.length; i++) {
            ema = prices[i].multiply(multiplier).add(ema.multiply(oneMinusMultiplier));
        }
        return ema.setScale(8, RoundingMode.HALF_UP);
    }

    // ── RSI 计算 ──────────────────────────────────────────────────────

    private BigDecimal calculateRSI(BigDecimal[] prices, int period) {
        if (prices.length < period + 1) return BigDecimal.valueOf(50); // 数据不足，返回中性

        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;

        // 初始平均涨幅/跌幅
        for (int i = 1; i <= period; i++) {
            BigDecimal change = prices[i].subtract(prices[i - 1]);
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                totalGain = totalGain.add(change);
            } else {
                totalLoss = totalLoss.add(change.abs());
            }
        }

        BigDecimal avgGain = totalGain.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        BigDecimal avgLoss = totalLoss.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

        // Smoothed RSI
        for (int i = period + 1; i < prices.length; i++) {
            BigDecimal change = prices[i].subtract(prices[i - 1]);
            BigDecimal gain = change.compareTo(BigDecimal.ZERO) > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.compareTo(BigDecimal.ZERO) < 0 ? change.abs() : BigDecimal.ZERO;

            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1)).add(gain)
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1)).add(loss)
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        }

        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100);
        }

        BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
        BigDecimal rsi = BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 4, RoundingMode.HALF_UP));
        return rsi;
    }
}
