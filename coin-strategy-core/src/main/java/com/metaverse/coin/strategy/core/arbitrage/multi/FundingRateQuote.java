package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;

/** Normalized funding quote. Rates are decimal rates per settlement period. */
public record FundingRateQuote(Exchange exchange, String symbol, BigDecimal fundingRate,
                               BigDecimal markPrice, Instant settlementTime) {
    public FundingRateQuote {
        if (exchange == null || symbol == null || fundingRate == null || markPrice == null) {
            throw new IllegalArgumentException("exchange, symbol, fundingRate and markPrice are required");
        }
        if (markPrice.signum() <= 0) throw new IllegalArgumentException("markPrice must be positive");
    }
}
