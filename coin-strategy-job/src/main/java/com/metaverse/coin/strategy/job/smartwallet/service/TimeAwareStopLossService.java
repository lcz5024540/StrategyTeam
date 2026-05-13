package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rule-based exit signal provider that tightens stop-loss thresholds as market
 * end time approaches, and can trigger early exit on consecutive price decline.
 *
 * Price history is held in memory (lost on restart), which is acceptable since
 * the trend signal is advisory and the threshold-based stop-loss is the primary gate.
 */
@Service
public class TimeAwareStopLossService implements ExitSignalProvider {

    private final SmartWalletProperties properties;

    /** positionId → recent mark prices (newest last), bounded by trendStopLossConsecutivePeriods + 1 */
    private final Map<Long, Deque<BigDecimal>> priceHistory = new ConcurrentHashMap<>();

    public TimeAwareStopLossService(SmartWalletProperties properties) {
        this.properties = properties;
    }

    @Override
    public BigDecimal resolveStopLossThreshold(SmartWalletPositionEntity position) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        BigDecimal fallback = risk.getAutoStopLossThresholdRate() == null
                ? new BigDecimal("0.35") : risk.getAutoStopLossThresholdRate();

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
            // Market already expired — use tightest tier (smallest threshold rate)
            return tiers.stream()
                    .map(SmartWalletProperties.StopLossTier::getThresholdRate)
                    .filter(r -> r != null)
                    .min(BigDecimal::compareTo)
                    .orElse(fallback);
        }

        long remainingHours = remainingMs / 3600_000L;

        // Each tier's remainingHours is the lower bound for that threshold band.
        // Find the tier with the largest remainingHours that is still <= actual remaining hours.
        // E.g. tiers [168→0.35, 24→0.25, 6→0.15, 0→0.05]:
        //   200h → max matching tier is 168h → 0.35
        //   48h  → max matching tier is 24h  → 0.25
        //   10h  → max matching tier is 6h   → 0.15
        //   2h   → max matching tier is 0h   → 0.05
        return tiers.stream()
                .filter(tier -> tier.getRemainingHours() <= remainingHours)
                .max((a, b) -> Integer.compare(a.getRemainingHours(), b.getRemainingHours()))
                .map(SmartWalletProperties.StopLossTier::getThresholdRate)
                .orElse(fallback);
    }

    @Override
    public void recordMarkPrice(Long positionId, BigDecimal markPrice, BigDecimal averageEntryPrice) {
        if (positionId == null || markPrice == null) {
            return;
        }
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!risk.isTrendStopLossEnabled()) {
            return;
        }
        int windowSize = risk.getTrendStopLossConsecutivePeriods() <= 0
                ? 3 : risk.getTrendStopLossConsecutivePeriods();
        int minSamples = risk.getMinPriceHistorySamples() == null || risk.getMinPriceHistorySamples() <= 0
                ? windowSize : risk.getMinPriceHistorySamples();
        int cap = Math.max(windowSize, minSamples) + 1;

        Deque<BigDecimal> history = priceHistory.computeIfAbsent(positionId, id -> new ArrayDeque<>());
        history.addLast(markPrice);
        // Keep enough entries to satisfy both windowSize and minPriceHistorySamples
        while (history.size() > cap) {
            history.pollFirst();
        }
    }

    @Override
    public boolean isTrendStopLossTriggered(Long positionId, BigDecimal currentMarkPrice, BigDecimal averageEntryPrice) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (!risk.isTrendStopLossEnabled() || positionId == null) {
            return false;
        }
        // Only trigger when position is already losing (ROI < 0)
        if (averageEntryPrice == null || currentMarkPrice == null
                || currentMarkPrice.compareTo(averageEntryPrice) >= 0) {
            return false;
        }

        Deque<BigDecimal> history = priceHistory.get(positionId);
        if (history == null) {
            return false;
        }

        int periods = risk.getTrendStopLossConsecutivePeriods() <= 0
                ? 3 : risk.getTrendStopLossConsecutivePeriods();
        int minSamples = risk.getMinPriceHistorySamples() == null || risk.getMinPriceHistorySamples() <= 0
                ? periods : risk.getMinPriceHistorySamples();
        if (history.size() < Math.max(periods, minSamples)) {
            return false;
        }

        // Check last `periods` entries are strictly decreasing
        BigDecimal[] prices = history.toArray(new BigDecimal[0]);
        int start = prices.length - periods;
        for (int i = start; i < prices.length - 1; i++) {
            if (prices[i].compareTo(prices[i + 1]) <= 0) {
                return false; // not strictly decreasing
            }
        }
        return true;
    }

    /** Remove cached price history when a position is closed. Call from exit handling code. */
    public void clearPositionHistory(Long positionId) {
        if (positionId != null) {
            priceHistory.remove(positionId);
        }
    }
}
