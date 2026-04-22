package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class KellyStakeCalculatorTest {

    private final KellyStakeCalculator calculator = new KellyStakeCalculator();

    // f* = (p*b - (1-p)) / b
    // p=0.65, entryPrice=0.4 → b=(1-0.4)/0.4=1.5
    // f* = (0.65*1.5 - 0.35) / 1.5 = (0.975-0.35)/1.5 = 0.625/1.5 = 0.4167
    // stake = 1000 * 0.3 * 0.4167 = 125.01 → capped by maxStake=100 → 100.00
    @Test
    void compute_normalCase_returnsKellyStakeCappedByMax() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.65"),   // calibratedFollowProbability
                new BigDecimal("0.40"),   // entryPrice
                new BigDecimal("1000"),   // availableCapital
                new BigDecimal("0.30"),   // kellyFraction
                new BigDecimal("1"),      // minOrderUsdc
                new BigDecimal("100")     // maxStakeUsdc
        );
        assertEquals(new BigDecimal("100.00"), result);
    }

    // p=0.55, entryPrice=0.9 → b=(0.1)/0.9=0.1111
    // f* = (0.55*0.1111 - 0.45) / 0.1111 = (0.0611 - 0.45)/0.1111 = negative → 0
    @Test
    void compute_negativeEv_returnsZero() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.55"),
                new BigDecimal("0.90"),
                new BigDecimal("1000"),
                new BigDecimal("0.30"),
                new BigDecimal("1"),
                new BigDecimal("100")
        );
        assertEquals(BigDecimal.ZERO.setScale(2), result);
    }

    // p=null → fallback returns null (caller should use old formula)
    @Test
    void compute_nullProbability_returnsNull() {
        BigDecimal result = calculator.compute(
                null,
                new BigDecimal("0.40"),
                new BigDecimal("1000"),
                new BigDecimal("0.30"),
                new BigDecimal("1"),
                new BigDecimal("100")
        );
        assertNull(result);
    }

    // entryPrice=0.99 (near 1) → odds abnormal → returns minOrderUsdc
    @Test
    void compute_priceNearOne_returnsMinOrder() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.65"),
                new BigDecimal("0.99"),
                new BigDecimal("1000"),
                new BigDecimal("0.30"),
                new BigDecimal("5"),
                new BigDecimal("100")
        );
        assertEquals(new BigDecimal("5.00"), result);
    }

    // stake below minOrder → returns minOrder
    @Test
    void compute_stakeBeforeCapBelowMin_returnsMinOrder() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.60"),
                new BigDecimal("0.50"),
                new BigDecimal("10"),   // tiny capital → tiny stake
                new BigDecimal("0.30"),
                new BigDecimal("5"),
                new BigDecimal("100")
        );
        // f*=(0.60*1-0.40)/1=0.20; stake=10*0.3*0.20=0.60 < minOrder=5 → 5.00
        assertEquals(new BigDecimal("5.00"), result);
    }
}
