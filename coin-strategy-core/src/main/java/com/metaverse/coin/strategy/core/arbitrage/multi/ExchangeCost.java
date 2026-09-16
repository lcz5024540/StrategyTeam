package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;

/** Round-trip cost assumptions for one exchange, expressed as decimal rates. */
public record ExchangeCost(BigDecimal makerFeeRate, BigDecimal takerFeeRate, BigDecimal slippageRate,
                           BigDecimal capitalTransferCostRate) {
    public ExchangeCost(BigDecimal makerFeeRate, BigDecimal takerFeeRate, BigDecimal slippageRate) {
        this(makerFeeRate, takerFeeRate, slippageRate, BigDecimal.ZERO);
    }

    public ExchangeCost {
        if (makerFeeRate == null || takerFeeRate == null || slippageRate == null
                || capitalTransferCostRate == null || makerFeeRate.signum() < 0
                || takerFeeRate.signum() < 0 || slippageRate.signum() < 0
                || capitalTransferCostRate.signum() < 0) {
            throw new IllegalArgumentException("cost rates must be non-negative");
        }
    }
    public BigDecimal roundTripTakerCost() {
        return oneWayTakerCost().multiply(BigDecimal.valueOf(2));
    }

    public BigDecimal oneWayTakerCost() {
        return takerFeeRate.add(slippageRate);
    }

    /** One-time capital movement cost, amortized into a Paper opportunity's expected yield. */
    public BigDecimal amortizedCapitalTransferCost() {
        return capitalTransferCostRate;
    }
}
