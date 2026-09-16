package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;

public record HedgeLeg(Exchange exchange, String symbol, HedgeSide side, BigDecimal quantity) {
    public HedgeLeg {
        if (exchange == null || symbol == null || side == null || quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("invalid hedge leg");
        }
    }
}
