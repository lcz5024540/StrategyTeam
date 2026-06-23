package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polymarket taker fee 动态查询服务。
 *
 * <p>Polymarket 自 2025-03 起对 taker（吃单方）按品类收取 0–1.8% 不等的手续费。
 * 本服务为风控仓位计算提供 <b>预下单</b> 阶段的费率估算，确保 stake 扣减手续费后
 * 的真实资金投入被正确计算。</p>
 *
 * <h3>费率来源优先级</h3>
 * <ol>
 *   <li>运行时缓存（最近一次该 market 的真实成交费率）</li>
 *   <li>配置文件中的品类 → 费率映射 ({@code smart-wallet.fee-schedule.category-fee-rates})</li>
 *   <li>全局默认费率 ({@code smart-wallet.fee-schedule.default-taker-fee-rate}，默认 0.02 = 2%)</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketFeeScheduleService {

    private static final String LOG_PREFIX = "[POLY_FEE]";

    private final SmartWalletProperties properties;

    /**
     * conditionId → 最近一次真实成交的 taker fee rate。
     * 由 {@link #recordObservedFeeRate} 在订单成交后写入，
     * 下次同 market 查询时直接命中。
     */
    private final ConcurrentHashMap<String, BigDecimal> observedFeeRates = new ConcurrentHashMap<>();

    // ── 公共查询 API ──────────────────────────────────────────────────────

    /**
     * 返回指定 market 的预估 taker fee rate。
     *
     * @param conditionId Polymarket market conditionId
     * @param category    market 品类标签（可为 null）
     * @return taker fee rate（如 0.018 表示 1.8%），保证非 null 且 >= 0
     */
    public BigDecimal getTakerFeeRate(String conditionId, String category) {
        // 1. 运行时观测缓存
        if (StringUtils.hasText(conditionId)) {
            BigDecimal observed = observedFeeRates.get(conditionId.toLowerCase());
            if (observed != null) {
                return observed;
            }
        }

        // 2. 品类映射
        SmartWalletProperties.FeeSchedule feeSchedule = properties.getFeeSchedule();
        if (feeSchedule != null && StringUtils.hasText(category)) {
            Map<String, BigDecimal> categoryRates = feeSchedule.getCategoryFeeRates();
            if (categoryRates != null) {
                BigDecimal rate = categoryRates.get(category.toLowerCase());
                if (rate != null) {
                    return rate.max(BigDecimal.ZERO);
                }
            }
        }

        // 3. 全局默认
        if (feeSchedule != null && feeSchedule.getDefaultTakerFeeRate() != null) {
            return feeSchedule.getDefaultTakerFeeRate().max(BigDecimal.ZERO);
        }

        // 最终兜底：回退到 executionWallet.estimatedFeeRate（向后兼容）
        BigDecimal legacy = properties.getExecutionWallet() == null
                ? null
                : properties.getExecutionWallet().getEstimatedFeeRate();
        return legacy == null ? new BigDecimal("0.02") : legacy.max(BigDecimal.ZERO);
    }

    /**
     * 简化调用：不传品类时直接走 conditionId 缓存 → 全局默认。
     */
    public BigDecimal getTakerFeeRate(String conditionId) {
        return getTakerFeeRate(conditionId, null);
    }

    /**
     * 计算给定仓位扣减 taker fee 后的有效投入金额。
     *
     * <p>公式：effectiveStake = rawStake / (1 + feeRate)</p>
     * <p>例如 rawStake=$10, feeRate=1.8% → effectiveStake = 10 / 1.018 ≈ $9.82</p>
     *
     * @param rawStake    原始计划仓位（USDC）
     * @param conditionId market conditionId
     * @param category    品类（可为 null）
     * @return 扣减手续费后的有效仓位
     */
    public BigDecimal adjustStakeForFee(BigDecimal rawStake, String conditionId, String category) {
        if (rawStake == null || rawStake.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal feeRate = getTakerFeeRate(conditionId, category);
        if (feeRate.compareTo(BigDecimal.ZERO) <= 0) {
            return rawStake;
        }
        return rawStake.divide(BigDecimal.ONE.add(feeRate), 2, RoundingMode.DOWN);
    }

    /**
     * 预估指定仓位的 taker 手续费金额。
     */
    public BigDecimal estimateFeeUsdc(BigDecimal stakeUsdc, String conditionId, String category) {
        if (stakeUsdc == null || stakeUsdc.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal feeRate = getTakerFeeRate(conditionId, category);
        return stakeUsdc.multiply(feeRate).setScale(4, RoundingMode.HALF_UP);
    }

    // ── 观测值回写 ──────────────────────────────────────────────────────

    /**
     * 订单成交后回写真实费率，供后续同 market 查询时使用。
     *
     * @param conditionId market conditionId
     * @param actualFeeUsdc 实际扣除的手续费（USDC）
     * @param notionalUsdc  成交名义金额（USDC）
     */
    public void recordObservedFeeRate(String conditionId, BigDecimal actualFeeUsdc, BigDecimal notionalUsdc) {
        if (!StringUtils.hasText(conditionId) || notionalUsdc == null
                || notionalUsdc.compareTo(BigDecimal.ZERO) <= 0
                || actualFeeUsdc == null) {
            return;
        }
        BigDecimal rate = actualFeeUsdc.divide(notionalUsdc, 6, RoundingMode.HALF_UP);
        observedFeeRates.put(conditionId.toLowerCase(), rate);
        log.debug("{} Recorded observed fee rate for conditionId={}: {} (fee={} / notional={})",
                LOG_PREFIX, conditionId, rate, actualFeeUsdc, notionalUsdc);
    }

    /**
     * 返回当前缓存大小（调试用）。
     */
    public int observedCacheSize() {
        return observedFeeRates.size();
    }
}
