package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SmartWalletScoringService {

    public BigDecimal score(SmartWalletProfile wallet) {
        return score(
                wallet.getWinRate(),
                wallet.getRealizedPnlUsdc(),
                wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades(),
                BigDecimal.ZERO,
                wallet.getMaxDrawdownRate()
        ).add(tagBonus(wallet.getTags())).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal score(BigDecimal winRate,
                            BigDecimal realizedPnlUsdc,
                            Integer totalTrades,
                            BigDecimal averageRoiRate,
                            BigDecimal maxDrawdownRate) {
        BigDecimal winComponent = sanitize(winRate).multiply(new BigDecimal("0.45"));
        BigDecimal pnlComponent = cappedRatio(realizedPnlUsdc, new BigDecimal("50000")).multiply(new BigDecimal("0.20"));
        BigDecimal sampleComponent = cappedRatio(new BigDecimal(totalTrades == null ? 0 : totalTrades), new BigDecimal("250")).multiply(new BigDecimal("0.15"));
        BigDecimal roiComponent = sanitize(averageRoiRate).multiply(new BigDecimal("0.20"));
        BigDecimal drawdownPenalty = sanitize(maxDrawdownRate).multiply(new BigDecimal("0.20"));

        BigDecimal total = winComponent
                .add(pnlComponent)
                .add(sampleComponent)
                .add(roiComponent)
                .subtract(drawdownPenalty);

        return normalizeScore(total);
    }

    private BigDecimal cappedRatio(BigDecimal value, BigDecimal cap) {
        if (value == null || cap == null || cap.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal bounded = value.max(BigDecimal.ZERO).min(cap);
        return bounded.divide(cap, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal sanitize(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private BigDecimal tagBonus(java.util.List<String> tags) {
        Set<String> normalizedTags = new HashSet<>(tags == null ? Collections.emptyList() : tags);
        return normalizedTags.isEmpty()
                ? BigDecimal.ZERO
                : new BigDecimal(Math.min(normalizedTags.size(), 4)).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeScore(BigDecimal total) {
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (total.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
        }
        return total.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 实体维度评分（Phase 2）
     * 合并同实体所有钱包的历史指标后重新计算综合得分
     * 解决单钱包评分因样本量不足而失真的问题
     *
     * @param entityId 实体ID
     * @param profiles 该实体下所有钱包的 profile 列表
     * @return 实体综合得分 0-1，无数据时返回 0
     */
    public BigDecimal scoreEntity(String entityId, List<SmartWalletProfileEntity> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (profiles.size() == 1) {
            SmartWalletProfileEntity single = profiles.get(0);
            return score(single.getWinRate(), single.getRealizedPnlUsdc(),
                    single.getTotalTrades(), single.getAverageRoiRate(), single.getMaxDrawdownRate());
        }

        // 按交易次数加权聚合
        int totalTrades = profiles.stream()
                .mapToInt(p -> p.getTotalTrades() == null ? 0 : p.getTotalTrades()).sum();

        BigDecimal weightedWinRate = BigDecimal.ZERO;
        BigDecimal weightedRoi = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (SmartWalletProfileEntity p : profiles) {
            int trades = p.getTotalTrades() == null ? 0 : p.getTotalTrades();
            BigDecimal weight = totalTrades > 0
                    ? BigDecimal.valueOf(trades).divide(BigDecimal.valueOf(totalTrades), 6, RoundingMode.HALF_UP)
                    : BigDecimal.ONE.divide(BigDecimal.valueOf(profiles.size()), 6, RoundingMode.HALF_UP);

            if (p.getWinRate() != null) {
                weightedWinRate = weightedWinRate.add(p.getWinRate().multiply(weight));
            }
            if (p.getAverageRoiRate() != null) {
                weightedRoi = weightedRoi.add(p.getAverageRoiRate().multiply(weight));
            }
            if (p.getRealizedPnlUsdc() != null) {
                totalPnl = totalPnl.add(p.getRealizedPnlUsdc());
            }
            if (p.getMaxDrawdownRate() != null) {
                maxDrawdown = maxDrawdown.max(p.getMaxDrawdownRate());
            }
        }

        // 使用与单钱包相同的权重公式
        BigDecimal winRateComp = cap(weightedWinRate).multiply(new BigDecimal("0.45"));
        BigDecimal pnlComp = cap(totalPnl, new BigDecimal("50000")).multiply(new BigDecimal("0.20"));
        BigDecimal sampleComp = cap(BigDecimal.valueOf(totalTrades), new BigDecimal("250")).multiply(new BigDecimal("0.15"));
        BigDecimal roiComp = cap(weightedRoi).multiply(new BigDecimal("0.20"));
        BigDecimal drawdownPenalty = cap(maxDrawdown).multiply(new BigDecimal("0.20"));

        BigDecimal raw = winRateComp.add(pnlComp).add(sampleComp).add(roiComp).subtract(drawdownPenalty);
        return raw.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal cap(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private BigDecimal cap(BigDecimal value, BigDecimal max) {
        if (value == null) return BigDecimal.ZERO;
        return value.max(BigDecimal.ZERO).min(max).divide(max, 6, RoundingMode.HALF_UP);
    }
}
