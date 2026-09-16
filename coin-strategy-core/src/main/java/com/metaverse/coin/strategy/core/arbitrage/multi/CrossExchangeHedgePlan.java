package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;

public record CrossExchangeHedgePlan(FundingRateSpread spread, HedgeLeg longLeg, HedgeLeg shortLeg) {
    public CrossExchangeHedgePlan {
        if (spread == null || longLeg == null || shortLeg == null) throw new IllegalArgumentException("plan is incomplete");
        if (longLeg.exchange() != spread.longLeg().exchange() || shortLeg.exchange() != spread.shortLeg().exchange()) {
            throw new IllegalArgumentException("legs do not match funding spread");
        }
        if (longLeg.quantity().compareTo(shortLeg.quantity()) != 0) throw new IllegalArgumentException("hedge quantities must match");
    }
    public BigDecimal quantity() { return longLeg.quantity(); }
}
