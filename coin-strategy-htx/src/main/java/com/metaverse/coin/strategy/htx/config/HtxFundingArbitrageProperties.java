package com.metaverse.coin.strategy.htx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Configuration for spot-long plus perpetual-short funding-fee arbitrage on HTX. */
@Data
@ConfigurationProperties(prefix = "htx.funding-arbitrage")
public class HtxFundingArbitrageProperties {

    /** Master switch. Default off so the strategy cannot start without an explicit deployment env. */
    private boolean enabled = false;

    /** Spot symbol, for example xautusdt. */
    private String symbol = "xautusdt";

    /** USDT-margined perpetual contract, for example XAUT-USDT. */
    private String contractCode = "XAUT-USDT";

    /** Desired matched notional per cycle in quote currency. */
    private BigDecimal targetQuoteNotional = new BigDecimal("500");

    /** Enter only when funding is at least this positive rate. */
    private BigDecimal minFundingRate = new BigDecimal("0.00005");

    /** Exit an active cycle when funding falls to or below this rate. */
    private BigDecimal exitFundingRate = BigDecimal.ZERO;

    /** Maximum absolute spot/swap mid-price basis allowed for entry. */
    private BigDecimal maxEntryBasisRate = new BigDecimal("0.0015");

    /** Maximum absolute spot/swap mid-price basis tolerated while holding. */
    private BigDecimal maxHoldBasisRate = new BigDecimal("0.0030");

    /** Minimum distance between mark/last price and short liquidation price while holding. */
    private BigDecimal minLiquidationDistanceRate = new BigDecimal("0.0500");

    /** Extra room above best ask for spot entry limit orders. */
    private BigDecimal spotEntrySlippageRate = new BigDecimal("0.0005");

    /** Extra room below best bid for spot exit limit orders. */
    private BigDecimal spotExitSlippageRate = new BigDecimal("0.0010");

    /** Price scale used for XAUT/USDT spot and swap limits. */
    private int priceScale = 2;

    /** Spot base quantity scale used by HTX for XAUT. */
    private int baseScale = 6;

    /** How long a resting spot entry order may remain open before cancellation. */
    private long spotEntryTtlMs = 60_000L;

    /** How long a resting spot exit order may remain open before cancellation/manual state. */
    private long spotExitTtlMs = 60_000L;

    /** Scheduler interval. */
    private long pollIntervalMs = 30_000L;

    /** Scheduler initial delay. */
    private long initialDelayMs = 20_000L;

    /** Contract leverage used by this strategy. Keep low because the spot leg hedges price risk. */
    private int leverage = 5;

    /** Minimum contract volume. */
    private long minContractVolume = 1L;

    /** Require this much free spot quote balance before entering. */
    private BigDecimal minSpotQuoteAvailable = new BigDecimal("20");

    /** Require this much free contract margin before entering, after notional/leverage check. */
    private BigDecimal minContractMarginAvailable = new BigDecimal("20");

    /** Keep this reserve in the spot quote wallet after the spot buy. */
    private BigDecimal minSpotQuoteReserve = BigDecimal.ZERO;

    /** Prefix for spot client ids. Contract client ids remain numeric due to HTX V5 validation. */
    private String clientOrderIdPrefix = "CSFA";
}
