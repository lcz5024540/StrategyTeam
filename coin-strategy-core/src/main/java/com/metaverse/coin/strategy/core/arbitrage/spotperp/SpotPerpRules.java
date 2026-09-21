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
        Map<SpotPerpVenue, BigDecimal> roundTripFeeRates,
        SchemeCPolicy schemeC
) {
    private static final BigDecimal BPS = new BigDecimal("10000");

    /**
     * Timing / leverage overlays. {@link #disabled()} keeps production
     * anytime-entry + 21-day TTL behavior.
     *
     * @param entryWindow when positive, open only if next settlement is inside this window
     * @param maxSettlementsHeld when &gt; 0, close after this many collected funding prints
     * @param minLiquidationDistance remaining short-perp distance to liq; zero disables the stop
     * @param maintenanceMarginRate isolated USDT-M MMR used for the distance calc
     */
    public record SchemeCPolicy(
            Duration entryWindow,
            int maxSettlementsHeld,
            BigDecimal minLiquidationDistance,
            BigDecimal maintenanceMarginRate
    ) {
        public SchemeCPolicy {
            Objects.requireNonNull(entryWindow, "entryWindow");
            Objects.requireNonNull(minLiquidationDistance, "minLiquidationDistance");
            Objects.requireNonNull(maintenanceMarginRate, "maintenanceMarginRate");
            if (entryWindow.isNegative()) {
                throw new IllegalArgumentException("entryWindow must be >= 0");
            }
            if (maxSettlementsHeld < 0) {
                throw new IllegalArgumentException("maxSettlementsHeld must be >= 0");
            }
            if (minLiquidationDistance.signum() < 0) {
                throw new IllegalArgumentException("minLiquidationDistance must be >= 0");
            }
            if (maintenanceMarginRate.signum() <= 0 || maintenanceMarginRate.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("maintenanceMarginRate must be in (0, 1)");
            }
        }

        public static SchemeCPolicy disabled() {
            return new SchemeCPolicy(Duration.ZERO, 0, BigDecimal.ZERO, new BigDecimal("0.004"));
        }

        public boolean nearSettlementEntry() {
            return !entryWindow.isZero();
        }

        public boolean holdSettlementCap() {
            return maxSettlementsHeld > 0;
        }

        public boolean liquidationStopEnabled() {
            return minLiquidationDistance.signum() > 0;
        }
    }

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
        schemeC = schemeC == null ? SchemeCPolicy.disabled() : schemeC;
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
                fees,
                SchemeCPolicy.disabled()
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
                fees,
                schemeCFromEnvironment(environment, defaults.schemeC)
        );
    }

    /**
     * Locked Scheme C size: 10,000 USDT notional. Fees stay percent-of-notional.
     */
    public static SpotPerpRules schemeCTenThousand(int leverage, int minFundingPeriods,
            BigDecimal closeThreshold, BigDecimal maxBasisRate, Duration settlementProtection,
            Duration minHolding, SchemeCPolicy schemeC) {
        SpotPerpRules base = defaults();
        if (leverage < 1 || leverage > 5) {
            throw new IllegalArgumentException("leverage must be in 1..5 for this dry-run");
        }
        return new SpotPerpRules(
                true,
                true,
                new BigDecimal("10000"),
                leverage,
                base.openThreshold(),
                closeThreshold,
                base.minBasisRate(),
                maxBasisRate,
                base.maxBasisDeteriorationRate(),
                base.minNetYieldRate(),
                minFundingPeriods,
                settlementProtection,
                minHolding,
                base.maxHolding(),
                base.maxQuoteAge(),
                new BigDecimal("20000"),
                base.coverageRatio(),
                base.maxVwapImpact(),
                base.maxLotOvershoot(),
                base.roundTripFeeRates(),
                schemeC
        );
    }

    /** Spot notional plus isolated perp margin at the configured leverage. */
    public BigDecimal capitalUsdt() {
        return notionalUsdt.add(notionalUsdt.divide(BigDecimal.valueOf(leverage), 8,
                java.math.RoundingMode.HALF_UP));
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

    private static SchemeCPolicy schemeCFromEnvironment(Environment environment, SchemeCPolicy fallback) {
        return new SchemeCPolicy(
                Duration.ofMinutes(longValue(environment,
                        "arbitrage.spot-perp-funding.entry-window-minutes",
                        fallback.entryWindow().toMinutes())),
                integer(environment, "arbitrage.spot-perp-funding.max-settlements-held",
                        fallback.maxSettlementsHeld()),
                decimal(environment, "arbitrage.spot-perp-funding.min-liquidation-distance",
                        fallback.minLiquidationDistance()),
                decimal(environment, "arbitrage.spot-perp-funding.maintenance-margin-rate",
                        fallback.maintenanceMarginRate())
        );
    }
}
