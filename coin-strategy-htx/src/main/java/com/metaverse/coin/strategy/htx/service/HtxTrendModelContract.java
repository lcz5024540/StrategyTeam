package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;

import java.util.Locale;
import java.util.Set;

/**
 * The immutable responsibility boundary for each independent trend model.
 *
 * <p>It intentionally contains no score thresholds or risk amounts. Those values must come from
 * frozen-data evaluation and a versioned policy; this contract only prevents one model from being
 * silently treated as another model's entry, target, or exit hypothesis.</p>
 */
public enum HtxTrendModelContract {
    DONCHIAN_BREAKOUT(EntryRole.EXECUTABLE_CANDIDATE, "TREND_START", "INTRADAY_15M",
            Set.of(Regime.TRENDING_UP, Regime.TRENDING_DOWN), EntryStyle.CONDITIONAL_OR_MARKET,
            InvalidationBasis.DONCHIAN_OPPOSITE_BOUNDARY, TargetBasis.CHANNEL_MEASURED_MOVE,
            ExitAuthority.NOT_YET_AUTHORIZED),
    FIB_PULLBACK_CONTINUATION(EntryRole.EXECUTABLE_CANDIDATE, "TREND_CONTINUATION", "INTRADAY_15M",
            Set.of(Regime.TRENDING_UP, Regime.TRENDING_DOWN), EntryStyle.PASSIVE_LIMIT_PREFERRED,
            InvalidationBasis.FIBONACCI_RETRACEMENT_FAILURE, TargetBasis.FIBONACCI_EXTENSION,
            ExitAuthority.FIB_V4),
    EMA_VWAP_MOMENTUM_CONTINUATION(EntryRole.EXECUTABLE_CANDIDATE, "INTRADAY_ACCELERATION", "INTRADAY_15M",
            Set.of(Regime.TRENDING_UP, Regime.TRENDING_DOWN), EntryStyle.IOC_OR_MARKET,
            InvalidationBasis.EMA_VWAP_FAILURE, TargetBasis.STRUCTURE_SWING,
            ExitAuthority.NOT_YET_AUTHORIZED),
    EMA_VWAP_PULLBACK(EntryRole.SUPPORTING_EVIDENCE, "INTRADAY_PULLBACK", "INTRADAY_15M",
            Set.of(Regime.TRENDING_UP, Regime.TRENDING_DOWN), EntryStyle.NOT_APPLICABLE,
            InvalidationBasis.NOT_APPLICABLE, TargetBasis.NOT_APPLICABLE, ExitAuthority.NOT_APPLICABLE),
    MOMENTUM_CONTINUATION(EntryRole.SUPPORTING_EVIDENCE, "MOMENTUM_CONFIRMATION", "INTRADAY_15M",
            Set.of(Regime.TRENDING_UP, Regime.TRENDING_DOWN), EntryStyle.NOT_APPLICABLE,
            InvalidationBasis.NOT_APPLICABLE, TargetBasis.NOT_APPLICABLE, ExitAuthority.NOT_APPLICABLE),
    UNKNOWN(EntryRole.UNKNOWN, "UNKNOWN", "UNSPECIFIED", Set.of(), EntryStyle.NOT_APPLICABLE,
            InvalidationBasis.NOT_APPLICABLE, TargetBasis.NOT_APPLICABLE, ExitAuthority.NOT_APPLICABLE);

    private final EntryRole entryRole;
    private final String marketRole;
    private final String timeframeProfile;
    private final Set<Regime> applicableRegimes;
    private final EntryStyle preferredEntryStyle;
    private final InvalidationBasis invalidationBasis;
    private final TargetBasis targetBasis;
    private final ExitAuthority exitAuthority;

    HtxTrendModelContract(EntryRole entryRole, String marketRole, String timeframeProfile,
                          Set<Regime> applicableRegimes, EntryStyle preferredEntryStyle,
                          InvalidationBasis invalidationBasis, TargetBasis targetBasis,
                          ExitAuthority exitAuthority) {
        this.entryRole = entryRole;
        this.marketRole = marketRole;
        this.timeframeProfile = timeframeProfile;
        this.applicableRegimes = Set.copyOf(applicableRegimes);
        this.preferredEntryStyle = preferredEntryStyle;
        this.invalidationBasis = invalidationBasis;
        this.targetBasis = targetBasis;
        this.exitAuthority = exitAuthority;
    }

    public static HtxTrendModelContract fromCode(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(modelCode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    public boolean appliesTo(Regime regime, String period) {
        return appliesTo(regime, period, true);
    }

    /**
     * The regime requirement is normally mandatory.  The explicit false path exists only for
     * controlled ablation/gray-release research and still preserves model role and timeframe.
     */
    public boolean appliesTo(Regime regime, String period, boolean requireTrendingRegime) {
        return entryRole == EntryRole.EXECUTABLE_CANDIDATE
                && (!requireTrendingRegime || applicableRegimes.contains(regime))
                && timeframeProfile.equals(HtxTrendTimeframeProfile.fromPeriod(period).name());
    }

    public EntryRole entryRole() { return entryRole; }
    public String marketRole() { return marketRole; }
    public String timeframeProfile() { return timeframeProfile; }
    public Set<Regime> applicableRegimes() { return applicableRegimes; }
    public EntryStyle preferredEntryStyle() { return preferredEntryStyle; }
    public InvalidationBasis invalidationBasis() { return invalidationBasis; }
    public TargetBasis targetBasis() { return targetBasis; }
    public ExitAuthority exitAuthority() { return exitAuthority; }

    public enum EntryRole { EXECUTABLE_CANDIDATE, SUPPORTING_EVIDENCE, UNKNOWN }
    public enum EntryStyle { CONDITIONAL_OR_MARKET, PASSIVE_LIMIT_PREFERRED, IOC_OR_MARKET, NOT_APPLICABLE }
    public enum InvalidationBasis { DONCHIAN_OPPOSITE_BOUNDARY, FIBONACCI_RETRACEMENT_FAILURE, EMA_VWAP_FAILURE, NOT_APPLICABLE }
    public enum TargetBasis { CHANNEL_MEASURED_MOVE, FIBONACCI_EXTENSION, STRUCTURE_SWING, NOT_APPLICABLE }
    public enum ExitAuthority { FIB_V4, NOT_YET_AUTHORIZED, NOT_APPLICABLE }
}
