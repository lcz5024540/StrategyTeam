package com.metaverse.coin.strategy.htx.strategy.contract;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeeAwareContractGridStrategyTest {

    private final FeeAwareContractGridStrategy strategy = new FeeAwareContractGridStrategy();

    @Test
    void computesStrictlyProfitableLongAndShortExitTicks() {
        ContractGridStrategy.PositionCost longCost = cost("4350.00", "0.2001");
        ContractGridStrategy.PositionCost shortCost = cost("4351.00", "0.2001");

        ContractGridStrategy.ExitQuote quote = strategy.exitQuote(longCost, shortCost,
                new BigDecimal("0.0004"), new BigDecimal("0.0002"),
                new BigDecimal("0.0005"), new BigDecimal("0.0010"), new BigDecimal("0.01"));

        assertEquals(new BigDecimal("4360.88"), quote.longClosePrice());
        assertEquals(new BigDecimal("4340.12"), quote.shortClosePrice());
        assertTrue(quote.longClosePrice().compareTo(longCost.averageOpenPrice()) > 0);
        assertTrue(quote.shortClosePrice().compareTo(shortCost.averageOpenPrice()) < 0);
    }

    @Test
    void allowsOnlyConfiguredEntrySpread() {
        assertTrue(strategy.entrySpreadAllowed(new BigDecimal("4350"), new BigDecimal("4352"),
                new BigDecimal("0.0005")));
        assertFalse(strategy.entrySpreadAllowed(new BigDecimal("4350"), new BigDecimal("4353"),
                new BigDecimal("0.0005")));
    }

    @Test
    void rejectsInvalidCostInput() {
        assertThrows(IllegalArgumentException.class, () -> strategy.exitQuote(
                cost("4350", "0"), null, BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.01")));
    }

    private static ContractGridStrategy.PositionCost cost(String price, String fee) {
        return new ContractGridStrategy.PositionCost(new BigDecimal(price), new BigDecimal("0.001"),
                115L, new BigDecimal(fee));
    }
}
