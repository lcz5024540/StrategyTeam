package com.metaverse.coin.strategy.htx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Configuration for the default-off multi-asset, multi-period trend ensemble observer. */
@Data
@ConfigurationProperties(prefix = "htx.trend.ensemble-shadow")
public class HtxTrendEnsembleShadowProperties {

    /** Master switch. The engine records candidate signals only and never places orders. */
    private boolean enabled = false;

    /** USDT-margined perpetual contracts watched by the ensemble engine. */
    private List<String> contractCodes = new ArrayList<>(List.of("BTC-USDT", "ETH-USDT", "XAUT-USDT"));

    /** Entry timeframes watched independently. HTX accepts values such as 15min, 30min, 60min, 4hour. */
    private List<String> periods = new ArrayList<>(List.of("15min", "30min", "60min", "4hour"));

    /** Number of bars fetched for each contract-period evaluation. */
    private int klineSize = 180;

    /** Ignore the still-forming crypto candle until it has this close grace. */
    private long candleCloseGraceSeconds = 10L;

    /** EMA periods used by the trend structure model. */
    private int fastEmaPeriod = 20;
    private int mediumEmaPeriod = 60;
    private int slowEmaPeriod = 120;

    /** Donchian breakout lookback, excluding the latest completed bar. */
    private int breakoutLookback = 20;

    /** Momentum lookback measured in bars. */
    private int momentumLookback = 12;

    /** ATR and ADX periods used for volatility and trend-strength filters. */
    private int atrPeriod = 14;
    private int adxPeriod = 14;

    /** Volume confirmation lookback measured in bars. */
    private int volumeLookback = 20;

    /** Minimum ATR / close ratio before a trend candidate is trusted. */
    private BigDecimal minAtrPct = new BigDecimal("0.0010");

    /** Minimum ADX before the trend-strength component contributes. */
    private BigDecimal minAdx = new BigDecimal("16");

    /** Minimum absolute momentum over {@link #momentumLookback} bars. */
    private BigDecimal minMomentumPct = new BigDecimal("0.0015");

    /** Volume ratio required for the volume-confirmation component. */
    private BigDecimal minVolumeRatio = new BigDecimal("1.10");

    /** Minimum dominant side score for a tradable candidate. */
    private BigDecimal entryScoreThreshold = new BigDecimal("60");

    /** Minimum score gap between long and short sides to avoid ambiguous entries. */
    private BigDecimal minScoreGap = new BigDecimal("12");

    /** Scheduler interval. */
    private long pollIntervalMs = 60_000L;

    /** Scheduler initial delay. */
    private long initialDelayMs = 45_000L;

    /** Version tag persisted with each candidate row. */
    private String modelVersion = "ensemble-shadow-v1";
}
