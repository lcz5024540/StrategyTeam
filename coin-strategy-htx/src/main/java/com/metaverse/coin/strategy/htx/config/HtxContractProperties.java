package com.metaverse.coin.strategy.htx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/** Configuration for the isolated USDT-margined perpetual contract mode. */
@Data
@ConfigurationProperties(prefix = "htx.contract")
public class HtxContractProperties {

    /** Loads the contract API and controller. Default false keeps the entire mode absent. */
    private boolean enabled = false;
    /** Default true: validate public data and log intent without sending private trade requests. */
    private boolean dryRun = true;
    /** Second live-trading confirmation. Real orders require enabled=true, dry-run=false, live-enabled=true. */
    private boolean liveEnabled = false;
    private String baseUrl = "https://api.hbdm.com";
    private String marginMode = "isolated";
    private String positionMode = "dual_side";
    private List<String> contractCodes = List.of("BTC-USDT", "ETH-USDT", "XAUT-USDT");
    private int leverage = 5;
    private int maxLeverage = 5;
    private int depthStep = 6;
    private int depthTopN = 10;
    private BigDecimal depthSafetyRatio = new BigDecimal("3.0");
    private BigDecimal minQuoteNotional = new BigDecimal("5");
    private BigDecimal maxSingleQuoteNotional = new BigDecimal("50");
    private BigDecimal marginSafetyRatio = new BigDecimal("1.20");
    private long fillPollIntervalMs = 500L;
    private long fillTimeoutMs = 10_000L;
    private int queryFailureThreshold = 5;

    /** Periodic simulation only. The scheduler refuses to run outside dry-run mode. */
    private boolean autoTestEnabled = false;
    /** Additional opt-in required before the scheduler may submit real contract orders. */
    private boolean autoLiveEnabled = false;
    private long autoTestInitialDelayMs = 20_000L;
    private long autoTestIntervalMs = 60_000L;
    /** Number of contracts, not XAUT quantity. */
    private long autoTestVolume = 2L;
    /** Simulated close target above the current best ask. */
    private BigDecimal autoTestTargetReturnPct = new BigDecimal("0.006");
}
