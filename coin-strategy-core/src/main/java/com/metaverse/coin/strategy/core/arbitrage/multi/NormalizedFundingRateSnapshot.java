package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/** Exchange-neutral USDT perpetual funding snapshot. */
public record NormalizedFundingRateSnapshot(
        Exchange exchange, String canonicalSymbol, BigDecimal rawFundingRate,
        int fundingIntervalHours, Instant nextFundingTime, BigDecimal markPrice,
        BigDecimal indexPrice, Instant receivedAt) {

    public NormalizedFundingRateSnapshot {
        if (exchange == null || canonicalSymbol == null || canonicalSymbol.isBlank()) throw new IllegalArgumentException("exchange and symbol are required");
        if (rawFundingRate == null || markPrice == null || indexPrice == null || nextFundingTime == null || receivedAt == null) throw new IllegalArgumentException("funding snapshot is incomplete");
        if (fundingIntervalHours <= 0 || markPrice.signum() <= 0 || indexPrice.signum() <= 0) throw new IllegalArgumentException("invalid funding interval or price");
    }

    public BigDecimal hourlyFundingRate() {
        return rawFundingRate.divide(BigDecimal.valueOf(fundingIntervalHours), 16, RoundingMode.HALF_UP);
    }

    public boolean isStale(Instant now, Duration maximumAge) {
        return receivedAt.plus(maximumAge).isBefore(now);
    }
}
