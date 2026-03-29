package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashSet;
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
}
