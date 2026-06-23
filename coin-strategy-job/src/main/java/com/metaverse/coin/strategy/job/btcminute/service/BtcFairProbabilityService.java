package com.metaverse.coin.strategy.job.btcminute.service;

import com.metaverse.coin.strategy.job.btcminute.config.BtcMinuteStrategyProperties;
import com.metaverse.coin.strategy.job.btcminute.model.BtcMinuteMarketSpec;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * BTC 公允概率计算服务。
 *
 * <p>综合动量信号和波动率模型，计算 Polymarket BTC 短期市场的公允概率。</p>
 *
 * <p>核心公式：</p>
 * <pre>
 * baseProbability = 波动率模型 P(BTC > targetPrice in T minutes)
 * momentumAdjustment = f(RSI, EMA diff, price change rate)
 * fairProbability = clamp(baseProbability + momentumAdjustment, 0.02, 0.98)
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BtcFairProbabilityService {

    private static final String LOG_PREFIX = "[BTC_FAIR_PROB]";

    private final BtcPriceFeedService priceFeedService;
    private final BtcMomentumCalculator momentumCalculator;
    private final BtcVolatilityEstimator volatilityEstimator;

    @Data
    @Builder
    public static class FairProbResult {
        /** 波动率模型基础概率 */
        private BigDecimal baseProbability;
        /** 动量调整值 */
        private BigDecimal momentumAdjustment;
        /** 最终公允概率 */
        private BigDecimal fairProbability;
        /** 当前 BTC 价格 */
        private BigDecimal btcPrice;
        /** RSI */
        private BigDecimal rsi;
        /** EMA diff */
        private BigDecimal emaDiff;
        /** 波动率 */
        private BigDecimal volatility;
        /** 数据是否就绪 */
        private boolean ready;
    }

    /**
     * 为指定 BTC 短期市场计算公允概率。
     *
     * @param spec 市场规格（目标价格、方向、时间窗口）
     * @return 公允概率结果
     */
    public FairProbResult calculate(BtcMinuteMarketSpec spec) {
        BigDecimal btcPrice = priceFeedService.getLatestPrice();
        if (btcPrice == null) {
            log.warn("{} BTC 价格数据不可用", LOG_PREFIX);
            return FairProbResult.builder().ready(false).build();
        }

        // 1. 计算动量指标
        BtcMomentumCalculator.MomentumResult momentum = momentumCalculator.calculate();
        if (!momentum.isDataReady()) {
            log.warn("{} 动量数据不足", LOG_PREFIX);
            return FairProbResult.builder().ready(false).btcPrice(btcPrice).build();
        }

        // 2. 计算距离结算的分钟数
        int minutesToSettlement = spec.getWindowMinutes();
        if (spec.getSettlementTime() != null) {
            minutesToSettlement = (int) Duration.between(Instant.now(), spec.getSettlementTime()).toMinutes();
            minutesToSettlement = Math.max(minutesToSettlement, 1);
        }

        // 3. 波动率模型计算基础概率
        BigDecimal targetPrice = spec.getTargetPrice();
        BigDecimal baseProbability;

        if ("UP".equalsIgnoreCase(spec.getDirection())) {
            // P(BTC > targetPrice in T minutes)
            baseProbability = volatilityEstimator.estimateProbability(btcPrice, targetPrice, minutesToSettlement);
        } else {
            // P(BTC < targetPrice) = 1 - P(BTC > targetPrice)
            baseProbability = BigDecimal.ONE.subtract(
                    volatilityEstimator.estimateProbability(btcPrice, targetPrice, minutesToSettlement));
        }

        // 4. 动量调整
        BigDecimal momentumAdj = calculateMomentumAdjustment(momentum, spec.getDirection());

        // 5. 合成最终概率
        BigDecimal fairProb = baseProbability.add(momentumAdj);
        fairProb = fairProb.max(new BigDecimal("0.02")).min(new BigDecimal("0.98"));
        fairProb = fairProb.setScale(4, RoundingMode.HALF_UP);

        BigDecimal annualizedVol = volatilityEstimator.calculateAnnualizedVolatility();

        return FairProbResult.builder()
                .baseProbability(baseProbability.setScale(4, RoundingMode.HALF_UP))
                .momentumAdjustment(momentumAdj.setScale(4, RoundingMode.HALF_UP))
                .fairProbability(fairProb)
                .btcPrice(btcPrice)
                .rsi(momentum.getRsi())
                .emaDiff(momentum.getEmaDiff())
                .volatility(annualizedVol)
                .ready(true)
                .build();
    }

    // ── 动量调整计算 ──────────────────────────────────────────────────

    /**
     * 根据 RSI 和 EMA 差值计算动量对概率的调整。
     *
     * <ul>
     *   <li>RSI > 70 且方向 UP → 正向加成（趋势延续）</li>
     *   <li>RSI < 30 且方向 DOWN → 正向加成</li>
     *   <li>EMA 快线 > 慢线 且方向 UP → 正向加成</li>
     *   <li>调整值上限 ±0.10（避免过度修正）</li>
     * </ul>
     */
    private BigDecimal calculateMomentumAdjustment(BtcMomentumCalculator.MomentumResult momentum, String direction) {
        BigDecimal adjustment = BigDecimal.ZERO;
        boolean isUp = "UP".equalsIgnoreCase(direction);

        // RSI 信号
        BigDecimal rsi = momentum.getRsi();
        if (rsi != null) {
            if (rsi.compareTo(BigDecimal.valueOf(70)) > 0) {
                // 超买 → UP 方向有延续概率，DOWN 方向有反转概率
                BigDecimal rsiSignal = rsi.subtract(BigDecimal.valueOf(70))
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                adjustment = adjustment.add(isUp ? rsiSignal : rsiSignal.negate());
            } else if (rsi.compareTo(BigDecimal.valueOf(30)) < 0) {
                // 超卖 → DOWN 方向有延续概率，UP 方向有反转概率
                BigDecimal rsiSignal = BigDecimal.valueOf(30).subtract(rsi)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                adjustment = adjustment.add(isUp ? rsiSignal.negate() : rsiSignal);
            }
        }

        // EMA 趋势信号
        BigDecimal emaDiff = momentum.getEmaDiff();
        if (emaDiff != null) {
            BigDecimal btcPrice = priceFeedService.getLatestPrice();
            if (btcPrice != null && btcPrice.compareTo(BigDecimal.ZERO) > 0) {
                // EMA diff 占价格的比例
                BigDecimal emaDiffRatio = emaDiff.divide(btcPrice, 6, RoundingMode.HALF_UP);
                // 放大 10 倍作为调整因子
                BigDecimal emaSignal = emaDiffRatio.multiply(BigDecimal.TEN);
                adjustment = adjustment.add(isUp ? emaSignal : emaSignal.negate());
            }
        }

        // 限制调整范围
        BigDecimal maxAdj = new BigDecimal("0.10");
        return adjustment.max(maxAdj.negate()).min(maxAdj);
    }
}
