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

    @Test void sellFromBuy_is_buy_times_one_plus_two_spread_scale2() {
        // 60000 * (1 + 2*0.003) = 60000 * 1.006 = 60360.00
        assertEquals(0, new BigDecimal("60360.00")
                .compareTo(MarketMakerMath.sellFromBuy(new BigDecimal("60000.00"), new BigDecimal("0.003"))));
    }

    @Test void sellFromBuy_no_loss_holds_at_default_spread() {
        BigDecimal buy = new BigDecimal("60000.00");
        BigDecimal sell = MarketMakerMath.sellFromBuy(buy, new BigDecimal("0.003"));
        assertTrue(MarketMakerMath.isNoLoss(buy, sell, new BigDecimal("0.002")));
    }

    @Test void sellFromBuy_no_loss_breaks_when_spread_below_fee_floor() {
        BigDecimal buy = new BigDecimal("60000.00");
        BigDecimal sell = MarketMakerMath.sellFromBuy(buy, new BigDecimal("0.001")); // 2δ=0.002 < 2f 磨损
        assertFalse(MarketMakerMath.isNoLoss(buy, sell, new BigDecimal("0.002")));
    }

    @Test void shouldReprice_true_when_drift_at_or_above_threshold() {
        // |60000-59940|/60000 = 0.001 == 阈值 → true（含等号）
        assertTrue(MarketMakerMath.shouldReprice(
                new BigDecimal("60000"), new BigDecimal("59940"), new BigDecimal("0.001")));
        // 漂移更大也 true
        assertTrue(MarketMakerMath.shouldReprice(
                new BigDecimal("60000"), new BigDecimal("59700"), new BigDecimal("0.001")));
    }

    @Test void shouldReprice_false_when_drift_below_threshold() {
        // |60000-59950|/60000 = 0.000833 < 0.001 → false
        assertFalse(MarketMakerMath.shouldReprice(
                new BigDecimal("60000"), new BigDecimal("59950"), new BigDecimal("0.001")));
    }

    @Test void shouldReprice_true_when_bestbid_drops_below_current_buy() {
        // 反向漂移（bestBid 低于挂单价）也应触发：|59000-60000|/59000 大
        assertTrue(MarketMakerMath.shouldReprice(
                new BigDecimal("59000"), new BigDecimal("60000"), new BigDecimal("0.001")));
    }
}
