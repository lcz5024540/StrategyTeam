package com.metaverse.coin.strategy.htx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Configuration for HTX contract trend following. */
@Data
@ConfigurationProperties(prefix = "htx.trend")
public class HtxTrendProperties {

    /** Master switch for scheduler loading. */
    private boolean enabled = false;

    /** Observe-only mode records/logs signals but never sends orders. */
    private boolean observeOnly = true;

    /** Whether the strategy may open new positions. Active positions are still managed when disabled. */
    private boolean entryEnabled = true;

    /** When enabled, only a database-backed combined candidate may submit a new live entry. */
    private boolean combinationEntryEnabled = false;

    /** Legacy single USDT-margined perpetual contract, used when {@link #contractCodes} is empty. */
    private String contractCode = "XAUT-USDT";

    /** Live whitelist of USDT-margined perpetual contracts evaluated by the trend runner. */
    private List<String> contractCodes = new ArrayList<>();

    /** Maximum open-like trend positions across the live whitelist. */
    private int maxActivePositions = 1;

    /** Lower timeframe used for entry and exit triggers, for example 15min or 60min. */
    private String period = "15min";

    /** Higher timeframe used only for trend direction. Empty means reuse {@link #period}. */
    private String trendPeriod = "60min";

    /** Number of bars fetched for each entry-timeframe evaluation. */
    private int klineSize = 160;

    /** Number of bars fetched for each trend-timeframe evaluation. */
    private int trendKlineSize = 160;

    /** Ignore the still-forming current crypto candle until it has this close grace. */
    private long candleCloseGraceSeconds = 10L;

    /** Fast EMA period used for entry timeframe momentum. */
    private int fastEmaPeriod = 12;

    /** Slow EMA period used for entry timeframe momentum. */
    private int slowEmaPeriod = 48;

    /** Fast EMA period on the higher trend timeframe. */
    private int trendFastEmaPeriod = 8;

    /** Slow EMA period on the higher trend timeframe. */
    private int trendSlowEmaPeriod = 21;

    /** Donchian breakout lookback, excluding the current completed bar. */
    private int breakoutLookback = 20;

    /** ATR period for stop distance and low-volatility filter. */
    private int atrPeriod = 14;

    /** ADX period for trend-strength filter. */
    private int adxPeriod = 14;

    /** Minimum ADX required before entering a trend. */
    private BigDecimal minAdx = new BigDecimal("18");

    /** Minimum ATR / close ratio; filters dead, fee-dominated markets. */
    private BigDecimal minAtrPct = new BigDecimal("0.0010");

    /** Enable Fibonacci retracement continuation entries after the higher-timeframe trend is already established. */
    private boolean fibonacciPullbackEnabled = true;

    /** Lower Fibonacci retracement bound used for pullback continuation entries. */
    private BigDecimal fibonacciLowerRate = new BigDecimal("0.382");

    /** Upper Fibonacci retracement bound used for pullback continuation entries. */
    private BigDecimal fibonacciUpperRate = new BigDecimal("0.618");

    /** Recent swing range must be at least this many ATRs before Fibonacci levels are trusted. */
    private BigDecimal fibonacciMinRangeAtrMultiplier = new BigDecimal("1.2");

    /** Maximum allowed top-of-book spread before live entry. */
    private BigDecimal maxEntrySpreadRate = new BigDecimal("0.0010");

    /** Recent completed bars used to judge whether a contract has enough quote turnover. */
    private int liquidityLookbackBars = 20;

    /** Minimum average quote turnover per entry-timeframe bar; <=0 disables the turnover gate. */
    private BigDecimal minEntryAvgTurnoverUsdt = BigDecimal.ZERO;

    /** Order book levels used to judge whether the entry side has enough executable liquidity. */
    private int entryDepthLevels = 5;

    /** Required top-book contracts relative to the planned order size; <=0 disables the depth gate. */
    private BigDecimal minEntryDepthMultiplier = new BigDecimal("3");

    /** Maximum ATR / close ratio before entry; <=0 disables the extreme-volatility gate. */
    private BigDecimal maxEntryAtrPct = new BigDecimal("0.0500");

    /** Base target notional before risk sizing clamps it. */
    private BigDecimal targetQuoteNotional = new BigDecimal("190");

    /** Absolute maximum live notional for this strategy after sizing multipliers. */
    private BigDecimal maxQuoteNotional = new BigDecimal("190");

    /** Minimum live notional; smaller calculated sizes are skipped. */
    private BigDecimal minQuoteNotional = new BigDecimal("20");

    /** Max USDT loss budget per trade, used with ATR stop distance. */
    private BigDecimal riskPerTradeUsdt = new BigDecimal("1.5");

    /** Initial stop distance = ATR * this multiplier. */
    private BigDecimal atrStopMultiplier = new BigDecimal("2.5");

    /** Trailing stop distance = ATR * this multiplier. */
    private BigDecimal trailingAtrMultiplier = new BigDecimal("2.0");

    /**
     * Enable structure-based trailing stop selection. Default off until backtest and live samples prove it improves exits.
     */
    private boolean structureStopEnabled = false;

    /** Optional take-profit distance = ATR * this multiplier; <=0 disables. */
    private BigDecimal takeProfitAtrMultiplier = new BigDecimal("0");

    /** Conservative taker fee fallback per side when the account-specific HTX fee query is unavailable. */
    private BigDecimal estimatedTakerFeeRate = new BigDecimal("0.0004");

    /** Cache lifetime for account-specific HTX swap fee rates. */
    private long feeRateCacheMs = 600_000L;

    /** Minimum net profit buffer required after estimated round-trip fees. */
    private BigDecimal minNetProfitRate = new BigDecimal("0.0010");

    /** Require a composite direction-confidence score before live entry. */
    private boolean directionConfidenceGateEnabled = true;

    /** Minimum composite score for ordinary trend entries. */
    private BigDecimal minDirectionConfidenceScore = new BigDecimal("70");

    /** Minimum composite score when broad-market direction is mixed. */
    private BigDecimal minMixedDirectionConfidenceScore = new BigDecimal("82");

    /** Minimum composite score when entering against broad-market direction. */
    private BigDecimal minCounterBreadthDirectionConfidenceScore = new BigDecimal("95");

    /** Minimum composite score for Fibonacci pullback entries. */
    private BigDecimal fibonacciMinDirectionConfidenceScore = new BigDecimal("80");

    /** Minimum composite score for funding-carry entries promoted from a flat base signal. */
    private BigDecimal fundingCarryMinDirectionConfidenceScore = new BigDecimal("90");

    /** Allow the stricter ensemble model to promote a flat strict signal into the normal live entry gate chain. */
    private boolean ensembleEntryEnabled = true;

    /** Minimum ensemble score before a shadow observation may become a live entry candidate. */
    private BigDecimal ensembleEntryMinScore = new BigDecimal("85");

    /** Minimum long/short score gap before a shadow observation may become a live entry candidate. */
    private BigDecimal ensembleEntryMinScoreGap = new BigDecimal("20");

    /** Minimum ADX for ensemble-promoted live candidates. */
    private BigDecimal ensembleEntryMinAdx = new BigDecimal("24");

    /** Minimum ATR / close ratio for ensemble-promoted live candidates. */
    private BigDecimal ensembleEntryMinAtrPct = new BigDecimal("0.0012");

    /** Require the ensemble regime to be TRENDING_UP for long or TRENDING_DOWN for short. */
    private boolean ensembleEntryRequireTrendingRegime = true;

    /** Optionally require broad market to be aligned; default false because mixed markets still pass direction-confidence gate. */
    private boolean ensembleEntryRequireBreadthAlignment = false;

    /** Prevent ensemble-promoted entries from fighting a clear broad-market trend. */
    private boolean ensembleEntryAllowAgainstBroadMarket = false;

    /** Minimum absolute net profit in USDT before a flat signal can close a protected winner. */
    private BigDecimal minFlatExitProfitUsdt = new BigDecimal("0.20");

    /** Number of consecutive flat signals required before fee-protected flat exit. */
    private int flatExitConfirmationBars = 3;

    /** Maximum estimated net loss in USDT tolerated after a confirmed flat signal. */
    private BigDecimal maxFlatExitLossUsdt = new BigDecimal("0.25");

    /** Maximum estimated net loss rate tolerated after a confirmed flat signal. */
    private BigDecimal maxFlatExitLossRate = new BigDecimal("0.0030");

    /** Number of consecutive flat signals required before loss-control flat exit. */
    private int flatLossExitConfirmationBars = 3;

    /** Expected move proxy = ATR * this multiplier; entry is skipped when it cannot cover costs. */
    private BigDecimal minExpectedMoveAtrMultiplier = BigDecimal.ONE;

    /** Maximum worse fill versus the signal close, expressed as ATR multiple; <=0 disables this chase guard. */
    private BigDecimal maxEntrySignalChaseAtrMultiplier = new BigDecimal("0.20");

    /** Maximum worse fill versus the signal close, expressed as price rate; <=0 disables this chase guard. */
    private BigDecimal maxEntrySignalChaseRate = BigDecimal.ZERO;

    /** Maximum seconds after the signal bar closed during which a live entry is still allowed; <=0 disables. */
    private long maxEntrySignalAgeSeconds = 600L;

    /** Bounded parallelism for market/signal candidate scans. Live order submission remains sequential. */
    private int candidateScanConcurrency = 6;

    /** Enable quality and market-breadth based entry sizing. This never hard-pauses entries by itself. */
    private boolean adaptiveSizingEnabled = true;

    /** Closed trend positions inspected for recent quality sizing. */
    private int qualityLookbackTrades = 20;

    /** Time window inspected for recent quality sizing. */
    private int qualityLookbackHours = 24;

    /** Minimum closed positions before quality sizing changes the base size. */
    private int qualityMinClosedTrades = 6;

    /** Profit factor required to avoid weak-quality downsizing. */
    private BigDecimal qualityMinProfitFactor = new BigDecimal("1.10");

    /** Average win / average loss required to avoid weak-quality downsizing when both sides have samples. */
    private BigDecimal qualityMinPayoffRatio = new BigDecimal("1.00");

    /** Minimum average net profit per closed trade required to avoid weak-quality downsizing. */
    private BigDecimal qualityMinExpectancyUsdt = BigDecimal.ZERO;

    /** Profit factor required for strong-quality upsizing. */
    private BigDecimal qualityStrongProfitFactor = new BigDecimal("1.30");

    /** Average win / average loss required for strong-quality upsizing when both sides have samples. */
    private BigDecimal qualityStrongPayoffRatio = new BigDecimal("1.20");

    /** Position multiplier when wins do not exceed losses, payoff is poor, profit factor is poor, or recent expectancy is weak. */
    private BigDecimal weakQualityPositionMultiplier = new BigDecimal("0.50");

    /** Upper cap for the final adaptive multiplier while recent execution quality is weak. */
    private BigDecimal weakQualityMaxPositionMultiplier = new BigDecimal("0.60");

    /** Position multiplier when recent win count, payoff ratio and profit factor are all healthy. */
    private BigDecimal strongQualityPositionMultiplier = new BigDecimal("1.15");

    /** Recent signal window used to detect market-wide trend breadth. */
    private int marketBreadthLookbackMinutes = 35;

    /** Minimum symbols required before market breadth can affect sizing. */
    private int marketBreadthMinSymbols = 20;

    /** Bull/bear breadth threshold, e.g. 0.70 means 70% of symbols share the direction. */
    private BigDecimal marketBreadthThreshold = new BigDecimal("0.70");

    /** Opposite breadth must stay below this value before a broad trend is trusted. */
    private BigDecimal marketBreadthOppositeMax = new BigDecimal("0.15");

    /** Position multiplier for signals aligned with a broad market trend. */
    private BigDecimal marketBreadthAlignedPositionMultiplier = new BigDecimal("1.50");

    /** Position multiplier for signals against a broad market trend. */
    private BigDecimal marketBreadthCounterPositionMultiplier = new BigDecimal("0.50");

    /** Enable BTC/ETH multi-timeframe market-anchor context for candidate scoring and direction confidence. */
    private boolean marketAnchorEnabled = true;

    /** Major contracts used as market anchors. */
    private List<String> marketAnchorContracts = new ArrayList<>(List.of("BTC-USDT", "ETH-USDT"));

    /** Timeframes checked for each market anchor contract. */
    private List<String> marketAnchorPeriods = new ArrayList<>(List.of("15min", "30min", "60min", "4hour"));

    /** Number of bars fetched for each market-anchor period. */
    private int marketAnchorKlineSize = 80;

    /** Fast EMA period for market-anchor trend detection. */
    private int marketAnchorFastEmaPeriod = 8;

    /** Slow EMA period for market-anchor trend detection. */
    private int marketAnchorSlowEmaPeriod = 21;

    /** Minimum aligned periods per BTC/ETH symbol before the symbol is counted as directional. */
    private int marketAnchorMinAlignedPeriodsPerSymbol = 2;

    /** Minimum aligned period signals across BTC/ETH before the anchor is treated as broad up/down. */
    private int marketAnchorMinAlignedSignals = 4;

    /** Minimum aligned anchor symbols, default two means BTC and ETH must both participate. */
    private int marketAnchorMinAlignedSymbols = 2;

    /** Maximum opposite period signals tolerated before the anchor becomes mixed. */
    private int marketAnchorMaxOppositeSignals = 1;

    /** Candidate score bonus when entry direction aligns with BTC/ETH multi-timeframe anchor. */
    private BigDecimal marketAnchorScoreBonus = new BigDecimal("20");

    /** Candidate score penalty when entry direction fights BTC/ETH multi-timeframe anchor. */
    private BigDecimal marketAnchorCounterScorePenalty = new BigDecimal("20");

    /** Direction-confidence bonus when entry direction aligns with BTC/ETH multi-timeframe anchor. */
    private BigDecimal marketAnchorConfidenceBonus = new BigDecimal("20");

    /** Direction-confidence penalty when entry direction fights BTC/ETH multi-timeframe anchor. */
    private BigDecimal marketAnchorCounterConfidencePenalty = new BigDecimal("20");

    /** Lower clamp for the combined adaptive sizing multiplier. */
    private BigDecimal minAdaptivePositionMultiplier = new BigDecimal("0.25");

    /** Upper clamp for the combined adaptive sizing multiplier. */
    private BigDecimal maxAdaptivePositionMultiplier = new BigDecimal("1.80");

    /** Allow high-confidence entries to receive an extra sizing multiplier. */
    private boolean confidenceSizingEnabled = true;

    /** Minimum direction-confidence score that may receive the high-confidence multiplier. */
    private BigDecimal highConfidenceSizingScore = new BigDecimal("105");

    /** Extra position multiplier for high-confidence entries. */
    private BigDecimal highConfidencePositionMultiplier = new BigDecimal("1.20");

    /** Minimum score for high-confidence entries aligned with broad market and strong recent quality. */
    private BigDecimal strongConfluenceSizingScore = new BigDecimal("120");

    /** Extra position multiplier for strong confluence entries. */
    private BigDecimal strongConfluencePositionMultiplier = new BigDecimal("1.35");

    /** Once a live position reaches fee breakeven, trailing stop is clamped to at least breakeven. */
    private boolean feeProtectionEnabled = true;

    /** After price reaches the fee-protected zone, a flat signal exits instead of waiting for a wide stop. */
    private boolean exitFlatAfterFeeProtection = true;

    /** Maximum funding rate cost tolerated for the entry side per funding period. */
    private BigDecimal maxEntryFundingCostRate = new BigDecimal("0.0006");

    /** Allow funding-carry entries when the strict breakout signal is flat and no trend position is active. */
    private boolean fundingCarryEntryEnabled = true;

    /** Minimum absolute funding rate before carry can promote a flat signal into a live entry. */
    private BigDecimal minCarryFundingRateAbs = new BigDecimal("0.0002");

    /**
     * Minimum ADX required for funding-carry entries; stricter than normal entries because carry starts from a flat base signal.
     */
    private BigDecimal fundingCarryMinAdx = new BigDecimal("24");

    /** Minimum ATR / close ratio required for funding-carry entries. */
    private BigDecimal fundingCarryMinAtrPct = new BigDecimal("0.0010");

    /** Funding-carry entries need either local EMA alignment or broad-market alignment. */
    private boolean fundingCarryRequireConfirmation = true;

    /** Prevent funding-carry entries that fight a broad market regime, for example shorting into broad-up. */
    private boolean fundingCarryAllowAgainstBroadMarket = false;

    /** Exit a live trend position when its side funding cost is above this rate. */
    private BigDecimal maxHoldFundingCostRate = new BigDecimal("0.0012");

    /** Contract leverage used by this strategy. */
    private int leverage = 5;

    /** Price scale for persisted stop prices. */
    private int priceScale = 2;

    /** Minimum free isolated/cross USDT margin required after sizing check. */
    private BigDecimal minContractMarginAvailable = new BigDecimal("20");

    /** Scheduler interval. */
    private long pollIntervalMs = 60_000L;

    /** Scheduler initial delay. */
    private long initialDelayMs = 30_000L;

    /** Only allow new entries in a short window after each completed candle closes. */
    private boolean alignEntryToCandleClose = true;

    /** Seconds after candle close before a new-entry scan may start. */
    private long entryWindowStartSecondsAfterClose = 15L;

    /** Seconds after the entry window starts during which new entries may be evaluated. */
    private long entryWindowDurationSeconds = 45L;

    /** Master switch for same-contract re-entry cooldown after a completed trend cycle; <=0 disables all cooldowns. */
    private long cooldownMs = 3_600_000L;

    /** Same-contract cooldown after a losing completed trend position. */
    private long lossCooldownMs = 3_600_000L;

    /** Same-contract cooldown after a near-breakeven completed trend position. */
    private long breakevenCooldownMs = 1_800_000L;

    /** Same-contract cooldown after a clearly profitable completed trend position. */
    private long profitCooldownMs = 900_000L;

    /** Net USDT profit threshold that classifies a completed position as clearly profitable. */
    private BigDecimal profitCooldownMinNetProfitUsdt = new BigDecimal("1.00");

    /** After this profit cooldown age, a fresh strong breakout may re-enter before profitCooldownMs expires. */
    private long profitCooldownStrongReentryAfterMs = 600_000L;

    /** Minimum ADX required before bypassing profit cooldown for a strong re-entry. */
    private BigDecimal profitCooldownStrongReentryMinAdx = new BigDecimal("30");

    /** Minimum ATR / close ratio required before bypassing profit cooldown for a strong re-entry. */
    private BigDecimal profitCooldownStrongReentryMinAtrPct = new BigDecimal("0.0020");

    /** Prefix for DB keys. Contract client ids remain numeric due to HTX V5 validation. */
    private String clientOrderIdPrefix = "CSTF";

    /** Optional secondary account that mirrors live trend entries and exits after the primary account. */
    private Mirror mirror = new Mirror();

    @Data
    public static class Mirror {
        /** Default off; enabling requires a dedicated secondary HTX contract API key. */
        private boolean enabled = false;
        /** Human-readable account label used only in logs. Do not put secrets here. */
        private String accountLabel = "TradeX";
        private String accessKey;
        private String secretKey;
        private String baseUrl = "https://api.hbdm.com";
        /** Keep follower leverage aligned before opening mirrored positions. */
        private boolean syncLeverage = true;
        /** Whether a failed leverage sync must block the mirrored entry order. */
        private boolean blockEntryOnLeverageSyncFailure = false;
        /** Keep the primary position visible as close-pending until the follower account is flat. */
        private boolean blockPrimaryCloseUntilSynced = true;
    }
}
