package com.metaverse.coin.strategy.htx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Configuration for HTX XAUT-USDT contract trend following. */
@Data
@ConfigurationProperties(prefix = "htx.trend")
public class HtxTrendProperties {

    /** Master switch for scheduler loading. */
    private boolean enabled = false;

    /** Observe-only mode records/logs signals but never sends orders. */
    private boolean observeOnly = true;

    /** USDT-margined perpetual contract, for example XAUT-USDT. */
    private String contractCode = "XAUT-USDT";

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

    /** Maximum allowed top-of-book spread before live entry. */
    private BigDecimal maxEntrySpreadRate = new BigDecimal("0.0010");

    /** Base target notional before risk sizing clamps it. */
    private BigDecimal targetQuoteNotional = new BigDecimal("190");

    /** Maximum live notional for this strategy. */
    private BigDecimal maxQuoteNotional = new BigDecimal("190");

    /** Minimum live notional; smaller calculated sizes are skipped. */
    private BigDecimal minQuoteNotional = new BigDecimal("20");

    /** Max USDT loss budget per trade, used with ATR stop distance. */
    private BigDecimal riskPerTradeUsdt = new BigDecimal("1.5");

    /** Initial stop distance = ATR * this multiplier. */
    private BigDecimal atrStopMultiplier = new BigDecimal("2.5");

    /** Trailing stop distance = ATR * this multiplier. */
    private BigDecimal trailingAtrMultiplier = new BigDecimal("2.0");

    /** Optional take-profit distance = ATR * this multiplier; <=0 disables. */
    private BigDecimal takeProfitAtrMultiplier = new BigDecimal("0");

    /** Maximum funding rate cost tolerated for the entry side per funding period. */
    private BigDecimal maxEntryFundingCostRate = new BigDecimal("0.0006");

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

    /** Avoid immediate re-entry after a completed trend cycle. */
    private long cooldownMs = 3_600_000L;

    /** Prefix for DB keys. Contract client ids remain numeric due to HTX V5 validation. */
    private String clientOrderIdPrefix = "CSTF";
}
