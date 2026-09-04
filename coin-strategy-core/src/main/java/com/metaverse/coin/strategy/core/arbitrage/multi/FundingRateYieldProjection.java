package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/** Projects funding rates across a common settlement window. */
public final class FundingRateYieldProjection {
    private FundingRateYieldProjection() {
    }

    public record Direction(NormalizedFundingRateSnapshot longQuote,
            NormalizedFundingRateSnapshot shortQuote, BigDecimal projectedSpread) {
    }

    public record QuoteDirection(FundingRateQuote longQuote,
            FundingRateQuote shortQuote, BigDecimal projectedSpread) {
    }

    public static Direction bestDirection(NormalizedFundingRateSnapshot first,
            NormalizedFundingRateSnapshot second, Instant now) {
        BigDecimal firstLongProjected = projectedSpread(first, second, now);
        BigDecimal secondLongProjected = projectedSpread(second, first, now);
        if (firstLongProjected.compareTo(secondLongProjected) > 0) {
            return new Direction(first, second, firstLongProjected);
        }
        if (secondLongProjected.compareTo(firstLongProjected) > 0) {
            return new Direction(second, first, secondLongProjected);
        }
        NormalizedFundingRateSnapshot low = first.hourlyFundingRate()
                .compareTo(second.hourlyFundingRate()) <= 0 ? first : second;
        NormalizedFundingRateSnapshot high = low == first ? second : first;
        return new Direction(low, high, firstLongProjected);
    }

    public static QuoteDirection bestDirection(FundingRateQuote first, FundingRateQuote second) {
        BigDecimal firstLongProjected = projectedSpread(first, second);
        BigDecimal secondLongProjected = projectedSpread(second, first);
        if (firstLongProjected.compareTo(secondLongProjected) > 0) {
            return new QuoteDirection(first, second, firstLongProjected);
        }
        if (secondLongProjected.compareTo(firstLongProjected) > 0) {
            return new QuoteDirection(second, first, secondLongProjected);
        }
        FundingRateQuote low = first.hourlyFundingRate()
                .compareTo(second.hourlyFundingRate()) <= 0 ? first : second;
        FundingRateQuote high = low == first ? second : first;
        return new QuoteDirection(low, high, firstLongProjected);
    }

    public static BigDecimal projectedSpread(NormalizedFundingRateSnapshot longQuote,
            NormalizedFundingRateSnapshot shortQuote) {
        return projectedRawSpread(longQuote.rawFundingRate(), longQuote.fundingIntervalHours(),
                shortQuote.rawFundingRate(), shortQuote.fundingIntervalHours());
    }

    public static BigDecimal projectedSpread(NormalizedFundingRateSnapshot longQuote,
            NormalizedFundingRateSnapshot shortQuote, Instant now) {
        return projectedSpread(longQuote.rawFundingRate(), longQuote.fundingIntervalHours(),
                longQuote.nextFundingTime(), shortQuote.rawFundingRate(),
                shortQuote.fundingIntervalHours(), shortQuote.nextFundingTime(), now);
    }

    public static BigDecimal projectedSpread(FundingRateQuote longQuote,
            FundingRateQuote shortQuote) {
        return projectedRawSpread(longQuote.fundingRate(), longQuote.fundingIntervalHours(),
                shortQuote.fundingRate(), shortQuote.fundingIntervalHours());
    }

    public static BigDecimal projectedSpread(BigDecimal longHourlyRate, int longIntervalHours,
            BigDecimal shortHourlyRate, int shortIntervalHours) {
        if (longHourlyRate == null || shortHourlyRate == null
                || longIntervalHours <= 0 || shortIntervalHours <= 0) {
            throw new IllegalArgumentException("complete positive funding intervals are required");
        }
        int horizonHours = lcm(longIntervalHours, shortIntervalHours);
        return shortHourlyRate.subtract(longHourlyRate).multiply(BigDecimal.valueOf(horizonHours));
    }

    public static BigDecimal projectedSpread(BigDecimal longFundingRate, int longIntervalHours,
            Instant longNextFundingTime, BigDecimal shortFundingRate, int shortIntervalHours,
            Instant shortNextFundingTime, Instant now) {
        if (longFundingRate == null || shortFundingRate == null || longNextFundingTime == null
                || shortNextFundingTime == null || now == null
                || longIntervalHours <= 0 || shortIntervalHours <= 0) {
            throw new IllegalArgumentException("complete positive funding intervals are required");
        }
        int windowHours = lcm(longIntervalHours, shortIntervalHours);
        long longSettlements = settlementCount(longNextFundingTime, longIntervalHours, now,
                windowHours);
        long shortSettlements = settlementCount(shortNextFundingTime, shortIntervalHours, now,
                windowHours);
        return shortFundingRate.multiply(BigDecimal.valueOf(shortSettlements))
                .subtract(longFundingRate.multiply(BigDecimal.valueOf(longSettlements)));
    }

    private static BigDecimal projectedRawSpread(BigDecimal longFundingRate, int longIntervalHours,
            BigDecimal shortFundingRate, int shortIntervalHours) {
        if (longFundingRate == null || shortFundingRate == null
                || longIntervalHours <= 0 || shortIntervalHours <= 0) {
            throw new IllegalArgumentException("complete positive funding intervals are required");
        }
        int windowHours = lcm(longIntervalHours, shortIntervalHours);
        return shortFundingRate.multiply(BigDecimal.valueOf(windowHours / shortIntervalHours))
                .subtract(longFundingRate.multiply(BigDecimal.valueOf(windowHours / longIntervalHours)));
    }

    private static long settlementCount(Instant nextFundingTime, int intervalHours, Instant now,
            int windowHours) {
        long intervalSeconds = Duration.ofHours(intervalHours).getSeconds();
        Instant firstSettlement = nextFundingTime;
        if (firstSettlement.isBefore(now)) {
            long skippedPeriods = Duration.between(firstSettlement, now).getSeconds() / intervalSeconds;
            firstSettlement = firstSettlement.plusSeconds(skippedPeriods * intervalSeconds);
            while (!firstSettlement.isAfter(now)) {
                firstSettlement = firstSettlement.plusSeconds(intervalSeconds);
            }
        } else if (firstSettlement.equals(now)) {
            firstSettlement = firstSettlement.plusSeconds(intervalSeconds);
        }
        Instant windowEnd = now.plus(Duration.ofHours(windowHours));
        if (firstSettlement.isAfter(windowEnd)) {
            return 0;
        }
        return Duration.between(firstSettlement, windowEnd).getSeconds() / intervalSeconds + 1;
    }

    private static int lcm(int left, int right) {
        return Math.abs(left / gcd(left, right) * right);
    }

    private static int gcd(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a;
    }
}
