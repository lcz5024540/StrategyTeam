package com.metaverse.coin.strategy.core.arbitrage.spotperp;

import com.metaverse.coin.strategy.core.arbitrage.spotperp.SpotPerpInstrument.SpotPerpVenue;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Hard dry-run rules for single-venue spot-long / perp-short funding harvest.
 *
 * <p>Live execution is not implemented. {@code dryRun} must stay {@code true}.</p>
 */
public record SpotPerpRules(
        boolean dryRun,
        boolean autoOpenEnabled,
        BigDecimal notionalUsdt,
        int leverage,
        BigDecimal openThreshold,
        BigDecimal closeThreshold,
        BigDecimal minBasisRate,
        BigDecimal maxBasisRate,
        BigDecimal maxBasisDeteriorationRate,
        BigDecimal minNetYieldRate,
        int minFundingPeriods,
        Duration settlementProtection,
        Duration minHolding,
        Duration maxHolding,
        Duration maxQuoteAge,
        BigDecimal minBookNotional,
        BigDecimal coverageRatio,
        BigDecimal maxVwapImpact,
        BigDecimal maxLotOvershoot,
        Map<SpotPerpVenue, BigDecimal> roundTripFeeRates
) {
    private static final BigDecimal BPS = new BigDecimal("10000");

    public SpotPerpRules {
        Objects.requireNonNull(notionalUsdt, "notionalUsdt");
        Objects.requireNonNull(openThreshold, "openThreshold");
        Objects.requireNonNull(closeThreshold, "closeThreshold");
        Objects.requireNonNull(minBasisRate, "minBasisRate");
        Objects.requireNonNull(maxBasisRate, "maxBasisRate");
        Objects.requireNonNull(maxBasisDeteriorationRate, "maxBasisDeteriorationRate");
        Objects.requireNonNull(minNetYieldRate, "minNetYieldRate");
        Objects.requireNonNull(settlementProtection, "settlementProtection");
        Objects.requireNonNull(minHolding, "minHolding");
        Objects.requireNonNull(maxHolding, "maxHolding");
        Objects.requireNonNull(maxQuoteAge, "maxQuoteAge");
        Objects.requireNonNull(minBookNotional, "minBookNotional");
        Objects.requireNonNull(coverageRatio, "coverageRatio");
        Objects.requireNonNull(maxVwapImpact, "maxVwapImpact");
        Objects.requireNonNull(maxLotOvershoot, "maxLotOvershoot");
        roundTripFeeRates = Map.copyOf(Objects.requireNonNull(roundTripFeeRates, "roundTripFeeRates"));
        if (!dryRun) {
            throw new IllegalArgumentException(
                    "live spot-perp trading is not implemented; dry-run must stay true");
        }
        if (notionalUsdt.signum() <= 0) {
            throw new IllegalArgumentException("notionalUsdt must be positive");
        }
        if (leverage < 1 || leverage > 5) {
            throw new IllegalArgumentException("leverage must be in 1..5 for this dry-run");
        }
        if (minFundingPeriods < 1) {
            throw new IllegalArgumentException("minFundingPeriods must be >= 1");
        }
        if (closeThreshold.compareTo(openThreshold) > 0) {
            throw new IllegalArgumentException("closeThreshold must be <= openThreshold");
        }
        if (maxHolding.compareTo(minHolding) < 0) {
            throw new IllegalArgumentException("maxHolding must be >= minHolding");
        }
        if (maxVwapImpact.signum() <= 0 || coverageRatio.signum() <= 0) {
            throw new IllegalArgumentException("liquidity gates must be positive");
        }
        for (SpotPerpVenue venue : SpotPerpVenue.values()) {
            if (!roundTripFeeRates.containsKey(venue) || roundTripFeeRates.get(venue).signum() < 0) {
                throw new IllegalArgumentException("round-trip fee rate required for " + venue);
            }
        }
    }

    /**
     * Production defaults: 100 USDT per symbol, 2x perp hedge, taker IOC both legs,
     * enter only when projected funding over ~10 days can cover retail round-trip fees.
     */
    public static SpotPerpRules defaults() {
        EnumMap<SpotPerpVenue, BigDecimal> fees = new EnumMap<>(SpotPerpVenue.class);
        // Spot taker 10 bps + USDT-M taker 4 bps, both sides of the round trip.
        fees.put(SpotPerpVenue.BINANCE, new BigDecimal("0.0028"));
        // HTX spot taker is typically wider; keep the harvest gate conservative.
        fees.put(SpotPerpVenue.HTX, new BigDecimal("0.0048"));
        return new SpotPerpRules(
                true,
                true,
                new BigDecimal("100"),
                2,
                new BigDecimal("0.00008"),
                new BigDecimal("0.00002"),
                new BigDecimal("-0.0005"),
                new BigDecimal("0.0020"),
                new BigDecimal("0.0015"),
                BigDecimal.ZERO,
                30,
                Duration.ofMinutes(10),
                Duration.ofHours(8),
                Duration.ofDays(21),
                Duration.ofSeconds(15),
                new BigDecimal("500"),
                new BigDecimal("2.0"),
                new BigDecimal("0.0005"),
                new BigDecimal("1.25"),
                fees
        );
    }

    public static SpotPerpRules fromEnvironment(Environment environment) {
        SpotPerpRules defaults = defaults();
        if (environment == null) {
            return defaults;
        }
        boolean dryRun = bool(environment, "arbitrage.spot-perp-funding.dry-run", true);
        EnumMap<SpotPerpVenue, BigDecimal> fees = new EnumMap<>(SpotPerpVenue.class);
        fees.put(SpotPerpVenue.BINANCE, decimal(environment,
                "arbitrage.spot-perp-funding.fees.binance-round-trip",
                defaults.roundTripFeeRates.get(SpotPerpVenue.BINANCE)));
        fees.put(SpotPerpVenue.HTX, decimal(environment,
                "arbitrage.spot-perp-funding.fees.htx-round-trip",
                defaults.roundTripFeeRates.get(SpotPerpVenue.HTX)));
        return new SpotPerpRules(
                dryRun,
                bool(environment, "arbitrage.spot-perp-funding.auto-open-enabled", true),
                decimal(environment, "arbitrage.spot-perp-funding.notional-usdt", defaults.notionalUsdt),
                integer(environment, "arbitrage.spot-perp-funding.leverage", defaults.leverage),
                decimal(environment, "arbitrage.spot-perp-funding.open-threshold", defaults.openThreshold),
                decimal(environment, "arbitrage.spot-perp-funding.close-threshold", defaults.closeThreshold),
                decimal(environment, "arbitrage.spot-perp-funding.min-basis-rate", defaults.minBasisRate),
                decimal(environment, "arbitrage.spot-perp-funding.max-basis-rate", defaults.maxBasisRate),
                decimal(environment, "arbitrage.spot-perp-funding.max-basis-deterioration-rate",
                        defaults.maxBasisDeteriorationRate),
                decimal(environment, "arbitrage.spot-perp-funding.min-net-yield-rate", defaults.minNetYieldRate),
                integer(environment, "arbitrage.spot-perp-funding.min-funding-periods", defaults.minFundingPeriods),
                Duration.ofMinutes(longValue(environment,
                        "arbitrage.spot-perp-funding.settlement-protection-minutes",
                        defaults.settlementProtection.toMinutes())),
                Duration.ofHours(longValue(environment,
                        "arbitrage.spot-perp-funding.min-holding-hours",
                        defaults.minHolding.toHours())),
                Duration.ofHours(longValue(environment,
                        "arbitrage.spot-perp-funding.max-holding-hours",
                        defaults.maxHolding.toHours())),
                Duration.ofMillis(longValue(environment,
                        "arbitrage.spot-perp-funding.max-quote-age-ms",
                        defaults.maxQuoteAge.toMillis())),
                decimal(environment, "arbitrage.spot-perp-funding.min-book-notional", defaults.minBookNotional),
                decimal(environment, "arbitrage.spot-perp-funding.coverage-ratio", defaults.coverageRatio),
                decimal(environment, "arbitrage.spot-perp-funding.max-vwap-impact", defaults.maxVwapImpact),
                decimal(environment, "arbitrage.spot-perp-funding.max-lot-overshoot", defaults.maxLotOvershoot),
                fees
        );
    }

    public BigDecimal roundTripFee(SpotPerpVenue venue) {
        return roundTripFeeRates.get(venue);
    }

    public BigDecimal maxVwapImpactBps() {
        return maxVwapImpact.multiply(BPS);
    }

    public BigDecimal requiredCoverageNotional() {
        return notionalUsdt.multiply(coverageRatio);
    }

    private static BigDecimal decimal(Environment environment, String key, BigDecimal fallback) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? fallback : new BigDecimal(value.trim());
    }

    private static int integer(Environment environment, String key, int fallback) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static long longValue(Environment environment, String key, long fallback) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
    }

    private static boolean bool(Environment environment, String key, boolean fallback) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }
}
