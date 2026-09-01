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
    private BigDecimal maxSingleQuoteNotional = new BigDecimal("1100");
    private BigDecimal marginSafetyRatio = new BigDecimal("1.20");
    private long fillPollIntervalMs = 500L;
    private long fillTimeoutMs = 10_000L;
    private int queryFailureThreshold = 5;
    /** Conservative headroom below HTX's documented 72 query requests per UID per 3 seconds. */
    private int uidQueryLimit = 68;
    /** Conservative headroom below HTX's documented 72 trade requests per UID per 3 seconds. */
    private int uidTradeLimit = 68;
    private long uidRateWindowMs = 3_000L;

    /** Periodic simulation only. The scheduler refuses to run outside dry-run mode. */
    private boolean autoTestEnabled = false;
    /** Additional opt-in required before the scheduler may submit real contract orders. */
    private boolean autoLiveEnabled = false;
    /** Shadow supervisor for moving-grid hedge/recycle reconciliation. Default off. */
    private boolean hedgeSupervisorEnabled = false;
    /** Shadow mode reconciles and reports only; it never submits pending hedge intents. */
    private boolean hedgeSupervisorShadowOnly = true;
    /** Future live-send gate for pending hedge/recycle intents. Default false. */
    private boolean hedgeAutoSendEnabled = false;
    private long hedgeSupervisorInitialDelayMs = 30_000L;
    private long hedgeSupervisorIntervalMs = 60_000L;
    private int hedgeSupervisorBatchSize = 20;
    private long autoTestInitialDelayMs = 20_000L;
    private long autoTestIntervalMs = 5_000L;
    /** Number of contracts, not XAUT quantity. */
    private long autoTestVolume = 2L;
    /** Per-side target quote notional. Positive values override autoTestVolume. */
    private BigDecimal autoTestQuoteNotional = new BigDecimal("20");
    /** Consume external depth with independent IOC prices instead of equal-price resting orders. */
    private boolean autoFastIocEnabled = false;
    /** Profit-first mode: maker entries and fee-aware reduce-only maker exits. */
    private boolean autoProfitGridEnabled = true;
    /** Conservative maker fee floor per order leg. */
    private BigDecimal makerFeeRatePerSide = new BigDecimal("0.0004");
    /** Net profit retained after both fees, funding and safety buffers. */
    private BigDecimal minNetProfitRate = new BigDecimal("0.0010");
    private BigDecimal fundingBufferRate = new BigDecimal("0.0002");
    private BigDecimal safetyBufferRate = new BigDecimal("0.0005");
    /** Skip new maker grids when the top-of-book spread is wider than this rate. */
    private BigDecimal maxEntrySpreadRate = new BigDecimal("0.0010");
    /** Maximum age of resting maker exits before emergency reduce-only IOC reconciliation. */
    private long profitGridExitTtlMs = 3_600_000L;
    /** IOC emergency exits refuse prices outside this distance from the current best quote. */
    private BigDecimal maxEmergencySlippageRate = new BigDecimal("0.0010");
    /** Stop opening new grids after this realized daily loss; <=0 disables only this numeric cap. */
    private BigDecimal maxDailyRealizedLossUsdt = new BigDecimal("1.0");
    private int maxConsecutiveLosses = 3;
    /** Refresh depth and retry remaining reduce-only IOC close volume before declaring exposure. */
    private int fastIocCloseRetryAttempts = 3;
    /** Avoid hammering private APIs after the account can no longer support both legs. */
    private long insufficientMarginBackoffMs = 300_000L;
    /** Simulated close target above the current best ask. */
    private BigDecimal autoTestTargetReturnPct = new BigDecimal("0.006");
}
