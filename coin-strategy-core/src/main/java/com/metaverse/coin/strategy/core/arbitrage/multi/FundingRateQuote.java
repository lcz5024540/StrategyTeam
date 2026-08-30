package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Normalized funding quote. Rates are decimal rates per settlement period. */
public record FundingRateQuote(Exchange exchange, String symbol, BigDecimal fundingRate,
                               int fundingIntervalHours, BigDecimal markPrice, Instant settlementTime) {
    /** Compatibility constructor for legacy callers whose quotes use the historical eight-hour period. */
    public FundingRateQuote(Exchange exchange, String symbol, BigDecimal fundingRate,
            BigDecimal markPrice, Instant settlementTime) {
        this(exchange, symbol, fundingRate, 8, markPrice, settlementTime);
    }

    public FundingRateQuote {
        if (exchange == null || symbol == null || fundingRate == null || markPrice == null) {
            throw new IllegalArgumentException("exchange, symbol, fundingRate and markPrice are required");
        }
        if (fundingIntervalHours <= 0 || markPrice.signum() <= 0) {
            throw new IllegalArgumentException("funding interval and markPrice must be positive");
        }
    }

    public BigDecimal hourlyFundingRate() {
        return fundingRate.divide(BigDecimal.valueOf(fundingIntervalHours), 16, RoundingMode.HALF_UP);
    }
}
