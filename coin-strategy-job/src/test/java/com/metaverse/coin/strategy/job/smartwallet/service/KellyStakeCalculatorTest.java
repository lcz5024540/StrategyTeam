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
    // edgeFactor=null → pass-through: followProb is used directly as p
    @Test
    void compute_normalCase_returnsKellyStakeCappedByMax() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.65"),   // calibratedFollowProbability
                new BigDecimal("0.40"),   // entryPrice
                new BigDecimal("1000"),   // availableCapital
                new BigDecimal("0.30"),   // kellyFraction
                new BigDecimal("1"),      // minOrderUsdc
                new BigDecimal("100"),    // maxStakeUsdc
                null                      // kellySignalEdgeFactor=null → no conversion
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
                new BigDecimal("100"),
                null
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
                new BigDecimal("100"),
                new BigDecimal("0.15")
        );
        assertNull(result);
    }

    // entryPrice=0.99 (near 1) → market nearly resolved → signal rejected (ZERO)
    // minOrder floor must NOT be applied: a nearly-resolved market has extreme slippage
    // and the caller interprets ZERO as "EV negative, reject this signal"
    @Test
    void compute_priceNearOne_rejectsSignal() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.65"),
                new BigDecimal("0.99"),
                new BigDecimal("1000"),
                new BigDecimal("0.30"),
                new BigDecimal("5"),
                new BigDecimal("100"),
                null
        );
        assertEquals(BigDecimal.ZERO.setScale(2), result);
    }

    // entryPrice=0.01 (near 0) → market nearly resolved on NO side → signal rejected (ZERO)
    @Test
    void compute_priceNearZero_rejectsSignal() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.65"),
                new BigDecimal("0.01"),
                new BigDecimal("1000"),
                new BigDecimal("0.30"),
                new BigDecimal("5"),
                new BigDecimal("100"),
                null
        );
        assertEquals(BigDecimal.ZERO.setScale(2), result);
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
                new BigDecimal("100"),
                null
        );
        // f*=(0.60*1-0.40)/1=0.20; stake=10*0.3*0.20=0.60 < minOrder=5 → 5.00
        assertEquals(new BigDecimal("5.00"), result);
    }

    // Bug fix: calibratedFollowProbability is a follow-worthiness composite score, not a true event
    // win probability. With edgeFactor, it is mapped via: p_win = 0.5 + (followProb - 0.5) * edgeFactor.
    // This converts a "75% likely to be worth following" into a conservative win-rate estimate.
    //
    // followProb=0.65, edgeFactor=0.15:
    //   p_win = 0.5 + (0.65 - 0.5) * 0.15 = 0.5 + 0.0225 = 0.5225
    // b = (1 - 0.4) / 0.4 = 1.5
    // f* = (0.5225 * 1.5 - 0.4775) / 1.5 = 0.30625 / 1.5 = 0.20416667
    // stake = 1000 * 0.3 * 0.20416667 = 61.25 (below maxStake=100 → not capped)
    @Test
    void compute_withEdgeFactor_convertsFollowProbabilityToConservativeWinProb() {
        BigDecimal result = calculator.compute(
                new BigDecimal("0.65"),   // calibratedFollowProbability (follow-worthiness, not win rate)
                new BigDecimal("0.40"),   // entryPrice
                new BigDecimal("1000"),   // availableCapital
                new BigDecimal("0.30"),   // kellyFraction
                new BigDecimal("1"),      // minOrderUsdc
                new BigDecimal("100"),    // maxStakeUsdc
                new BigDecimal("0.15")    // kellySignalEdgeFactor: converts followProb → p_win
        );
        assertEquals(new BigDecimal("61.25"), result,
                "Edge factor=0.15 must map followProb=0.65 to p_win=0.5225, yielding stake=61.25");
    }
}
