package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admits entries whose signed executable basis is favorable relative to recent observations for
 * the same symbol and exchange direction. Higher values are better: the short leg can be sold
 * relatively dearer than the long leg is bought.
 */
public final class DirectionalBasisAdmissionGate {
    public record Assessment(boolean admitted, BigDecimal basis, BigDecimal threshold,
            int sampleCount) { }

    private final boolean enabled;
    private final int minimumSamples;
    private final int windowSize;
    private final int percentile;
    private final Map<String, Deque<BigDecimal>> history = new ConcurrentHashMap<>();

    public DirectionalBasisAdmissionGate(boolean enabled, int minimumSamples, int windowSize,
            int percentile) {
        if (minimumSamples <= 0 || windowSize < minimumSamples) {
            throw new IllegalArgumentException("basis history window must cover minimum samples");
        }
        if (percentile < 0 || percentile > 100) {
            throw new IllegalArgumentException("basis percentile must be between 0 and 100");
        }
        this.enabled = enabled;
        this.minimumSamples = minimumSamples;
        this.windowSize = windowSize;
        this.percentile = percentile;
    }

    public static DirectionalBasisAdmissionGate disabled() {
        return new DirectionalBasisAdmissionGate(false, 1, 1, 0);
    }

    public Assessment assess(String symbol, Exchange longExchange, Exchange shortExchange,
            BigDecimal longEntryPrice, BigDecimal shortEntryPrice) {
        BigDecimal basis = shortEntryPrice.subtract(longEntryPrice)
                .divide(longEntryPrice, 12, RoundingMode.HALF_UP);
        if (!enabled) {
            return new Assessment(true, basis, null, 0);
        }
        String key = symbol + '|' + longExchange.name() + '|' + shortExchange.name();
        Deque<BigDecimal> samples = history.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (samples) {
            BigDecimal threshold = samples.size() < minimumSamples ? null : percentile(samples);
            boolean admitted = threshold == null || basis.compareTo(threshold) >= 0;
            samples.addLast(basis);
            while (samples.size() > windowSize) {
                samples.removeFirst();
            }
            return new Assessment(admitted, basis, threshold, samples.size());
        }
    }

    private BigDecimal percentile(Deque<BigDecimal> samples) {
        ArrayList<BigDecimal> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.naturalOrder());
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        BigDecimal index = BigDecimal.valueOf(percentile)
                .multiply(BigDecimal.valueOf(sorted.size() - 1))
                .divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        int lower = index.intValue();
        int upper = Math.min(lower + 1, sorted.size() - 1);
        BigDecimal fraction = index.subtract(BigDecimal.valueOf(lower));
        return sorted.get(lower).add(sorted.get(upper).subtract(sorted.get(lower)).multiply(fraction));
    }
}
