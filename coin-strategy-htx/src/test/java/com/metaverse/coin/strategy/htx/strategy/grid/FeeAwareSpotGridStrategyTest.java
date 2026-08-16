package com.metaverse.coin.strategy.htx.strategy.grid;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeeAwareSpotGridStrategyTest {

    private final FeeAwareSpotGridStrategy strategy = new FeeAwareSpotGridStrategy();

    @Test
    void quoteRaisesSellPriceToFeeFloorWhenConfiguredSpreadIsTooSmall() {
        SpotGridStrategy.GridQuote quote = strategy.quote(
                bd("100"), bd("0.0001"), bd("0.002"), bd("0.002"), 2);

        assertEquals(bd("100.41"), quote.sellPrice());
        assertTrue(quote.sellPrice().compareTo(quote.buyPrice()) > 0);
        assertTrue(strategy.coversFees(quote.buyPrice(), quote.sellPrice(), bd("0.002"), bd("0.002")));
    }

    @Test
    void fillPricingUsesActualBaseFeeAndCoversTheSellFee() {
        BigDecimal sell = strategy.sellPriceAfterFill(
                bd("100"), bd("1"), bd("0.003"), bd("100"), bd("99"),
                bd("0.002"), bd("0.002"), 2);

        assertEquals(bd("100.51"), sell);
        BigDecimal netProceeds = bd("0.997").multiply(sell).multiply(bd("0.998"));
        assertTrue(netProceeds.compareTo(bd("100")) >= 0);
    }

    @Test
    void targetNeverDropsBelowFeeFloorWhenMarketAskFalls() {
        BigDecimal sell = strategy.sellPriceAfterFill(
                bd("4200"), bd("1"), bd("0.002"), bd("4200"), bd("4100"),
                bd("0.002"), bd("0.002"), 2);

        assertEquals(bd("4216.86"), sell);
        assertTrue(sell.compareTo(bd("4200")) > 0);
    }

    @Test
    void feeCoverageRequiresStrictlyHigherSellPrice() {
        assertFalse(strategy.coversFees(bd("100"), bd("100"), bd("0"), bd("0")));
        assertTrue(strategy.coversFees(bd("100"), bd("100.01"), bd("0"), bd("0")));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
