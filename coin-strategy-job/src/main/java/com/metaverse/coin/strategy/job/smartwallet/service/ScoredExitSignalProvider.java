package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Method-C exit signal provider.
 *
 * Algorithm:
 *   baseThreshold  = resolveTimeTierThreshold(remainingHours)   [same tier logic as TimeAwareStopLossService]
 *   currentRoiPct  = (markPrice - avgEntry) / avgEntry          [negative when losing]
 *   roiPenalty     = max(0, abs(roiPct) * roiMomentumWeight)    [only for losing positions]
 *   finalThreshold = clamp(baseThreshold - roiPenalty, minThreshold, maxThreshold)
 *
 * Example: remaining=48h → base=0.25, ROI=-20%, weight=0.10 → penalty=0.02 → threshold=0.23
 *
 * Trend stop-loss logic is identical to TimeAwareStopLossService (in-memory price history).
 */
@Service
public class ScoredExitSignalProvider implements ExitSignalProvider {

    private static final int SCALE = 8;
    private final SmartWalletProperties properties;
    private final Map<Long, Deque<BigDecimal>> priceHistory = new ConcurrentHashMap<>();

    public ScoredExitSignalProvider(SmartWalletProperties properties) {
        this.properties = properties;
    }

    @Override
    public BigDecimal resolveStopLossThreshold(SmartWalletPositionEntity position) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        BigDecimal fallback = risk.getAutoStopLossThresholdRate() == null
                ? new BigDecimal("0.35") : risk.getAutoStopLossThresholdRate();

        BigDecimal base = resolveTimeTierThreshold(position, risk, fallback);

        // Compute ROI penalty — only applied when position is losing
        BigDecimal markPrice = position == null ? null : position.getLastMarkPrice();
        BigDecimal avgEntry = position == null ? null : position.getAverageEntryPrice();
        BigDecimal penalty = BigDecimal.ZERO;

        if (markPrice != null && avgEntry != null && avgEntry.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal roiPct = markPrice.subtract(avgEntry).divide(avgEntry, SCALE, RoundingMode.HALF_UP);
            if (roiPct.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal weight = risk.getExitScoringRoiMomentumWeight() == null
                        ? new BigDecimal("0.10") : risk.getExitScoringRoiMomentumWeight();
                penalty = roiPct.abs().multiply(weight).setScale(4, RoundingMode.HALF_UP);
            }
        }

        BigDecimal minT = risk.getExitScoringMinThreshold() == null
                ? new BigDecimal("0.03") : risk.getExitScoringMinThreshold();
        BigDecimal maxT = risk.getExitScoringMaxThreshold() == null
                ? new BigDecimal("0.50") : risk.getExitScoringMaxThreshold();

        BigDecimal result = base.subtract(penalty).setScale(2, RoundingMode.HALF_UP);
        if (result.compareTo(minT) < 0) return minT.setScale(2, RoundingMode.HALF_UP);
        if (result.compareTo(maxT) > 0) return maxT.setScale(2, RoundingMode.HALF_UP);
        return result;
    }

    /**
     * Resolve time-tier baseline threshold — same matching logic as TimeAwareStopLossService.
     * Find the tier with the largest remainingHours that is still <= actual remaining hours.
     */
    private BigDecimal resolveTimeTierThreshold(SmartWalletPositionEntity position,
                                                SmartWalletProperties.SignalRisk risk,
                                                BigDecimal fallback) {
        if (!risk.isTimeAwareStopLossEnabled()) {
            return fallback;
        }
        Long marketEndAt = position == null ? null : position.getMarketEndAt();
        if (marketEndAt == null || marketEndAt <= 0) {
            return fallback;
        }
        List<SmartWalletProperties.StopLossTier> tiers = risk.getStopLossTiers();
        if (tiers == null || tiers.isEmpty()) {
            return fallback;
        }
        long remainingMs = marketEndAt - System.currentTimeMillis();
        if (remainingMs <= 0) {
            return tiers.stream()
                    .map(SmartWalletProperties.StopLossTier::getThresholdRate)
                    .filter(r -> r != null)
                    .min(BigDecimal::compareTo)
                    .orElse(fallback);
        }
        long remainingHours = remainingMs / 3600_000L;
        return tiers.stream()
                .filter(tier -> tier.getRemainingHours() <= remainingHours)
                .max((a, b) -> Integer.compare(a.getRemainingHours(), b.getRemainingHours()))
                .map(SmartWalletProperties.StopLossTier::getThresholdRate)
                .orElse(fallback);
    }

    @Override
    public void recordMarkPrice(Long positionId, BigDecimal markPrice, BigDecimal averageEntryPrice) {
        if (positionId == null || markPrice == null) return;
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!risk.isTrendStopLossEnabled()) return;
        int windowSize = risk.getTrendStopLossConsecutivePeriods() <= 0
                ? 3 : risk.getTrendStopLossConsecutivePeriods();
        Deque<BigDecimal> history = priceHistory.computeIfAbsent(positionId, id -> new ArrayDeque<>());
        history.addLast(markPrice);
        while (history.size() > windowSize + 1) {
            history.pollFirst();
        }
    }

    @Override
    public boolean isTrendStopLossTriggered(Long positionId, BigDecimal currentMarkPrice, BigDecimal averageEntryPrice) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!risk.isTrendStopLossEnabled() || positionId == null) return false;
        if (averageEntryPrice == null || currentMarkPrice == null
                || currentMarkPrice.compareTo(averageEntryPrice) >= 0) return false;
        Deque<BigDecimal> history = priceHistory.get(positionId);
        if (history == null) return false;
        int periods = risk.getTrendStopLossConsecutivePeriods() <= 0
                ? 3 : risk.getTrendStopLossConsecutivePeriods();
        if (history.size() < periods) return false;
        BigDecimal[] prices = history.toArray(new BigDecimal[0]);
        int start = prices.length - periods;
        for (int i = start; i < prices.length - 1; i++) {
            if (prices[i].compareTo(prices[i + 1]) <= 0) return false;
        }
        return true;
    }

    @Override
    public void clearPositionHistory(Long positionId) {
        if (positionId != null) priceHistory.remove(positionId);
    }
}
