package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfile;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SmartWalletScoringService {

    private static final BigDecimal MAX_30D_WEIGHT = new BigDecimal("0.25");
    private static final BigDecimal MAX_7D_WEIGHT = new BigDecimal("0.20");
    private static final BigDecimal RECENT_30D_SAMPLE_CAP = new BigDecimal("20");
    private static final BigDecimal RECENT_7D_SAMPLE_CAP = new BigDecimal("8");

    public BigDecimal score(SmartWalletProfile wallet) {
        return score(
                wallet.getWinRate(),
                wallet.getRecentWinRate7d(),
                wallet.getRecentWinRate30d(),
                wallet.getRealizedPnlUsdc(),
                wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades(),
                wallet.getRecentTradeCount7d(),
                wallet.getRecentTradeCount30d(),
                wallet.getAverageRoiRate(),
                wallet.getMaxDrawdownRate(),
                wallet.getBehaviorComposite()
        ).add(tagBonus(wallet.getTags())).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    public BigDecimal score(BigDecimal winRate,
                            BigDecimal recentWinRate7d,
                            BigDecimal recentWinRate30d,
                            BigDecimal realizedPnlUsdc,
                            Integer totalTrades,
                            Integer recentTradeCount7d,
                            Integer recentTradeCount30d,
                            BigDecimal averageRoiRate,
                            BigDecimal maxDrawdownRate) {
        return score(winRate, recentWinRate7d, recentWinRate30d, realizedPnlUsdc, totalTrades,
                recentTradeCount7d, recentTradeCount30d, averageRoiRate, maxDrawdownRate, null);
    }

    public BigDecimal score(BigDecimal winRate,
                            BigDecimal recentWinRate7d,
                            BigDecimal recentWinRate30d,
                            BigDecimal realizedPnlUsdc,
                            Integer totalTrades,
                            Integer recentTradeCount7d,
                            Integer recentTradeCount30d,
                            BigDecimal averageRoiRate,
                            BigDecimal maxDrawdownRate,
                            BigDecimal behaviorComposite) {
        BigDecimal effectiveWinRate = effectiveWinRate(
                winRate,
                recentWinRate30d,
                recentWinRate7d,
                recentTradeCount30d,
                recentTradeCount7d
        );
        BigDecimal winComponent = effectiveWinRate.multiply(new BigDecimal("0.50"));
        BigDecimal pnlComponent = cappedRatio(realizedPnlUsdc, new BigDecimal("50000")).multiply(new BigDecimal("0.15"));
        BigDecimal sampleComponent = cappedRatio(new BigDecimal(totalTrades == null ? 0 : totalTrades), new BigDecimal("250"))
                .multiply(new BigDecimal("0.10"));
        BigDecimal roiComponent = sanitize(averageRoiRate).multiply(new BigDecimal("0.10"));
        BigDecimal recentActivityComponent = recentActivityScore(recentTradeCount7d, recentTradeCount30d)
                .multiply(new BigDecimal("0.05"));
        BigDecimal drawdownPenalty = sanitize(maxDrawdownRate).multiply(new BigDecimal("0.10"));

        BigDecimal total = winComponent
                .add(pnlComponent)
                .add(sampleComponent)
                .add(roiComponent)
                .add(recentActivityComponent)
                .subtract(drawdownPenalty);

        return blendBehaviorComposite(normalizeScore(total), behaviorComposite);
    }

    public BigDecimal score(BigDecimal winRate,
                            BigDecimal recentWinRate30d,
                            BigDecimal realizedPnlUsdc,
                            Integer totalTrades,
                            BigDecimal averageRoiRate,
                            BigDecimal maxDrawdownRate) {
        return score(winRate, null, recentWinRate30d, realizedPnlUsdc, totalTrades, null, null, averageRoiRate, maxDrawdownRate);
    }

    public BigDecimal score(BigDecimal winRate,
                            BigDecimal recentWinRate7d,
                            BigDecimal recentWinRate30d,
                            BigDecimal realizedPnlUsdc,
                            Integer totalTrades,
                            BigDecimal averageRoiRate,
                            BigDecimal maxDrawdownRate) {
        return score(winRate, recentWinRate7d, recentWinRate30d, realizedPnlUsdc, totalTrades, null, null, averageRoiRate, maxDrawdownRate);
    }

    public BigDecimal score(BigDecimal winRate,
                            BigDecimal realizedPnlUsdc,
                            Integer totalTrades,
                            BigDecimal averageRoiRate,
                            BigDecimal maxDrawdownRate) {
        return score(winRate, null, null, realizedPnlUsdc, totalTrades, null, null, averageRoiRate, maxDrawdownRate);
    }

    public BigDecimal behaviorComposite(WalletBehaviorProfileView behaviorProfile) {
        if (behaviorProfile == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (behaviorProfile.behaviorComposite() != null) {
            return normalizeScore(behaviorProfile.behaviorComposite());
        }
        return behaviorComposite(behaviorProfile.replicabilityScore(), behaviorProfile.qualityScore(), behaviorProfile.stabilityScore());
    }

    public BigDecimal behaviorComposite(BigDecimal replicabilityScore,
                                        BigDecimal qualityScore,
                                        BigDecimal stabilityScore) {
        BigDecimal composite = sanitize(replicabilityScore).multiply(new BigDecimal("0.45"))
                .add(sanitize(qualityScore).multiply(new BigDecimal("0.35")))
                .add(sanitize(stabilityScore).multiply(new BigDecimal("0.20")));
        return normalizeScore(composite);
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

    private BigDecimal blendBehaviorComposite(BigDecimal baseScore, BigDecimal behaviorComposite) {
        if (behaviorComposite == null) {
            return normalizeScore(baseScore);
        }
        BigDecimal blended = normalizeScore(baseScore).multiply(new BigDecimal("0.85"))
                .add(sanitize(behaviorComposite).multiply(new BigDecimal("0.15")));
        return normalizeScore(blended);
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
            return score(single.getWinRate(), single.getRecentWinRate7d(), single.getRecentWinRate30d(), single.getRealizedPnlUsdc(),
                    single.getTotalTrades(), single.getRecentTradeCount7d(), single.getRecentTradeCount30d(),
                    single.getAverageRoiRate(), single.getMaxDrawdownRate());
        }

        int totalTrades = profiles.stream()
                .mapToInt(p -> p.getTotalTrades() == null ? 0 : p.getTotalTrades()).sum();
        int totalRecentTrades7d = profiles.stream()
                .mapToInt(p -> p.getRecentTradeCount7d() == null ? 0 : p.getRecentTradeCount7d()).sum();
        int totalRecentTrades30d = profiles.stream()
                .mapToInt(p -> p.getRecentTradeCount30d() == null ? 0 : p.getRecentTradeCount30d()).sum();

        BigDecimal weightedWinRate = BigDecimal.ZERO;
        BigDecimal weightedRecentWinRate7d = BigDecimal.ZERO;
        BigDecimal weightedRecentWinRate30d = BigDecimal.ZERO;
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
            if (p.getRecentWinRate7d() != null) {
                BigDecimal recent7dWeight = totalRecentTrades7d > 0
                        ? BigDecimal.valueOf(p.getRecentTradeCount7d() == null ? 0 : p.getRecentTradeCount7d())
                        .divide(BigDecimal.valueOf(totalRecentTrades7d), 6, RoundingMode.HALF_UP)
                        : weight;
                weightedRecentWinRate7d = weightedRecentWinRate7d.add(p.getRecentWinRate7d().multiply(recent7dWeight));
            }
            if (p.getRecentWinRate30d() != null) {
                BigDecimal recent30dWeight = totalRecentTrades30d > 0
                        ? BigDecimal.valueOf(p.getRecentTradeCount30d() == null ? 0 : p.getRecentTradeCount30d())
                        .divide(BigDecimal.valueOf(totalRecentTrades30d), 6, RoundingMode.HALF_UP)
                        : weight;
                weightedRecentWinRate30d = weightedRecentWinRate30d.add(p.getRecentWinRate30d().multiply(recent30dWeight));
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

        return score(
                weightedWinRate,
                totalRecentTrades7d > 0 ? weightedRecentWinRate7d : null,
                totalRecentTrades30d > 0 ? weightedRecentWinRate30d : null,
                totalPnl,
                totalTrades,
                totalRecentTrades7d,
                totalRecentTrades30d,
                weightedRoi,
                maxDrawdown
        );
    }

    private BigDecimal effectiveWinRate(BigDecimal winRate,
                                        BigDecimal recentWinRate30d,
                                        BigDecimal recentWinRate7d,
                                        Integer recentTradeCount30d,
                                        Integer recentTradeCount7d) {
        BigDecimal base = sanitize(winRate);
        BigDecimal weight30d = recentWinRate30d == null
                ? BigDecimal.ZERO
                : sampleConfidence(recentTradeCount30d, RECENT_30D_SAMPLE_CAP).multiply(MAX_30D_WEIGHT);
        BigDecimal weight7d = recentWinRate7d == null
                ? BigDecimal.ZERO
                : sampleConfidence(recentTradeCount7d, RECENT_7D_SAMPLE_CAP).multiply(MAX_7D_WEIGHT);
        BigDecimal baseWeight = BigDecimal.ONE.subtract(weight30d).subtract(weight7d).max(BigDecimal.ZERO);
        return base.multiply(baseWeight)
                .add(sanitize(recentWinRate30d).multiply(weight30d))
                .add(sanitize(recentWinRate7d).multiply(weight7d))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal recentActivityScore(Integer recentTradeCount7d, Integer recentTradeCount30d) {
        BigDecimal score7d = cappedRatio(BigDecimal.valueOf(recentTradeCount7d == null ? 0 : recentTradeCount7d), new BigDecimal("10"));
        BigDecimal score30d = cappedRatio(BigDecimal.valueOf(recentTradeCount30d == null ? 0 : recentTradeCount30d), new BigDecimal("30"));
        return score7d.multiply(new BigDecimal("0.60"))
                .add(score30d.multiply(new BigDecimal("0.40")))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal sampleConfidence(Integer count, BigDecimal cap) {
        if (count == null || cap == null || cap.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return cappedRatio(BigDecimal.valueOf(count), cap);
    }
}
