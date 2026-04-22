package com.metaverse.coin.strategy.job.smartwallet.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Kelly Criterion stake calculator for binary prediction markets.
 *
 * Formula: f* = (p * b - (1-p)) / b
 *   p = calibrated follow probability
 *   b = (1 - entryPrice) / entryPrice  (payout odds)
 *
 * Returns null when probability is unknown (caller falls back to legacy formula).
 * Returns ZERO when EV is negative (caller should reject signal via EV gate).
 * Returns minOrderUsdc when entry price is near 0 or 1 (abnormal odds).
 */
@Component
public class KellyStakeCalculator {

    private static final BigDecimal PRICE_BOUNDARY = new BigDecimal("0.02");
    private static final int SCALE = 8;

    /**
     * Compute recommended stake using Kelly Criterion.
     *
     * @param calibratedFollowProbability  follow probability (null → return null for fallback)
     * @param entryPrice                   market entry price [0,1]
     * @param availableCapital             available capital in USDC
     * @param kellyFraction                Kelly fraction / discount factor (e.g. 0.3)
     * @param minOrderUsdc                 minimum order size floor
     * @param maxStakeUsdc                 maximum stake ceiling
     * @return recommended stake, or null if probability unknown
     */
    public BigDecimal compute(BigDecimal calibratedFollowProbability,
                              BigDecimal entryPrice,
                              BigDecimal availableCapital,
                              BigDecimal kellyFraction,
                              BigDecimal minOrderUsdc,
                              BigDecimal maxStakeUsdc) {
        if (calibratedFollowProbability == null) {
            return null;
        }

        BigDecimal price = entryPrice == null ? BigDecimal.ZERO : entryPrice;
        BigDecimal capital = availableCapital == null ? BigDecimal.ZERO : availableCapital;
        BigDecimal fraction = kellyFraction == null ? new BigDecimal("0.3") : kellyFraction;
        BigDecimal minOrder = minOrderUsdc == null ? BigDecimal.ONE : minOrderUsdc;
        BigDecimal maxStake = maxStakeUsdc == null ? new BigDecimal("150") : maxStakeUsdc;

        // Abnormal price boundary → clamp to minOrder
        if (price.compareTo(PRICE_BOUNDARY) < 0 || price.compareTo(BigDecimal.ONE.subtract(PRICE_BOUNDARY)) > 0) {
            return minOrder.setScale(2, RoundingMode.HALF_UP);
        }

        // b = (1 - price) / price
        BigDecimal odds = BigDecimal.ONE.subtract(price).divide(price, SCALE, RoundingMode.HALF_UP);

        // f* = (p * b - (1-p)) / b
        BigDecimal p = calibratedFollowProbability;
        BigDecimal numerator = p.multiply(odds).subtract(BigDecimal.ONE.subtract(p));
        if (numerator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal kellyRatio = numerator.divide(odds, SCALE, RoundingMode.HALF_UP);

        // stake = capital * fraction * kellyRatio
        BigDecimal stake = capital.multiply(fraction).multiply(kellyRatio).setScale(2, RoundingMode.HALF_UP);

        // Apply floor and ceiling
        if (stake.compareTo(minOrder) < 0) {
            return minOrder.setScale(2, RoundingMode.HALF_UP);
        }
        return stake.min(maxStake).setScale(2, RoundingMode.HALF_UP);
    }
}
