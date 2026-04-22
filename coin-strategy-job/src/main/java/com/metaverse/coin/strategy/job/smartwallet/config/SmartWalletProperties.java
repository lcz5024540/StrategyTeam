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
    private Intelligence intelligence = new Intelligence();

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
        private Long leaderboardRefreshFixedDelayMs = 300000L;
        private Integer leaderboardRefreshTopN = 10;
        private Long eventSyncFixedDelayMs = 15000L;
        private Long positionSyncFixedDelayMs = 15000L;
        private Long positionSyncErrorCooldownMs = 300000L;
        private Long positionSyncNotFoundCooldownMs = 1800000L;
        private boolean positionSyncAutoRecoveryEnabled = true;
        private Integer positionSyncRecoveryFailureThreshold = 2;
        private Integer watchlistTradeSyncBatchSize = 3;
        private Long walletTradeSyncMinIntervalMs = 30000L;
        private Long walletTradeErrorCooldownMs = 300000L;
        private BigDecimal staleAddressWeightFactor = new BigDecimal("0.35");
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
        private boolean enableAutoStopLoss = false;
        private BigDecimal autoStopLossThresholdRate = new BigDecimal("0.50");
        private boolean enableAutoTakeProfit = false;
        private BigDecimal autoTakeProfitThresholdRate = new BigDecimal("0.20");
        private Integer maxSignalAgeSeconds = 300;
        private BigDecimal minTriggerSizeUsdc = new BigDecimal("5");
        private BigDecimal minFollowPrice = new BigDecimal("0.05");
        private BigDecimal maxFollowPrice = new BigDecimal("0.95");
        private boolean enableProfitabilityGate = false;
        private Integer profitabilityMinSampleSize = 3;
        private BigDecimal minEstimatedNetProfitabilityUsdc = BigDecimal.ZERO;
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
        /**
         * 开单策略路由模式：
         * ANY = 命中任一策略即可开单
         * ALL = 所有启用策略都必须通过
         */
        private String entryStrategyRouteMode = "ANY";
        /**
         * 启用的开单策略列表。默认保留现有行为：
         * 1. 共识策略
         * 2. 单钱包 override 策略
         */
        private List<String> enabledEntryStrategies = new ArrayList<>(List.of("CONSENSUS", "SINGLE_WALLET_OVERRIDE"));
        private Integer consensusWindowSeconds = 180;
        private Integer minDistinctWallets = 2;
        /** 实体去重后的最小实体数门控（替代 minDistinctWallets）。默认与 minDistinctWallets 保持一致 */
        private Integer minDistinctEntities = 2;
        private BigDecimal minConsensusScore = new BigDecimal("0.72");
        private boolean enableSingleWalletOverride = true;
        // Leaderboard survivorship-bias guard: when a wallet shows winRate >= threshold with only a small sample,
        // the number is almost certainly inflated (only top-N positions visible). Block single-wallet override for them.
        private boolean blockUnreliableLeaderboardWinRate = true;
        private BigDecimal unreliableWinRateThreshold = new BigDecimal("1.0");
        private Integer unreliableMaxSampleSize = 50;
        private BigDecimal singleWalletOverrideMinWalletScore = new BigDecimal("0.88");
        private BigDecimal singleWalletOverrideMinWinRate = new BigDecimal("0.68");
        private Integer singleWalletOverrideMinTrades = 60;
        private BigDecimal singleWalletOverrideMinTriggerSizeUsdc = new BigDecimal("100");
        private BigDecimal singleWalletOverrideMinRecommendedStakeUsdc = new BigDecimal("5");

        // ── 持仓集中度门控（防控盘） ──────────────────────────────────────────
        /** 是否开启持仓集中度门控（默认关闭，验证 API 格式后再上线） */
        private boolean enableConcentrationGate = false;
        /** CR_1 上限：单一持仓者占比超过此值则拒绝信号 */
        private BigDecimal maxTop1ConcentrationPct = new BigDecimal("0.40");
        /** CR_3 上限：前三持仓者合计占比超过此值则拒绝信号 */
        private BigDecimal maxTop3ConcentrationPct = new BigDecimal("0.65");
        /** HHI 上限：超过则判定为高度集中市场（0=不启用 HHI 检查） */
        private int maxHhi = 2500;
        /**
         * 鲸鱼信号来源处置模式：
         *   BLOCK     - 直接拒绝（默认）
         *   DOWNSIZE  - 仓位缩至 whaleSignalDownsizeRate
         *   WARN_ONLY - 仅告警，不影响决策
         */
        private String whaleSignalSourceMode = "BLOCK";
        /** DOWNSIZE 模式下仓位保留比例（默认保留 20%） */
        private BigDecimal whaleSignalDownsizeRate = new BigDecimal("0.20");
        /** 集中度数据缓存有效期（ms） */
        private int concentrationCacheTtlMs = 60000;
        /** 集中度分析拉取的最大持仓人数（Polymarket limit 上限 100） */
        private int concentrationTopN = 50;
        /** CR_3 低于此值时不施加仓位惩罚 */
        private BigDecimal concentrationStakeSafeTop3Pct = new BigDecimal("0.40");
        /** 集中度惩罚最大削减比例（0.5 = 最多缩减 50%） */
        private BigDecimal concentrationStakePenaltyMaxRate = new BigDecimal("0.50");

        // ── Kelly 动态仓位 ────────────────────────────────────────────
        /** 是否启用 Kelly 仓位计算（false = 回退到原有 triggerSize * walletScore 公式） */
        private boolean kellyStakeEnabled = false;
        /** Kelly 折扣因子（0.3 = 30% Kelly，保守值） */
        private BigDecimal kellyFraction = new BigDecimal("0.30");

        // ── 时间感知止损 ──────────────────────────────────────────────
        /** 是否启用时间感知动态止损阈值（false = 使用 autoStopLossThresholdRate 固定值） */
        private boolean timeAwareStopLossEnabled = false;
        /** 止损阈值分段列表，按 remainingHours 降序排列，匹配第一个不超过剩余时间的档位 */
        private List<StopLossTier> stopLossTiers = new ArrayList<>();
        /** 是否启用趋势加速止损（连续 N 期下跌时提前止损） */
        private boolean trendStopLossEnabled = false;
        /** 趋势加速止损连续下跌周期数（每周期 = 一次 checkStopLoss 扫描，约 5 分钟） */
        private int trendStopLossConsecutivePeriods = 3;

        // ── 方案C：退出信号提供者模式 ────────────────────────────────────
        /**
         * 退出信号提供者模式：
         *   RULES   - 规则退出（时间感知止损 + 趋势止损，即 TimeAwareStopLossService）
         *   SCORING - 特征评分退出（时间层级基准 + ROI 动量调整，即 ScoredExitSignalProvider）
         */
        private String exitSignalProviderMode = "RULES";
        /** 评分退出：ROI 动量权重（每 1% 亏损对基准阈值的缩减量，默认 0.10 = 每亏 10% 收紧 1%） */
        private BigDecimal exitScoringRoiMomentumWeight = new BigDecimal("0.10");
        /** 评分退出：最小止损阈值下限（防止评分后阈值过小导致频繁止损） */
        private BigDecimal exitScoringMinThreshold = new BigDecimal("0.03");
        /** 评分退出：最大止损阈值上限（防止评分后阈值过大导致止损失效） */
        private BigDecimal exitScoringMaxThreshold = new BigDecimal("0.50");

        /**
         * Backward-compatible accessors for existing YAML keys:
         *   smart-wallet.signal-risk.enable-ev-gate
         *   smart-wallet.signal-risk.ev-min-sample-size
         *   smart-wallet.signal-risk.min-expected-net-ev-usdc
         */
        @Deprecated
        public boolean isEnableEvGate() {
            return enableProfitabilityGate;
        }

        @Deprecated
        public void setEnableEvGate(boolean enableEvGate) {
            this.enableProfitabilityGate = enableEvGate;
        }

        @Deprecated
        public Integer getEvMinSampleSize() {
            return profitabilityMinSampleSize;
        }

        @Deprecated
        public void setEvMinSampleSize(Integer evMinSampleSize) {
            this.profitabilityMinSampleSize = evMinSampleSize;
        }

        @Deprecated
        public BigDecimal getMinExpectedNetEvUsdc() {
            return minEstimatedNetProfitabilityUsdc;
        }

        @Deprecated
        public void setMinExpectedNetEvUsdc(BigDecimal minExpectedNetEvUsdc) {
            this.minEstimatedNetProfitabilityUsdc = minExpectedNetEvUsdc;
        }
    }

    @Data
    public static class StopLossTier {
        /** 此阈值适用的最大市场剩余小时数（remainingHours <= this value） */
        private int remainingHours;
        /** 止损触发阈值（正数，0.25 = -25% ROI 触发） */
        private BigDecimal thresholdRate;
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
        private String relayerBaseUrl = "https://relayer.polymarket.com";
        private String builderApiKey;
        private Integer requestTimeoutSeconds = 20;
        private Integer retryBatchLimit = 20;
        private Integer retryBackoffMinutes = 15;
        private Integer maxRetryFailures = 8;
    }

    @Data
    public static class Intelligence {
        /** Polygonscan API Key for funding source analysis (Polygon chain) */
        private String polygonscanApiKey = "";
        /** Polygonscan API base URL */
        private String polygonscanApiBaseUrl = "https://api.polygonscan.com/api";
        /** Clustering job fixed delay in ms */
        private Long clusterFixedDelayMs = 3600000L;
        /** Minimum cluster confidence to treat as same entity for consensus dedup (0-1) */
        private BigDecimal minClusterConfidenceForDedup = new BigDecimal("0.40");
        /** BehaviorSimilarityClusterer similarity threshold (wallets above this are considered same entity) */
        private Double behaviorSimilarityThreshold = 0.75;
        /** Entity feature snapshot refresh interval in ms (default 6 hours) */
        private Long entitySnapshotFixedDelayMs = 21600000L;
        /** Deferred candidate reevaluation interval in ms */
        private Long candidateReevaluationFixedDelayMs = 120000L;
        /** Deferred candidate reevaluation batch size */
        private Integer candidateReevaluationBatchSize = 50;
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
