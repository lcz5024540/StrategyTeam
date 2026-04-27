package com.metaverse.coin.strategy.job.smartwallet.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Kelly Criterion stake calculator for binary prediction markets.
 *
 * Formula: f* = (p * b - (1-p)) / b
 *   p = event win probability (converted from follow-worthiness score if edgeFactor provided)
 *   b = (1 - entryPrice) / entryPrice  (payout odds)
 *
 * Returns null when probability is unknown (caller falls back to legacy formula).
 * Returns ZERO when EV is negative (caller should reject signal via EV gate).
 * Returns ZERO when entry price is near 0 or 1 (market nearly resolved, extreme slippage).
 *
 * When kellySignalEdgeFactor is non-null, calibratedFollowProbability is treated as a
 * composite follow-worthiness score and converted to a win-rate estimate:
 *   p_win = 0.5 + (followProb - 0.5) * edgeFactor
 */
@Component
public class KellyStakeCalculator {

    private static final BigDecimal PRICE_BOUNDARY = new BigDecimal("0.02");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final int SCALE = 8;

    /**
     * Compute recommended stake using Kelly Criterion.
     *
     * @param calibratedFollowProbability  follow-worthiness score (null → return null for fallback)
     * @param entryPrice                   market entry price [0,1]
     * @param availableCapital             available capital in USDC
     * @param kellyFraction                Kelly fraction / discount factor (e.g. 0.3)
     * @param minOrderUsdc                 minimum order size floor
     * @param maxStakeUsdc                 maximum stake ceiling
     * @param kellySignalEdgeFactor        edge factor for follow→win-rate conversion (null = use followProb directly)
     * @return recommended stake, or null if probability unknown
     */
    public BigDecimal compute(BigDecimal calibratedFollowProbability,
                              BigDecimal entryPrice,
                              BigDecimal availableCapital,
                              BigDecimal kellyFraction,
                              BigDecimal minOrderUsdc,
                              BigDecimal maxStakeUsdc,
                              BigDecimal kellySignalEdgeFactor) {
        if (calibratedFollowProbability == null) {
            return null;
        }

        BigDecimal price = entryPrice == null ? BigDecimal.ZERO : entryPrice;
        BigDecimal capital = availableCapital == null ? BigDecimal.ZERO : availableCapital;
        BigDecimal fraction = kellyFraction == null ? new BigDecimal("0.3") : kellyFraction;
        BigDecimal minOrder = minOrderUsdc == null ? BigDecimal.ONE : minOrderUsdc;
        BigDecimal maxStake = maxStakeUsdc == null ? new BigDecimal("150") : maxStakeUsdc;

        // Abnormal price boundary → market nearly resolved, extreme slippage, reject signal
        // Returning ZERO causes the caller to treat this as negative-EV and block the trade.
        if (price.compareTo(PRICE_BOUNDARY) < 0 || price.compareTo(BigDecimal.ONE.subtract(PRICE_BOUNDARY)) > 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        // Convert follow-worthiness score to event win probability.
        // calibratedFollowProbability is a composite signal quality score, not a market win rate.
        // With edgeFactor: p_win = 0.5 + (followProb - 0.5) * edgeFactor
        // Without edgeFactor (null): use followProb directly (backward-compatible fallback).
        BigDecimal p;
        if (kellySignalEdgeFactor != null) {
            p = HALF.add(calibratedFollowProbability.subtract(HALF).multiply(kellySignalEdgeFactor))
                    .setScale(SCALE, RoundingMode.HALF_UP);
        } else {
            p = calibratedFollowProbability;
        }

        // b = (1 - price) / price
        BigDecimal odds = BigDecimal.ONE.subtract(price).divide(price, SCALE, RoundingMode.HALF_UP);

        // f* = (p * b - (1-p)) / b
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
