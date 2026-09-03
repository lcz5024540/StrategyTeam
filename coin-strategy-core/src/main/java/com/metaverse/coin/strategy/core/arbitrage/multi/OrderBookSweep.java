package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Deterministic L2 sweep used by Paper close valuation and execution. */
public final class OrderBookSweep {
    private static final BigDecimal BPS = new BigDecimal("10000");

    private OrderBookSweep() {
    }

    public static Result forQuantity(List<NormalizedOrderBookSnapshot.Level> levels,
            BigDecimal requestedQuantity, BigDecimal bestPrice) {
        if (levels == null || levels.isEmpty() || requestedQuantity == null
                || requestedQuantity.signum() <= 0 || bestPrice == null || bestPrice.signum() <= 0) {
            throw new IllegalArgumentException("levels, positive quantity and best price are required");
        }
        BigDecimal remaining = requestedQuantity;
        BigDecimal filledQuantity = BigDecimal.ZERO;
        BigDecimal filledNotional = BigDecimal.ZERO;
        List<Slice> slices = new ArrayList<>();
        for (int index = 0; index < levels.size() && remaining.signum() > 0; index++) {
            NormalizedOrderBookSnapshot.Level level = levels.get(index);
            BigDecimal availableQuantity = level.notional().divide(level.price(), 18, RoundingMode.DOWN);
            BigDecimal sliceQuantity = availableQuantity.min(remaining);
            BigDecimal sliceNotional = sliceQuantity.multiply(level.price());
            slices.add(new Slice(index + 1, level.price(), sliceQuantity, sliceNotional));
            filledQuantity = filledQuantity.add(sliceQuantity);
            filledNotional = filledNotional.add(sliceNotional);
            remaining = remaining.subtract(sliceQuantity);
        }
        if (remaining.signum() > 0) {
            throw new IllegalStateException("order book depth is insufficient for requested quantity");
        }
        BigDecimal vwap = filledNotional.divide(filledQuantity, 18, RoundingMode.HALF_UP);
        BigDecimal impactBps = vwap.subtract(bestPrice).abs()
                .divide(bestPrice, 18, RoundingMode.HALF_UP).multiply(BPS);
        return new Result(requestedQuantity, filledQuantity, filledNotional, bestPrice, vwap,
                slices.get(slices.size() - 1).price(), impactBps, List.copyOf(slices));
    }

    public record Slice(int level, BigDecimal price, BigDecimal quantity, BigDecimal notional) {
    }

    public record Result(BigDecimal requestedQuantity, BigDecimal filledQuantity,
            BigDecimal filledNotional, BigDecimal bestPrice, BigDecimal vwap,
            BigDecimal worstPrice, BigDecimal impactBps, List<Slice> slices) {
    }
}
