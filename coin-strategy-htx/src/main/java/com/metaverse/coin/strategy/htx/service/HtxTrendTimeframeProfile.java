package com.metaverse.coin.strategy.htx.service;

import java.util.List;
import java.util.Locale;

/**
 * Immutable execution profile kept on every managed trend position.  A position keeps this profile
 * after configuration changes so its exit rules remain consistent with the entry hypothesis.
 */
public enum HtxTrendTimeframeProfile {
    INTRADAY_15M("15min", "60min", 1.0, 1.0, 1.0, List.of("30min", "60min", "4hour")),
    INTRADAY_30M("30min", "4hour", 1.0, 1.0, 1.0, List.of("60min", "4hour", "1day")),
    SWING_4H("4hour", "1day", 1.35, 1.45, 1.50, List.of("4hour", "1day")),
    TREND_1D("1day", "1day", 1.65, 1.75, 2.00, List.of("1day"));

    private final String entryPeriod;
    private final String environmentPeriod;
    private final double stopMultiplier;
    private final double trailingMultiplier;
    private final double targetMultiplier;
    private final List<String> exitConfirmationPeriods;

    HtxTrendTimeframeProfile(String entryPeriod, String environmentPeriod, double stopMultiplier,
                             double trailingMultiplier, double targetMultiplier,
                             List<String> exitConfirmationPeriods) {
        this.entryPeriod = entryPeriod;
        this.environmentPeriod = environmentPeriod;
        this.stopMultiplier = stopMultiplier;
        this.trailingMultiplier = trailingMultiplier;
        this.targetMultiplier = targetMultiplier;
        this.exitConfirmationPeriods = exitConfirmationPeriods;
    }

    public String entryPeriod() { return entryPeriod; }
    public String environmentPeriod() { return environmentPeriod; }
    public double stopMultiplier() { return stopMultiplier; }
    public double trailingMultiplier() { return trailingMultiplier; }
    public double targetMultiplier() { return targetMultiplier; }
    public List<String> exitConfirmationPeriods() { return exitConfirmationPeriods; }

    public static HtxTrendTimeframeProfile fromPeriod(String period) {
        String normalized = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "30min" -> INTRADAY_30M;
            case "4hour" -> SWING_4H;
            case "1day" -> TREND_1D;
            default -> INTRADAY_15M;
        };
    }

    public static HtxTrendTimeframeProfile fromPersisted(String profile, String fallbackPeriod) {
        if (profile != null && !profile.isBlank()) {
            try {
                return valueOf(profile.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Historical positions are intentionally compatible with the configured fallback profile.
            }
        }
        return fromPeriod(fallbackPeriod);
    }
}
