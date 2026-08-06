package com.metaverse.coin.strategy.htx.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketMakerMathTest {

    private static final BigDecimal F = new BigDecimal("0.002");

    @Test void buyPrice_is_p0_times_one_minus_spread_scale2() {
        BigDecimal p0 = new BigDecimal("60000");
        assertEquals(0, new BigDecimal("59700.00")
                .compareTo(MarketMakerMath.buyPrice(p0, new BigDecimal("0.005"))));
    }

    @Test void sellPrice_is_p0_times_one_plus_spread_scale2() {
        BigDecimal p0 = new BigDecimal("60000");
        assertEquals(0, new BigDecimal("60300.00")
                .compareTo(MarketMakerMath.sellPrice(p0, new BigDecimal("0.005"))));
    }

    @Test void qtyFor_is_notional_over_buyprice_scale4_halfdown() {
        // 20 / 59700.00 = 0.000335... → scale4 HALF_DOWN = 0.0003
        assertEquals(0, new BigDecimal("0.0003")
                .compareTo(MarketMakerMath.qtyFor(new BigDecimal("20"), new BigDecimal("59700.00"))));
    }

    @Test void isNoLoss_true_when_exactly_equal() {
        assertTrue(MarketMakerMath.isNoLoss(
                new BigDecimal("996004"), new BigDecimal("1000000"), F));
    }

    @Test void isNoLoss_false_when_buy_one_higher() {
        assertFalse(MarketMakerMath.isNoLoss(
                new BigDecimal("996005"), new BigDecimal("1000000"), F));
    }

    @Test void isNoLoss_false_when_spread_too_small_vs_fee() {
        BigDecimal p0 = new BigDecimal("100");
        BigDecimal buy = MarketMakerMath.buyPrice(p0, new BigDecimal("0.001"));   // 99.90
        BigDecimal sell = MarketMakerMath.sellPrice(p0, new BigDecimal("0.001")); // 100.10
        assertFalse(MarketMakerMath.isNoLoss(buy, sell, F));
    }

    @Test void isNoLoss_true_at_default_spread() {
        BigDecimal p0 = new BigDecimal("100");
        BigDecimal buy = MarketMakerMath.buyPrice(p0, new BigDecimal("0.005"));   // 99.50
        BigDecimal sell = MarketMakerMath.sellPrice(p0, new BigDecimal("0.005")); // 100.50
        assertTrue(MarketMakerMath.isNoLoss(buy, sell, F));
    }

    @Test void farEnough_true_when_no_existing() {
        assertTrue(MarketMakerMath.farEnough(
                new BigDecimal("100"), List.of(), new BigDecimal("0.003")));
    }

    @Test void farEnough_false_when_candidate_too_close() {
        // |100 - 100.2|/100.2 = 0.001996 < 0.003 → 太近
        assertFalse(MarketMakerMath.farEnough(
                new BigDecimal("100"), List.of(new BigDecimal("100.2")), new BigDecimal("0.003")));
    }

    @Test void farEnough_true_when_all_far() {
        assertTrue(MarketMakerMath.farEnough(
                new BigDecimal("100"),
                List.of(new BigDecimal("101"), new BigDecimal("98")),
                new BigDecimal("0.003")));
    }
}
