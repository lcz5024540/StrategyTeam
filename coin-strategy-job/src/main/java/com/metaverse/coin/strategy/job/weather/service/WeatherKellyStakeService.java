package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Kelly 公式动态仓位管理。
 *
 * 分数 Kelly：f* = [(p × b - q) / b] × kellyFraction × confidence
 *
 * 其中：
 *   p = fair value（我们估计的真实概率）
 *   q = 1 - p
 *   b = (1 / marketPrice) - 1（赔率）
 *   kellyFraction = 按 lead-hour tier 配置（0.15 ~ 0.40）
 *   confidence = 模型置信度，进一步缩减仓位
 *
 * 上限约束：
 *   - 单笔不超过资金池的 maxStakeRatio（按 tier 配置）
 *   - 不超过 maxSingleStakeUsdc
 *   - 不超过可用资金
 *
 * 下限：1 USDC（Polymarket 最低交易额）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherKellyStakeService {

    private final WeatherArbitrageProperties properties;
    private final WeatherCapitalPoolService capitalPoolService;

    public BigDecimal calculateStake(WeatherArbitrageSignal signal, int leadHours) {
        if (signal.getSide() == WeatherArbitrageSignal.Side.PASS) {
            return BigDecimal.ZERO;
        }

        BigDecimal p = signal.getFairValue();
        BigDecimal marketPrice = signal.getMarketPrice();
        BigDecimal confidence = signal.getConfidence();

        if (p == null || marketPrice == null || confidence == null) return BigDecimal.ZERO;
        if (marketPrice.compareTo(BigDecimal.ZERO) <= 0 || marketPrice.compareTo(BigDecimal.ONE) >= 0) {
            return BigDecimal.ZERO;
        }

        // 赔率 b = (1/price) - 1
        BigDecimal b = BigDecimal.ONE.divide(marketPrice, 8, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        if (b.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        // 全 Kelly：f = (p*b - q) / b
        BigDecimal q = BigDecimal.ONE.subtract(p);
        BigDecimal fullKelly = p.multiply(b).subtract(q)
                .divide(b, 8, RoundingMode.HALF_UP);

        if (fullKelly.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[WeatherKelly] Negative EV. p={}, price={}, b={}, kelly={}",
                    p, marketPrice, b, fullKelly);
            return BigDecimal.ZERO; // 负 EV，不下注
        }

        // 分数 Kelly × confidence
        BigDecimal fraction = resolveKellyFraction(leadHours);
        BigDecimal availableCapital = properties.getCapitalPoolUsdc()
                .subtract(capitalPoolService.allocatedUsdc())
                .max(BigDecimal.ZERO);

        BigDecimal kellyStake = fullKelly
                .multiply(fraction)
                .multiply(confidence)
                .multiply(availableCapital)
                .setScale(2, RoundingMode.HALF_UP);

        // Edge decay factor：edge 刚刚过阈值时额外缩减
        BigDecimal minEdge = resolveMinEdge(leadHours);
        BigDecimal edge = signal.getEdge() == null ? BigDecimal.ZERO : signal.getEdge();
        if (edge.compareTo(BigDecimal.ZERO) > 0 && minEdge.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal edgeDecay = edge.subtract(minEdge)
                    .divide(minEdge, 4, RoundingMode.HALF_UP)
                    .add(new BigDecimal("0.5"))
                    .min(BigDecimal.ONE)
                    .max(new BigDecimal("0.3"));
            kellyStake = kellyStake.multiply(edgeDecay).setScale(2, RoundingMode.HALF_UP);
        }

        // 上限约束
        BigDecimal maxStake = properties.getMaxSingleStakeUsdc();
        BigDecimal maxByRatio = availableCapital.multiply(resolveMaxStakeRatio(leadHours))
                .setScale(2, RoundingMode.HALF_UP);
        kellyStake = kellyStake.min(maxStake).min(maxByRatio);

        // 下限：1 USDC
        BigDecimal minStake = BigDecimal.ONE;
        if (kellyStake.compareTo(minStake) < 0) {
            // 如果 Kelly 建议低于最低限额，但 EV 为正，还是下最低限额
            if (fullKelly.compareTo(BigDecimal.ZERO) > 0 && confidence.compareTo(new BigDecimal("0.60")) > 0) {
                kellyStake = minStake;
            } else {
                return BigDecimal.ZERO;
            }
        }

        log.debug("[WeatherKelly] leadH={} p={} price={} b={} fullKelly={} fraction={} confidence={} stake={}",
                leadHours, p.setScale(4, RoundingMode.HALF_UP), marketPrice, b.setScale(4, RoundingMode.HALF_UP),
                fullKelly.setScale(4, RoundingMode.HALF_UP), fraction, confidence.setScale(4, RoundingMode.HALF_UP),
                kellyStake);

        return kellyStake;
    }

    private BigDecimal resolveKellyFraction(int leadHours) {
        if (leadHours < 6)  return properties.getUltraNearKellyFraction();
        if (leadHours < 24) return properties.getNearKellyFraction();
        if (leadHours < 72) return properties.getMediumKellyFraction();
        return properties.getFarKellyFraction();
    }

    private BigDecimal resolveMinEdge(int leadHours) {
        if (leadHours < 6)  return properties.getUltraNearMinEdge();
        if (leadHours < 24) return properties.getNearMinEdge();
        if (leadHours < 72) return properties.getMediumMinEdge();
        return properties.getFarMinEdge();
    }

    private BigDecimal resolveMaxStakeRatio(int leadHours) {
        if (leadHours < 6)  return new BigDecimal("0.15");
        if (leadHours < 24) return new BigDecimal("0.10");
        if (leadHours < 72) return new BigDecimal("0.08");
        return new BigDecimal("0.05");
    }
}
