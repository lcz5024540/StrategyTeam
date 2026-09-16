package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderBookSweepTest {
    @Test
    void consumesActualLevelsForTwoThousandUsdtScenario() {
        List<NormalizedOrderBookSnapshot.Level> asks = List.of(
                level("100.00", "500"), level("100.10", "700"), level("100.20", "800"),
                level("100.30", "2000"));
        BigDecimal quantity = new BigDecimal("500").divide(new BigDecimal("100.00"), 18, java.math.RoundingMode.DOWN)
                .add(new BigDecimal("700").divide(new BigDecimal("100.10"), 18, java.math.RoundingMode.DOWN))
                .add(new BigDecimal("800").divide(new BigDecimal("100.20"), 18, java.math.RoundingMode.DOWN));

        OrderBookSweep.Result result = OrderBookSweep.forQuantity(asks, quantity, new BigDecimal("100.00"));

        assertEquals(3, result.slices().size());
        assertEquals(0, new BigDecimal("2000").compareTo(result.filledNotional().setScale(0, java.math.RoundingMode.HALF_UP)));
        assertEquals(new BigDecimal("100.20"), result.worstPrice());
    }

    @Test
    void rejectsWhenDepthCannotFillRequestedQuantity() {
        assertThrows(IllegalStateException.class, () -> OrderBookSweep.forQuantity(
                List.of(level("100", "100")), new BigDecimal("2"), new BigDecimal("100")));
    }

    private NormalizedOrderBookSnapshot.Level level(String price, String notional) {
        return new NormalizedOrderBookSnapshot.Level(new BigDecimal(price), new BigDecimal(notional));
    }
}
