package com.metaverse.coin.strategy.htx.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketMakerMathTest {

    @Test
    void actualBuyPrice_usesFilledCashAndFallsBackToLimit() {
        assertEquals(new BigDecimal("59700.00"), MarketMakerMath.actualBuyPrice(
                new BigDecimal("17.91"), new BigDecimal("0.0003"), new BigDecimal("60000")));
        assertEquals(new BigDecimal("60000.00"), MarketMakerMath.actualBuyPrice(
                null, new BigDecimal("0.0003"), new BigDecimal("60000")));
    }

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

    @Test void uses_symbol_specific_quantity_scale() {
        assertEquals(6, MarketMakerMath.quantityScale("btcusdt"));
        assertEquals(4, MarketMakerMath.quantityScale("ethusdt"));
        assertEquals(6, MarketMakerMath.quantityScale("xautusdt"));
    }

    @Test void sellFloor_equals_buyPrice_scale2() {
        assertEquals(0, new BigDecimal("64515.00")
                .compareTo(MarketMakerMath.sellFloor(new BigDecimal("64515"))));
    }

    @Test void desiredSell_up_follows_ask_when_ask_above_buy() {
        // 涨：ask=64800 > buy=64515 → desiredSell=64800
        assertEquals(0, new BigDecimal("64800.00").compareTo(
                MarketMakerMath.desiredSellPrice(new BigDecimal("64515"), new BigDecimal("64800"))));
    }

    @Test void desiredSell_flat_rests_at_ask_ge_buy() {
        // 平：ask=64520 ≈ buy=64515，ask≥buy → 取 ask
        assertEquals(0, new BigDecimal("64520.00").compareTo(
                MarketMakerMath.desiredSellPrice(new BigDecimal("64515"), new BigDecimal("64520"))));
    }

    @Test void desiredSell_milddown_rests_at_buy_floor() {
        // 微跌：ask=64400 < buy=64515 → 取保本地板 buy=64515
        assertEquals(0, new BigDecimal("64515.00").compareTo(
                MarketMakerMath.desiredSellPrice(new BigDecimal("64515"), new BigDecimal("64400"))));
    }

    @Test void desiredSell_is_never_below_ask() {
        BigDecimal d = MarketMakerMath.desiredSellPrice(new BigDecimal("64515"), new BigDecimal("64800"));
        assertTrue(d.compareTo(new BigDecimal("64800")) >= 0);
    }

    @Test void shouldRepriceSell_true_at_threshold_boundary() {
        // |64580-64515|/64515 = 0.001007 ≥ 0.001 → true
        assertTrue(MarketMakerMath.shouldRepriceSell(
                new BigDecimal("64580"), new BigDecimal("64515"), new BigDecimal("0.001")));
    }

    @Test void shouldRepriceSell_false_below_threshold() {
        // |64550-64515|/64515 = 0.000542 < 0.001 → false
        assertFalse(MarketMakerMath.shouldRepriceSell(
                new BigDecimal("64550"), new BigDecimal("64515"), new BigDecimal("0.001")));
    }

    @Test void shouldRepriceSell_true_when_desired_drops_below_current() {
        // 反向漂移（desired 低于当前挂价）也触发：|64000-64515|/64000 大
        assertTrue(MarketMakerMath.shouldRepriceSell(
                new BigDecimal("64000"), new BigDecimal("64515"), new BigDecimal("0.001")));
    }

    @Test void shouldStopLoss_true_when_bestbid_exactly_on_line() {
        // 止损线 = 64515*(1-0.005) = 64192.425；bestBid 恰好等于 → true
        assertTrue(MarketMakerMath.shouldStopLoss(
                new BigDecimal("64192.425"), new BigDecimal("64515"), new BigDecimal("0.005")));
    }

    @Test void shouldStopLoss_false_when_bestbid_above_line() {
        // bestBid=64200 > 64192.425 → false
        assertFalse(MarketMakerMath.shouldStopLoss(
                new BigDecimal("64200"), new BigDecimal("64515"), new BigDecimal("0.005")));
    }

    @Test void shouldStopLoss_true_on_deep_drop() {
        assertTrue(MarketMakerMath.shouldStopLoss(
                new BigDecimal("64000"), new BigDecimal("64515"), new BigDecimal("0.005")));
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test void takerExit_noFee_noTolerance_isBreakEvenAtBuyPrice() {
        assertTrue(MarketMakerMath.isTakerExitAcceptable(bd("100"), bd("100"), bd("0"), bd("0")));
        assertFalse(MarketMakerMath.isTakerExitAcceptable(bd("100"), bd("99.99"), bd("0"), bd("0")));
    }

    @Test void takerExit_withFee_noTolerance_needsPriceAboveBuyToCoverBothFees() {
        assertFalse(MarketMakerMath.isTakerExitAcceptable(bd("100"), bd("100"), bd("0.002"), bd("0")));
        assertTrue(MarketMakerMath.isTakerExitAcceptable(bd("100"), bd("100.5"), bd("0.002"), bd("0")));
    }

    @Test void takerExit_withFee_andTolerance_loosensThreshold() {
        assertFalse(MarketMakerMath.isTakerExitAcceptable(bd("100"), bd("100"), bd("0.002"), bd("0.002")));
        assertTrue(MarketMakerMath.isTakerExitAcceptable(bd("100"), bd("100.3"), bd("0.002"), bd("0.002")));
        assertFalse(MarketMakerMath.isTakerExitAcceptable(bd("100"), bd("100.1"), bd("0.002"), bd("0.002")));
    }

    @Test void takerExit_toleranceEqualityBoundaryInclusive() {
        assertTrue(MarketMakerMath.isTakerExitAcceptable(bd("1000"), bd("998"), bd("0"), bd("0.002")));
        assertFalse(MarketMakerMath.isTakerExitAcceptable(bd("1000"), bd("997.99"), bd("0"), bd("0.002")));
    }

    @Test void takerBuy_acceptable_atBoundaryInclusive() {
        // 恰在边界 ask == bid·(1+x)：bid=60000, x=0.001 → 线=60060，ask=60060 → true（含等号）
        assertTrue(MarketMakerMath.isTakerBuyAcceptable(bd("60000"), bd("60060"), bd("0.001")));
        // 略窄 → true；略宽 → false
        assertTrue(MarketMakerMath.isTakerBuyAcceptable(bd("60000"), bd("60059.99"), bd("0.001")));
        assertFalse(MarketMakerMath.isTakerBuyAcceptable(bd("60000"), bd("60060.01"), bd("0.001")));
    }

    @Test void takerBuy_wideSpread_xaut_blocked_narrowSpread_btc_passes() {
        // xautusdt 宽价差 bid 4265 / ask 4291（~0.6%）在 x=0.001 下 → false（被挡）
        assertFalse(MarketMakerMath.isTakerBuyAcceptable(bd("4265"), bd("4291"), bd("0.001")));
        // btc 窄价差 bid 64000 / ask 64010（~0.016%）在 x=0.001 下 → true（可吃）
        assertTrue(MarketMakerMath.isTakerBuyAcceptable(bd("64000"), bd("64010"), bd("0.001")));
    }
}
