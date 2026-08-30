package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;

/** Projects normalized hourly funding rates across the longer leg's settlement interval. */
public final class FundingRateYieldProjection {
    private FundingRateYieldProjection() {
    }

    public static BigDecimal projectedSpread(NormalizedFundingRateSnapshot longQuote,
            NormalizedFundingRateSnapshot shortQuote) {
        return projectedSpread(longQuote.hourlyFundingRate(), longQuote.fundingIntervalHours(),
                shortQuote.hourlyFundingRate(), shortQuote.fundingIntervalHours());
    }

    public static BigDecimal projectedSpread(FundingRateQuote longQuote,
            FundingRateQuote shortQuote) {
        return projectedSpread(longQuote.hourlyFundingRate(), longQuote.fundingIntervalHours(),
                shortQuote.hourlyFundingRate(), shortQuote.fundingIntervalHours());
    }

    public static BigDecimal projectedSpread(BigDecimal longHourlyRate, int longIntervalHours,
            BigDecimal shortHourlyRate, int shortIntervalHours) {
        if (longHourlyRate == null || shortHourlyRate == null
                || longIntervalHours <= 0 || shortIntervalHours <= 0) {
            throw new IllegalArgumentException("complete positive funding intervals are required");
        }
        int horizonHours = Math.max(longIntervalHours, shortIntervalHours);
        return shortHourlyRate.subtract(longHourlyRate).multiply(BigDecimal.valueOf(horizonHours));
    }
}
