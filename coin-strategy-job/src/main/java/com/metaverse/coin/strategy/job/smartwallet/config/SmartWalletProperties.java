package com.metaverse.coin.strategy.job.smartwallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "smart-wallet")
public class SmartWalletProperties {

    private boolean enabled = true;
    private boolean autoExecutionEnabled = false;
    private boolean scanEnabled = true;
    private boolean requireTagMatch = true;
    private BigDecimal minWinRate = new BigDecimal("0.62");
    private BigDecimal minWalletScore = new BigDecimal("0.70");
    private Integer minTotalTrades = 25;
    private BigDecimal minRealizedPnlUsdc = new BigDecimal("1000");
    private BigDecimal maxDrawdownRate = new BigDecimal("0.25");
    private BigDecimal minMarketLiquidity = new BigDecimal("25000");
    private BigDecimal maxStakeUsdc = new BigDecimal("150");
    private BigDecimal bankrollUtilizationRate = new BigDecimal("0.03");
    private Integer followDelaySeconds = 3;
    private Long pollFixedDelayMs = 5000L;
    private PolymarketMonitor polymarket = new PolymarketMonitor();
    private ExecutionWallet executionWallet = new ExecutionWallet();
    private SignalRisk signalRisk = new SignalRisk();
    private ModelCalibration modelCalibration = new ModelCalibration();
    private BaselineModel baselineModel = new BaselineModel();
    private Map<String, SignalRisk> signalRiskPresets = new LinkedHashMap<>();
    private LiveValidation liveValidation = new LiveValidation();
    private SettlementSync settlementSync = new SettlementSync();
    private Claim claim = new Claim();
    private List<WalletSeed> wallets = new ArrayList<>();
    private List<MarketSeed> markets = new ArrayList<>();

    @Data
    public static class PolymarketMonitor {
        private boolean enabled = true;
        private boolean autoDiscoverEnabled = false;
        private boolean autoSyncEventsEnabled = true;
        private String dataApiBaseUrl = "https://data-api.polymarket.com";
        private String gammaApiBaseUrl = "https://gamma-api.polymarket.com";
        private String leaderboardCategory = "OVERALL";
        private String leaderboardTimePeriod = "ALL";
        private String leaderboardOrderBy = "PNL";
        private Integer leaderboardLimit = 50;
        private Integer analyzeTopN = 30;
        private Integer closedPositionLimit = 50;
        private Integer recentTradeLimit = 30;
        private Long discoveryFixedDelayMs = 3600000L;
        private Long eventSyncFixedDelayMs = 15000L;
        private Long positionSyncFixedDelayMs = 15000L;
        private Long positionSyncErrorCooldownMs = 300000L;
        private Long positionSyncNotFoundCooldownMs = 1800000L;
        private Integer watchlistTradeSyncBatchSize = 3;
        private Long walletTradeSyncMinIntervalMs = 30000L;
        private Long walletTradeErrorCooldownMs = 300000L;
    }

    @Data
    public static class ExecutionWallet {
        private boolean enabled = false;
        private String chain = "polygon";
        private Integer chainId = 137;
        private String address;
        private String privateKey;
        private String funderAddress;
        private BigDecimal maxOrderUsdc = new BigDecimal("150");
        private BigDecimal minOrderUsdc = new BigDecimal("1");
        private BigDecimal priceSlippageRate = new BigDecimal("0.02");
        private BigDecimal estimatedFeeRate = new BigDecimal("0.0015");
        private Boolean allowYes = Boolean.TRUE;
        private Boolean allowNo = Boolean.TRUE;
        private String apiBaseUrl = "https://clob.polymarket.com";
        private String apiKey;
        private String apiSecret;
        private String apiPassphrase;
        private boolean wsEnabled = true;
        private String wsUrl = "wss://ws.polymarket.com";
        private Long wsCacheTtlMs = 30000L;
        private Long wsWarmupWaitMs = 1200L;
        private boolean deriveApiCredsIfMissing = true;
        private String sdkMode = "PYTHON";
        private String pythonExecutable = "python3";
        private String helperScriptPath = "scripts/polymarket_clob_client.py";
        private Integer pythonTimeoutSeconds = 45;
        private Integer signatureType = 0;
        private String orderType = "GTC";
    }

    @Data
    public static class SignalRisk {
        private boolean allowSellSignal = false;
        private Integer maxSignalAgeSeconds = 300;
        private BigDecimal minTriggerSizeUsdc = new BigDecimal("5");
        private BigDecimal minFollowPrice = new BigDecimal("0.05");
        private BigDecimal maxFollowPrice = new BigDecimal("0.95");
        private boolean enableEvGate = false;
        private Integer evMinSampleSize = 3;
        private BigDecimal minExpectedNetEvUsdc = BigDecimal.ZERO;
        private boolean enableProbabilityGate = false;
        private BigDecimal minCalibratedFollowProbability = new BigDecimal("0.52");
        private boolean enableSlippageGate = false;
        private BigDecimal maxExpectedSlippageUsdc = new BigDecimal("1.50");
        private BigDecimal maxSlippageSpikeProbability = new BigDecimal("0.65");
        private Integer maxOpenPositions = 10;
        private BigDecimal maxTotalOpenCostUsdc = new BigDecimal("500");
        private Integer maxHoursToMarketEnd = 72;
        private boolean blockDuplicateOpenPosition = true;
        private boolean blockDuplicatePendingTask = true;
        private boolean blockOppositeOpenPositionSameMarket = true;
        private boolean blockOppositePendingTaskSameMarket = true;
        private boolean enableConsensusGate = true;
        private Integer consensusWindowSeconds = 180;
        private Integer minDistinctWallets = 2;
        private BigDecimal minConsensusScore = new BigDecimal("0.72");
        private boolean enableSingleWalletOverride = true;
        private BigDecimal singleWalletOverrideMinWalletScore = new BigDecimal("0.88");
        private BigDecimal singleWalletOverrideMinWinRate = new BigDecimal("0.68");
        private Integer singleWalletOverrideMinTrades = 60;
        private BigDecimal singleWalletOverrideMinTriggerSizeUsdc = new BigDecimal("100");
        private BigDecimal singleWalletOverrideMinRecommendedStakeUsdc = new BigDecimal("5");
    }

    @Data
    public static class ModelCalibration {
        private BigDecimal followProbabilityScale = new BigDecimal("1.15");
        private BigDecimal followProbabilityBias = new BigDecimal("-0.10");
        private BigDecimal slippageSpikeScale = new BigDecimal("1.10");
        private BigDecimal slippageSpikeBias = new BigDecimal("-0.05");
    }

    @Data
    public static class BaselineModel {
        private BigDecimal grossEdgePriorWeight = new BigDecimal("1.00");
        private FollowProbability followProbability = new FollowProbability();
        private Slippage slippage = new Slippage();
        private SlippageSpike slippageSpike = new SlippageSpike();

        @Data
        public static class FollowProbability {
            private BigDecimal intercept = new BigDecimal("-0.35");
            private BigDecimal walletScoreWeight = new BigDecimal("1.40");
            private BigDecimal consensusScoreWeight = new BigDecimal("1.10");
            private BigDecimal positiveRateWeight = new BigDecimal("1.25");
            private BigDecimal triggerSizeWeight = new BigDecimal("0.45");
            private BigDecimal distinctWalletWeight = new BigDecimal("0.35");
            private BigDecimal imbalanceWeight = new BigDecimal("0.20");
            private BigDecimal spreadPenaltyWeight = new BigDecimal("0.90");
            private BigDecimal volatilityPenaltyWeight = new BigDecimal("0.65");
            private BigDecimal triggerSizeCapUsdc = new BigDecimal("500");
            private BigDecimal distinctWalletCap = new BigDecimal("5");
            private BigDecimal spreadPenaltyScaleBps = new BigDecimal("250");
            private BigDecimal volatilityPenaltyScale = new BigDecimal("0.20");
        }

        @Data
        public static class Slippage {
            private BigDecimal spreadCostWeight = new BigDecimal("0.50");
            private BigDecimal impactCostWeight = new BigDecimal("0.015");
            private BigDecimal volatilityCostWeight = new BigDecimal("0.20");
        }

        @Data
        public static class SlippageSpike {
            private BigDecimal intercept = new BigDecimal("-1.10");
            private BigDecimal spreadRiskWeight = new BigDecimal("1.20");
            private BigDecimal volatilityRiskWeight = new BigDecimal("0.90");
            private BigDecimal sizeDepthRiskWeight = new BigDecimal("1.05");
            private BigDecimal historicalSpikeWeight = new BigDecimal("1.10");
            private BigDecimal spreadRiskScaleBps = new BigDecimal("120");
            private BigDecimal volatilityRiskScale = new BigDecimal("0.12");
            private BigDecimal spikeThresholdBps = new BigDecimal("80");
        }
    }

    @Data
    public static class SettlementSync {
        private boolean enabled = true;
        private Long fixedDelayMs = 300000L;
        private Integer positionLimit = 50;
        private Integer marketLimit = 20;
        private boolean refreshCacheOnEachRun = false;
        private Integer refreshCacheEveryRuns = 12;
        private Integer retryBatchLimit = 20;
        private Integer retryBackoffMinutes = 15;
        private Integer maxRetryFailures = 8;
    }

    @Data
    public static class Claim {
        private boolean enabled = true;
        private Long fixedDelayMs = 60000L;
        private Integer positionLimit = 50;
        private boolean paperAutoClaimEnabled = true;
        private boolean liveAutoClaimEnabled = true;
        private String liveGatewayMode = "MANUAL_CONFIRM";
        private Integer retryBatchLimit = 20;
        private Integer retryBackoffMinutes = 15;
        private Integer maxRetryFailures = 8;
    }

    @Data
    public static class LiveValidation {
        private boolean enabled = false;
        private String preset = "followable";
        private Long fixedDelayMs = 45000L;
        private Integer candidateScanLimit = 40;
        private Integer maxExecutionsPerRun = 1;
        private boolean skipIfPaperPendingExists = true;
        private Integer unsupportedMarketCooldownMinutes = 360;
        private Integer networkErrorThreshold = 2;
        private Integer networkCooldownMinutes = 15;
        private Integer geoblockCooldownMinutes = 720;
    }

    @Data
    public static class WalletSeed {
        private String address;
        private String chain;
        private String alias;
        private BigDecimal winRate = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private Integer totalTrades = 0;
        private BigDecimal averageSizeUsdc = BigDecimal.ZERO;
        private BigDecimal maxDrawdownRate = BigDecimal.ZERO;
        private String preferredOutcome = "YES";
        private List<String> tags = new ArrayList<>();
    }

    @Data
    public static class MarketSeed {
        private String marketId;
        private String question;
        private String outcome = "YES";
        private BigDecimal price = BigDecimal.ZERO;
        private BigDecimal conviction = BigDecimal.ZERO;
        private BigDecimal liquidityUsdc = BigDecimal.ZERO;
        private String category;
        private List<String> tags = new ArrayList<>();
    }
}
