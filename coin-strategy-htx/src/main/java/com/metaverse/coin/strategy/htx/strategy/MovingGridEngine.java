package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxGridWindowEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/** 纯计算的双向移动窗口：连续越界、迟滞和冷却全部在这里收敛。 */
public class MovingGridEngine {

    public static final String RUNNING = "RUNNING";
    public static final String MIGRATING_UP = "MIGRATING_UP";
    public static final String MIGRATING_DOWN = "MIGRATING_DOWN";
    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    public HtxGridWindowEntity initial(String symbol, BigDecimal anchor, BigDecimal stepPct,
                                       int levels, LocalDateTime now) {
        HtxGridWindowEntity window = new HtxGridWindowEntity();
        window.setSymbol(symbol);
        window.setAnchorPrice(scale(anchor));
        window.setStepPct(stepPct);
        window.setLevels(levels);
        window.setEpoch(1L);
        window.setState(RUNNING);
        window.setBreachCount(0);
        window.setCreatedAt(now);
        window.setUpdatedAt(now);
        recalculateBounds(window);
        return window;
    }

    public Decision observe(HtxGridWindowEntity current, BigDecimal marketPrice,
                            BigDecimal hysteresisPct, int confirmations, long cooldownMs,
                            int maxShiftSteps, LocalDateTime now) {
        String direction = breachDirection(current, marketPrice, hysteresisPct);
        if (direction == null) {
            current.setBreachDirection(null);
            current.setBreachCount(0);
            current.setState(RUNNING);
            current.setUpdatedAt(now);
            return new Decision(current, false, 0);
        }
        int count = direction.equals(current.getBreachDirection())
                ? value(current.getBreachCount()) + 1 : 1;
        current.setBreachDirection(direction);
        current.setBreachCount(count);
        current.setUpdatedAt(now);
        if (count < Math.max(1, confirmations) || coolingDown(current, cooldownMs, now)) {
            current.setState(RUNNING);
            return new Decision(current, false, 0);
        }

        int signedSteps = shiftSteps(current, marketPrice, hysteresisPct,
                Math.max(1, maxShiftSteps), direction);
        BigDecimal factor = BigDecimal.ONE.subtract(current.getStepPct());
        BigDecimal anchor = current.getAnchorPrice();
        int steps = Math.abs(signedSteps);
        for (int i = 0; i < steps; i++) {
            anchor = signedSteps > 0
                    ? anchor.divide(factor, MarketMakerMath.PRICE_SCALE + 4, RoundingMode.HALF_UP)
                    : anchor.multiply(factor);
        }
        current.setAnchorPrice(scale(anchor));
        current.setEpoch(value(current.getEpoch()) + 1L);
        current.setState(signedSteps > 0 ? MIGRATING_UP : MIGRATING_DOWN);
        current.setBreachDirection(null);
        current.setBreachCount(0);
        current.setLastMigratedAt(now);
        recalculateBounds(current);
        return new Decision(current, true, signedSteps);
    }

    private String breachDirection(HtxGridWindowEntity window, BigDecimal marketPrice,
                                   BigDecimal hysteresisPct) {
        BigDecimal hysteresis = hysteresisPct == null ? BigDecimal.ZERO : hysteresisPct.max(BigDecimal.ZERO);
        BigDecimal upperTrigger = window.getUpperPrice().multiply(BigDecimal.ONE.add(hysteresis));
        BigDecimal lowerTrigger = window.getLowerPrice().multiply(BigDecimal.ONE.subtract(hysteresis));
        if (marketPrice.compareTo(upperTrigger) > 0) return UP;
        if (marketPrice.compareTo(lowerTrigger) < 0) return DOWN;
        return null;
    }

    private int shiftSteps(HtxGridWindowEntity window, BigDecimal marketPrice, BigDecimal hysteresisPct,
                           int maxShiftSteps, String direction) {
        BigDecimal factor = BigDecimal.ONE.subtract(window.getStepPct());
        BigDecimal hysteresis = hysteresisPct == null ? BigDecimal.ZERO : hysteresisPct.max(BigDecimal.ZERO);
        BigDecimal anchor = window.getAnchorPrice();
        int steps = 0;
        if (UP.equals(direction)) {
            while (steps < maxShiftSteps
                    && marketPrice.compareTo(anchor.multiply(BigDecimal.ONE.add(hysteresis))) > 0) {
                anchor = anchor.divide(factor, MarketMakerMath.PRICE_SCALE + 4, RoundingMode.HALF_UP);
                steps++;
            }
            return steps;
        }
        BigDecimal lower = lower(anchor, factor, window.getLevels());
        while (steps < maxShiftSteps
                && marketPrice.compareTo(lower.multiply(BigDecimal.ONE.subtract(hysteresis))) < 0) {
            anchor = anchor.multiply(factor);
            lower = lower(anchor, factor, window.getLevels());
            steps++;
        }
        return -steps;
    }

    private void recalculateBounds(HtxGridWindowEntity window) {
        BigDecimal factor = BigDecimal.ONE.subtract(window.getStepPct());
        window.setUpperPrice(scale(window.getAnchorPrice()));
        window.setLowerPrice(scale(lower(window.getAnchorPrice(), factor, window.getLevels())));
    }

    private BigDecimal lower(BigDecimal anchor, BigDecimal factor, int levels) {
        return anchor.multiply(factor.pow(Math.max(0, levels - 1)));
    }

    private boolean coolingDown(HtxGridWindowEntity window, long cooldownMs, LocalDateTime now) {
        return cooldownMs > 0 && window.getLastMigratedAt() != null
                && Duration.between(window.getLastMigratedAt(), now).toMillis() < cooldownMs;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(MarketMakerMath.PRICE_SCALE, RoundingMode.HALF_UP);
    }

    public record Decision(HtxGridWindowEntity window, boolean migrated, int signedSteps) {}
}
