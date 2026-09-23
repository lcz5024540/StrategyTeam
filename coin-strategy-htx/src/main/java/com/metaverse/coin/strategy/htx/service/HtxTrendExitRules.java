package com.metaverse.coin.strategy.htx.service;

import java.math.BigDecimal;

/**
 * Pure price rules shared by the live trend exit path and its historical replay.
 * This class intentionally has no exchange, clock, persistence or configuration dependency.
 */
public final class HtxTrendExitRules {

    public static final String LONG = "LONG";
    public static final String SHORT = "SHORT";

    private HtxTrendExitRules() {
    }

    public static BigDecimal rTargetPrice(String side, BigDecimal entryPrice, BigDecimal stopPrice,
                                          BigDecimal multiple) {
        if (!positive(entryPrice) || !positive(stopPrice) || multiple == null || multiple.signum() <= 0) {
            return null;
        }
        BigDecimal riskDistance = LONG.equals(side) ? entryPrice.subtract(stopPrice) : stopPrice.subtract(entryPrice);
        if (!positive(riskDistance)) {
            return null;
        }
        BigDecimal targetDistance = riskDistance.multiply(multiple);
        return LONG.equals(side) ? entryPrice.add(targetDistance) : entryPrice.subtract(targetDistance);
    }

    public static boolean crossedStop(String side, BigDecimal reference, BigDecimal stopPrice) {
        if (!positive(reference) || !positive(stopPrice)) {
            return false;
        }
        return LONG.equals(side) ? reference.compareTo(stopPrice) <= 0 : reference.compareTo(stopPrice) >= 0;
    }

    public static boolean targetReached(String side, BigDecimal reference, BigDecimal targetPrice) {
        if (!positive(reference) || !positive(targetPrice)) {
            return false;
        }
        return LONG.equals(side) ? reference.compareTo(targetPrice) >= 0 : reference.compareTo(targetPrice) <= 0;
    }

    public static boolean trailingExitAllowed(String side, BigDecimal entryPrice, BigDecimal stopPrice,
                                              BigDecimal reference, BigDecimal activationR,
                                              boolean requireTimeframeExhaustion, int majorTimeframeOpposition,
                                              int requiredOpposition) {
        BigDecimal activationTarget = rTargetPrice(side, entryPrice, stopPrice, activationR);
        if (!positive(activationTarget) || !targetReached(side, reference, activationTarget)) {
            return false;
        }
        return !requireTimeframeExhaustion || majorTimeframeOpposition >= Math.max(1, requiredOpposition);
    }

    public static long partialCloseVolume(long remainingContracts, BigDecimal rate) {
        if (remainingContracts <= 1L || rate == null || rate.signum() <= 0) {
            return 0L;
        }
        long volume = BigDecimal.valueOf(remainingContracts).multiply(rate)
                .setScale(0, java.math.RoundingMode.DOWN).longValue();
        return Math.max(1L, Math.min(remainingContracts - 1L, volume));
    }

    public static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
