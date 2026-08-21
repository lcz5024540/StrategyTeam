package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;

/** Round-trip cost assumptions for one exchange, expressed as decimal rates. */
public record ExchangeCost(BigDecimal makerFeeRate, BigDecimal takerFeeRate, BigDecimal slippageRate) {
    public ExchangeCost {
        if (makerFeeRate == null || takerFeeRate == null || slippageRate == null
                || makerFeeRate.signum() < 0 || takerFeeRate.signum() < 0 || slippageRate.signum() < 0) {
            throw new IllegalArgumentException("cost rates must be non-negative");
        }
    }
    public BigDecimal roundTripTakerCost() {
        return oneWayTakerCost().multiply(BigDecimal.valueOf(2));
    }

    public BigDecimal oneWayTakerCost() {
        return takerFeeRate.add(slippageRate);
    }
}
