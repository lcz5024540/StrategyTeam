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
    /**
     * 白名单钱包是否允许 LIVE 执行。默认 false（仅 PAPER）。
     * 开启后，仍需白名单条目 allowedExecutionModes 包含 LIVE 且 autoExecutionEnabled=true 才真正走 LIVE。
     */
    private boolean allowlistLiveEnabled = false;
    private boolean scanEnabled = true;
    private boolean runtimeConfigApiEnabled = false;
    private boolean requireTagMatch = true;
    private BigDecimal minWinRate = new BigDecimal("0.62");
    private BigDecimal minWalletScore = new BigDecimal("0.70");
    private Integer minTotalTrades = 25;
    private BigDecimal minRealizedPnlUsdc = new BigDecimal("1000");
    private BigDecimal maxDrawdownRate = new BigDecimal("0.25");
    private boolean observeCooldownEnabled = true;
    private Integer observeCooldownMinRecentTrades7d = 6;
    private BigDecimal observeCooldownLowRecentWinRate7d = new BigDecimal("0.45");
    private BigDecimal observeCooldownMaxRecent30dGap = new BigDecimal("0.20");
    private BigDecimal observeCooldownMaxHistorical7dGap = new BigDecimal("0.25");
    private BigDecimal observeCooldownRecoverMinRecentWinRate7d = new BigDecimal("0.55");
    private BigDecimal observeCooldownRecoverMaxRecent30dGap = new BigDecimal("0.10");
    private Integer observeCooldownDays = 7;
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
    private MarketMonitoring marketMonitoring = new MarketMonitoring();
    private List<WalletSeed> wallets = new ArrayList<>();
    private List<MarketSeed> markets = new ArrayList<>();
    private Intelligence intelligence = new Intelligence();
    private FollowFitWeights followFitWeights = new FollowFitWeights();

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
        private List<LeaderboardQuery> leaderboardQueries = new ArrayList<>();
        private Integer analyzeTopN = 30;
        private Integer closedPositionLimit = 50;
        private Integer recentTradeLimit = 30;
        private Integer userTradeHistoryLookbackDays = 90;
        private Integer discoveryProfileTradeLimit = 30;
        private Integer sessionCoverageLookbackDays = 30;
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
        private Long warmMetricsRefreshFixedDelayMs = 600000L;
        private Integer warmMetricsRefreshBatchSize = 10;
        private Integer leaderTruthRefreshBatchSize = 50;
        private Long warmWalletRefreshMinIntervalMs = 1800000L;
        private boolean hotRetentionEnabled = true;
        private Integer hotRetentionHours = 24;
        private BigDecimal scoreHysteresisBuffer = new BigDecimal("0.08");
        private BigDecimal winRateHysteresisBuffer = new BigDecimal("0.04");
        private Integer warmWalletInactivityDays = 30;
        private BigDecimal staleAddressWeightFactor = new BigDecimal("0.35");
        /**
         * 地址超过该天数没有任何交易动作后进入 COLD，停止 HOT/WARM 轮询资源占用。
         * 后续通过排行榜刷新、手动导入或外部事件观察到新交易时会重新评估。
         */
        private Integer coldWalletInactivityDays = 60;

        /** closed-positions 增量同步配置。基于 sortBy=TIMESTAMP&sortDirection=DESC + 哨兵停止。 */
        private ClosedPositionSync closedPositionSync = new ClosedPositionSync();

        @Data
        public static class ClosedPositionSync {
            /** 总开关：true 启用 DB 驱动增量同步；false 回退原 API 直拉路径（紧急回滚）。 */
            private boolean enabled = true;
            /** 每页条数，Polymarket 服务端硬截为 50。 */
            private Integer pageSize = 50;
            /** 首次全量的最大翻页数（覆盖 ~100000 条硬上限 / 50）。 */
            private Integer maxFullPages = 2200;
            /** 增量翻页的最大页数兜底（一般遇哨兵就会提前终止）。 */
            private Integer maxIncrementalPages = 20;
            /** 连续 N 页无任何新数据则认为增量结束（哨兵命中兜底）。 */
            private Integer incrementalIdlePageThreshold = 2;
            /** 线程池核心线程数（2 核服务器给 1，升级 4 核后调 2）。 */
            private Integer threadPoolCoreSize = 1;
            /** 线程池最大线程数（2 核服务器给 2，升级 4 核后调 4）。 */
            private Integer threadPoolMaxSize = 2;
            /** 线程池队列容量。 */
            private Integer threadPoolQueueCapacity = 200;
            /** HTTP 全局并发限制（信号量），避免触发 429。 */
            private Integer httpConcurrencyLimit = 4;
            /** 单地址全量/增量熔断超时（ms），超时标记 PARTIAL 等下次。 */
            private Long singleWalletTimeoutMs = 600_000L;
            /** 增量调度间隔（ms）。 */
            private Long incrementalFixedDelayMs = 300_000L;
            /** 全量复扫调度间隔（ms），HOT 周 1 次、默认 24h。 */
            private Long fullResyncFixedDelayMs = 86_400_000L;
            /** 全量复扫成功后的最小再次间隔（ms），避免重复全量。 */
            private Long fullResyncMinIntervalMs = 86_400_000L;
            /** 单次调度批量大小（IncrementalJob/FullResyncJob 共用的钱包数上限）。 */
            private Integer dispatchBatchSize = 100;
        }

        @Data
        public static class LeaderboardQuery {
            private String category = "OVERALL";
            private String timePeriod = "ALL";
            private String orderBy = "PNL";
            private Integer limit = 50;
        }
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
        private BigDecimal autoTakeProfitThresholdRate = new BigDecimal("0.35");
        private boolean enableAutoTimeWindowExit = false;
        /** Market-end 前多少小时开始强制退出，避免持仓进入 expiry/oracle 阶段 */
        private Integer autoTimeWindowExitHoursBeforeEnd = 2;
        /** 时间窗口退出允许的最低 ROI；默认 -100% 表示只要能成交就退出 */
        private BigDecimal autoTimeWindowExitMinRoiRate = new BigDecimal("-1.00");
        private Integer fastEventMaxHours = 24;
        private Integer longHorizonMinHours = 48;
        private BigDecimal longHorizonStakeMultiplier = new BigDecimal("0.50");
        private Integer maxSignalAgeSeconds = 3600;
        private BigDecimal minTriggerSizeUsdc = new BigDecimal("5");
        /** 跟单金额相对源钱包本次真实触发名义本金的最大倍数；<=0 表示不限制 */
        private BigDecimal maxStakeToTriggerNotionalRatio = new BigDecimal("0.10");
        /** 单笔跟单金额占当前可用资金的最大比例，避免用小资金账户照搬大户仓位。<=0 表示不限制 */
        private BigDecimal maxSingleOrderCapitalRatio = new BigDecimal("0.01");
        /** 是否要求 leader 在同一 market/outcome 上仍持有盈利仓位后才允许跟单 */
        private boolean requireLeaderProfitableActivePosition = true;
        private BigDecimal minFollowPrice = new BigDecimal("0.02");
        private BigDecimal maxFollowPrice = new BigDecimal("0.98");
        private boolean enableEntryTimingGate = false;
        private BigDecimal maxEntryPriceDriftRate = new BigDecimal("0.05");
        private BigDecimal fastEventMaxEntryPriceDriftRate = new BigDecimal("0.015");
        private BigDecimal standardMaxEntryPriceDriftRate = new BigDecimal("0.03");
        private BigDecimal longHorizonMaxEntryPriceDriftRate = new BigDecimal("0.04");
        private BigDecimal maxEntrySpreadPct = new BigDecimal("0.04");
        private BigDecimal entryLocalTopRangePosition = new BigDecimal("0.85");
        private BigDecimal entryLocalTopMove5mRate = new BigDecimal("0.06");
        private Integer entryTimingSnapshotMaxAgeSeconds = 180;
        private boolean enableSignalScoreGate = false;
        private BigDecimal minLiveSignalScore = new BigDecimal("72");
        private BigDecimal minStandardSignalScore = new BigDecimal("82");
        private BigDecimal signalScoreTargetReturnRate = new BigDecimal("0.03");
        private BigDecimal signalScoreMinLiquidityFactor = new BigDecimal("0.10");
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
        /** 同一 marketId + outcome 最多允许生成的入场订单数，防止同一预测方向被反复加仓刷单 */
        private boolean blockMarketOutcomeOrderLimit = true;
        private Integer maxOrdersPerMarketOutcome = 3;
        private boolean blockDuplicateOpenPosition = true;
        private boolean blockDuplicatePendingTask = true;
        private boolean blockOppositeOpenPositionSameMarket = true;
        private boolean blockOppositePendingTaskSameMarket = true;
        private boolean enableConsensusGate = true;
        private String policyTag = "candidate-v2";
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
        private List<String> enabledEntryStrategies = new ArrayList<>(List.of("CONSENSUS", "HIGH_QUALITY_SINGLE_WALLET", "SINGLE_WALLET_PAPER_CANARY"));
        private Integer consensusWindowSeconds = 180;
        private Integer minDistinctWallets = 1;
        /** 实体去重后的最小实体数门控（替代 minDistinctWallets）。默认与 minDistinctWallets 保持一致 */
        private Integer minDistinctEntities = 1;
        private BigDecimal minConsensusScore = new BigDecimal("0.20");
        private boolean qualityWeightedConsensusEnabled = true;
        private BigDecimal consensusEntityBaseWeight = new BigDecimal("0.60");
        private BigDecimal consensusBehaviorWeight = new BigDecimal("0.15");
        private BigDecimal consensusRecencyWeight = new BigDecimal("0.10");
        private BigDecimal consensusLeaderFollowerWeight = new BigDecimal("0.05");
        private BigDecimal consensusIndependenceWeight = new BigDecimal("0.05");
        private BigDecimal consensusRecentConsistencyWeight = new BigDecimal("0.05");
        private boolean enableSingleWalletOverride = true;
        private boolean enableSingleWalletOverrideCanary = true;
        private BigDecimal singleWalletOverrideCanaryRate = new BigDecimal("0.10");
        private List<String> singleWalletOverrideWhitelist = new ArrayList<>();
        // Leaderboard survivorship-bias guard: when a wallet shows winRate >= threshold with only a small sample,
        // the number is almost certainly inflated (only top-N positions visible). Block single-wallet override for them.
        private boolean blockUnreliableLeaderboardWinRate = true;
        private BigDecimal unreliableWinRateThreshold = new BigDecimal("1.0");
        private Integer unreliableMaxSampleSize = 50;
        private BigDecimal singleWalletOverrideMinWalletScore = new BigDecimal("0.75");
        private BigDecimal singleWalletOverrideMinWinRate = new BigDecimal("0.68");
        private Integer singleWalletOverrideMinTrades = 30;
        private Integer singleWalletOverrideMinRecentTrades7d = 1;
        private BigDecimal singleWalletOverrideMinRecentWinRate7d = new BigDecimal("0.60");
        private BigDecimal singleWalletOverrideMinReplicabilityScore = new BigDecimal("0.60");
        private BigDecimal singleWalletOverrideMinQualityScore = new BigDecimal("0.60");
        private BigDecimal singleWalletOverrideMinTriggerSizeUsdc = new BigDecimal("15");
        private BigDecimal singleWalletOverrideMinRecommendedStakeUsdc = new BigDecimal("1");
        /** 是否启用单钱包 paper canary 入场通道，用于受控验证 OBSERVE_ONLY/共识不足的优质 HOT 钱包 */
        private boolean enableSingleWalletPaperCanary = true;
        /** paper canary 最低钱包评分 */
        private BigDecimal paperCanaryMinWalletScore = new BigDecimal("0.70");
        /** paper canary 最低触发交易金额 */
        private BigDecimal paperCanaryMinTriggerSizeUsdc = new BigDecimal("100");
        /** paper canary 最低校准跟随概率 */
        private BigDecimal paperCanaryMinFollowProbability = new BigDecimal("0.70");
        /** paper canary 固定最小试单金额 */
        private BigDecimal paperCanaryStakeUsdc = new BigDecimal("1.00");
        /** paper canary 可容忍的最大信号年龄，避免同步延迟让验证通道完全无样本 */
        private Integer paperCanaryMaxSignalAgeSeconds = 10800;

        // ── Archetype Bypass for High-Quality Wallets ────────────────────────────
        /**
         * Wallets with walletScore >= this threshold are exempt from the explicit
         * NOISE_OR_INSUFFICIENT early-exit in the archetype classifier, and are
         * eligible for the LONG_HORIZON_SPECULATOR archetype when no positive
         * archetype matches. Prevents misclassifying validated high-quality wallets
         * (sports bettors, long-horizon players) based on inherently poor short-horizon
         * alpha metrics.
         */
        private BigDecimal archetypeLeaderQualityBypassMin = new BigDecimal("0.65");

        /**
         * Minimum number of trades in the last 30 days required before a wallet
         * can be classified as LONG_HORIZON_SPECULATOR. Prevents a brand-new wallet
         * with a synthetic high score (0 local data) from bypassing the NOISE gate.
         */
        private Integer longHorizonSpeculatorMinTrades30d = 3;

        // ── Consensus Stake Amplifier ─────────────────────────────────────────────
        /** 是否启用共识多实体仓位放大器 */
        private boolean enableConsensusStakeAmplifier = true;
        /** dedupedEntityCount=2 时的仓位放大倍率 */
        private BigDecimal consensusAmplifierTier2Multiplier = new BigDecimal("1.15");
        /** dedupedEntityCount>=3 时的仓位放大倍率 */
        private BigDecimal consensusAmplifierTier3PlusMultiplier = new BigDecimal("1.30");
        /** 放大倍率上限（防止极端场景） */
        private BigDecimal consensusAmplifierMaxMultiplier = new BigDecimal("1.50");

        // ── Scale-up on High-Quality Consensus ───────────────────────────────────
        /** 是否允许高质量钱包对已有仓位执行追加买入 */
        private boolean enableScaleUpOnHighQualityConsensus = true;
        /** 追加时允许的最大价格相对漂移（相对已有仓位平均成本） */
        private BigDecimal scaleUpMaxPriceRelativeDrift = new BigDecimal("0.20");
        /** 单个 market/outcome 追加后的最大成本；为空时回退到 smart-wallet.max-stake-usdc */
        private BigDecimal scaleUpMaxMarketOutcomeCostUsdc;

        // ── 持仓集中度门控（防控盘） ──────────────────────────────────────────
        /** 是否开启持仓集中度门控（默认关闭，验证 API 格式后再上线） */
        private boolean enableConcentrationGate = false;
        /** 极端集中度的处置模式：BLOCK / DOWNSIZE / WARN_ONLY */
        private String extremeConcentrationMode = "DOWNSIZE";
        /** 极端集中度 DOWNSIZE 模式下保留的仓位比例 */
        private BigDecimal extremeConcentrationDownsizeRate = new BigDecimal("0.35");
        /** 高风险集中度的处置模式：BLOCK / DOWNSIZE / WARN_ONLY */
        private String concentrationHighRiskMode = "DOWNSIZE";
        /** CR_1 极端集中阈值：单一持仓者超过此值视为极端集中 */
        private BigDecimal maxTop1ExtremeConcentrationPct = new BigDecimal("0.70");
        /** CR_3 极端集中阈值：前三持仓者合计超过此值视为极端集中 */
        private BigDecimal maxTop3ExtremeConcentrationPct = new BigDecimal("0.90");
        /** CR_5 极端集中阈值：仅在 holder 数较少时启用 */
        private BigDecimal maxTop5ExtremeConcentrationPct = new BigDecimal("0.95");
        /** holder 数低于该值时，CR_5 触发极端集中判定 */
        private Integer extremeConcentrationMaxObservedHolders = 10;
        /** 极端集中 HHI 阈值 */
        private int maxExtremeHhi = 6000;
        /** 高风险集中度 DOWNSIZE 模式下保留的仓位比例 */
        private BigDecimal concentrationHighRiskDownsizeRate = new BigDecimal("0.35");
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
        /** 观测持仓人数低于该值时，不用集中度结果阻断或缩仓，避免 top-N 样本过小误判控盘 */
        private Integer concentrationMinObservedHoldersForGate = 12;
        /** CR_3 低于此值时不施加仓位惩罚 */
        private BigDecimal concentrationStakeSafeTop3Pct = new BigDecimal("0.40");
        /** 集中度惩罚最大削减比例（0.5 = 最多缩减 50%） */
        private BigDecimal concentrationStakePenaltyMaxRate = new BigDecimal("0.50");

        // ── Kelly 动态仓位 ────────────────────────────────────────────
        /** 是否启用 Kelly 仓位计算（false = 回退到原有 triggerSize * walletScore 公式） */
        private boolean kellyStakeEnabled = false;
        /** Kelly 折扣因子（0.3 = 30% Kelly，保守值） */
        private BigDecimal kellyFraction = new BigDecimal("0.30");
        /**
         * Kelly 概率边际因子：将 calibratedFollowProbability（跟单价值综合评分）
         * 转换为事件胜率估计：p_win = 0.5 + (followProb - 0.5) * edgeFactor。
         * null = 直接使用 followProb（向后兼容）。
         */
        private BigDecimal kellySignalEdgeFactor = new BigDecimal("0.15");
        /** 行为稳定度过低时开始缩仓 */
        private BigDecimal stakeStabilityLowScore = new BigDecimal("0.45");
        /** 行为稳定度过低时的保留仓位比例 */
        private BigDecimal stakeStabilityLowMultiplier = new BigDecimal("0.70");
        /** 行为稳定度较高时允许的小幅加仓阈值 */
        private BigDecimal stakeStabilityHighScore = new BigDecimal("0.75");
        /** 行为稳定度较高时的加仓倍率 */
        private BigDecimal stakeStabilityHighMultiplier = new BigDecimal("1.05");
        /** 预计滑点 / 仓位超过该比例后开始缩仓 */
        private BigDecimal stakeSlippagePenaltyStartRate = new BigDecimal("0.04");
        /** 滑点/主题相关性等高级缩仓后的最低保留比例 */
        private BigDecimal stakePenaltyFloorMultiplier = new BigDecimal("0.55");
        /** 同主题持仓相关性惩罚倍率 */
        private BigDecimal stakeThemeCorrelationPenaltyMultiplier = new BigDecimal("0.75");

        // ── 时间感知止损 ──────────────────────────────────────────────
        /** 是否启用时间感知动态止损阈值（false = 使用 autoStopLossThresholdRate 固定值） */
        private boolean timeAwareStopLossEnabled = false;
        /** 止损阈值分段列表，按 remainingHours 降序排列，匹配第一个不超过剩余时间的档位 */
        private List<StopLossTier> stopLossTiers = new ArrayList<>();
        /** 是否启用趋势加速止损（连续 N 期下跌时提前止损） */
        private boolean trendStopLossEnabled = false;
        /** 趋势加速止损连续下跌周期数（每周期 = 一次 checkStopLoss 扫描，约 5 分钟） */
        private int trendStopLossConsecutivePeriods = 3;
        /** 趋势止损最少历史样本数：低于此值时不触发，防止重启后样本不足误止损 */
        private Integer minPriceHistorySamples;

        // ── 钱包 archetype 分类阈值 ────────────────────────────────────────────
        /** HF_SCALPER 判定最低 7d 交易次数；低于此值不因交易频率触发 HF 分类 */
        private int hfScalperMinTrades7d = 18;
        /** THEMATIC 判定最低 alphaRule 分位；低于此值不触发主题钱包分类 */
        private BigDecimal thematicMinAlphaRule = new BigDecimal("0.58");
        /** 市场结束时间数据质量守卫：超过此天数认为结束时间异常（数据错误），拒绝入场 */
        private int obviouslyFutureMarketEndDays = 365;

        // ── 方案C：退出信号提供者模式 ────────────────────────────────────
        /**
         * 退出信号提供者模式：
         *   RULES   - 规则退出（时间感知止损 + 趋势止损，即 TimeAwareStopLossService）
         *   SCORING - 特征评分退出（时间层级基准 + ROI 动量调整，即 ScoredExitSignalProvider）
         */
        private String exitSignalProviderMode = "RULES";
        /** 评分退出：ROI 动量权重（每 1% 亏损对基准阈值的缩减量，默认 0.10 = 每亏 10% 收紧 1%） */
        private BigDecimal exitScoringRoiMomentumWeight = new BigDecimal("0.10");
        /** 评分退出：当前共识衰减对退出分的影响权重 */
        private BigDecimal exitScoringConsensusDecayWeight = new BigDecimal("0.30");
        /** 评分退出：盘口恶化对退出分的影响权重 */
        private BigDecimal exitScoringOrderbookDeteriorationWeight = new BigDecimal("0.25");
        /** 评分退出：普通高集中市场对退出分的影响权重 */
        private BigDecimal exitScoringHighConcentrationWeight = new BigDecimal("0.20");
        /** 评分退出：极端集中市场对退出分的影响权重 */
        private BigDecimal exitScoringExtremeConcentrationWeight = new BigDecimal("0.40");
        /** 评分退出：源钱包后续减仓/反向交易对退出分的影响权重 */
        private BigDecimal exitScoringSourceWalletReductionWeight = new BigDecimal("0.35");
        /** 评分退出：源钱包继续加仓时对持有耐心的缓冲权重 */
        private BigDecimal exitScoringSourceWalletAddWeight = new BigDecimal("0.10");
        /** 评分退出：共识基准线，用于计算当前共识衰减程度 */
        private BigDecimal exitScoringBaselineConsensus = new BigDecimal("0.72");
        /** 评分退出：最小止损阈值下限（防止评分后阈值过小导致频繁止损） */
        private BigDecimal exitScoringMinThreshold = new BigDecimal("0.03");
        /** 评分退出：最大止损阈值上限（防止评分后阈值过大导致止损失效） */
        private BigDecimal exitScoringMaxThreshold = new BigDecimal("0.50");

        // ── 资金回收退出 ──────────────────────────────────────────────────
        /** 资金回收退出配置 */
        private CapitalRecycle capitalRecycle = new CapitalRecycle();

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
    public static class CapitalRecycle {
        /** 是否启用资金回收退出 */
        private boolean enabled = false;
        /** PAPER 环境是否允许资金回收自动退出。默认关闭，仅保留持仓监控。 */
        private boolean paperEnabled = false;
        /** LIVE 环境是否允许资金回收自动退出。 */
        private boolean liveEnabled = true;
        /** 持仓超过该小时数才进入候选（默认 8 小时） */
        private int holdingPeriodHours = 8;
        /** 死区下界（ROI 低于此值不回收，留给止损处理） */
        private BigDecimal deadZoneLowRate = new BigDecimal("-0.05");
        /** 死区上界（ROI 高于此值不回收，留给止盈处理） */
        private BigDecimal deadZoneHighRate = new BigDecimal("0.08");
        /** 资金回收评分阈值（0~100，超过才退出） */
        private int recycleThreshold = 60;
        /** 每个扫描周期最多回收仓位数 */
        private int maxRecyclePerRound = 3;
        /** 至少保留的仓位数（不会把所有仓位都回收） */
        private int minRetainPositions = 3;
        /** 每日回收金额上限（USDC），null 或 0 = 不限 */
        private BigDecimal dailyRecycleBudgetUsdc = new BigDecimal("100");
        /** 同一仓位评估后的冷却分钟数 */
        private Integer cooldownMinutes = 30;
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
        private Integer staleOraclePendingHours = 12;
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
        private Integer staleResolvedUnclaimedHours = 12;
    }

    @Data
    public static class MarketMonitoring {
        /**
         * 市场监控接收 PENDING/EXECUTING 跟单任务的最大年龄。仅用于监控候选市场保留窗口，
         * 不影响实际跟单执行的新鲜度风控。
         */
        private Integer activeTaskMaxAgeSeconds = 10800;
        private MetadataRefresh metadataRefresh = new MetadataRefresh();
        private ConcentrationSnapshot concentrationSnapshot = new ConcentrationSnapshot();

        @Data
        public static class MetadataRefresh {
            /**
             * 是否开启市场元数据后台刷新。页面查询只读数据库，不再请求内补刷。
             */
            private boolean enabled = true;
            /**
             * 调度固定间隔。默认 5 分钟刷新一次。
             */
            private Long refreshFixedDelayMs = 300000L;
            /**
             * 候选市场回看窗口，覆盖页面常用视角。
             */
            private Integer candidateLookbackDays = 30;
            /**
             * 单轮最多纳入多少候选市场。
             */
            private Integer maxCandidateMarkets = 120;
            /**
             * 单轮最多刷新多少个市场。
             */
            private Integer refreshBatchSize = 120;
            /**
             * Gamma markets 批量请求的 conditionId 分块大小。
             */
            private Integer gammaChunkSize = 5;
            /**
             * Gamma metadata 单 conditionId 失败后的短冷却，避免请求链路反复打同一外部失败。
             */
            private Long metadataFailureCooldownMs = 300000L;
            /**
             * Gamma public-search 同 question 失败后的短冷却。
             */
            private Long publicSearchFailureCooldownMs = 900000L;
            /**
             * Gamma /markets?condition_ids= 端点返回 403/429/5xx 后的全局熔断冷却。
             */
            private Long metadataEndpointCooldownMs = 120000L;
            /**
             * 单次批量刷新最多请求 Gamma metadata 的次数，避免外部异常时放大请求量。
             */
            private Integer maxGammaRequestsPerRefresh = 20;
            /**
             * 是否允许业务热路径在缓存未命中时同步请求 Gamma。默认关闭，仅后台刷新外部 metadata。
             */
            private boolean hotPathExternalRefreshEnabled = false;
            /**
             * 活跃市场 metadata 最大陈旧时间。
             */
            private Long activeMarketMaxStaleMs = 900000L;
            /**
             * 已关闭/已结算市场 metadata 最大陈旧时间。
             */
            private Long closedMarketMaxStaleMs = 21600000L;
            /**
             * Gamma metadata 端点连续失败达到该阈值后进入端点级熔断。
             */
            private Integer endpointFailureThreshold = 2;
            /**
             * Gamma metadata 端点熔断最大冷却时间。
             */
            private Long endpointCooldownMaxMs = 900000L;
            /**
             * 页面判定元数据过期的阈值。
             */
            private Long maxStaleMs = 900000L;
        }

        @Data
        public static class ConcentrationSnapshot {
            /**
             * 是否开启集中度快照刷新。市场页只读快照，不再触发实时外部调用。
             */
            private boolean enabled = true;
            /**
             * 调度固定间隔。默认 3 分钟，在时效和限频之间做保守平衡。
             */
            private Long refreshFixedDelayMs = 180000L;
            /**
             * 单轮最多刷新多少个市场，避免一次性打太多外部请求。
             */
            private Integer refreshBatchSize = 24;
            /**
             * 候选市场回看窗口。需覆盖市场页常用 7/14/30d 视角。
             */
            private Integer candidateLookbackDays = 30;
            /**
             * 每轮最多纳入多少候选市场参与排队刷新。
             */
            private Integer maxCandidateMarkets = 60;
            /**
             * 同一市场最小刷新间隔，防止热门市场在短时间内被重复抓取。
             */
            private Long minMarketRefreshIntervalMs = 300000L;
            /**
             * 页面可接受的最大快照年龄。超过后按 UNKNOWN 展示，避免拿过期数据做判断。
             */
            private Long maxSnapshotAgeMs = 900000L;
        }
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

    /**
     * 钱包跟随适合度评分 (followFitScore) 各维度权重。
     * 各权重之和建议为 1.0，超出范围时分数会被 clamp 到 [0,1]。
     *
     * 注意：alpha30s/alpha5m 在 Polymarket 预测市场场景下噪声大、对中低频地址区分度低，
     * 已弃用（默认 0），其权重已重分配到 leaderQuality / followableRate / alphaRule / winRate30d。
     * 字段保留以便配置回滚。
     */
    @Data
    public static class FollowFitWeights {
        private BigDecimal leaderQuality   = new BigDecimal("0.25");
        private BigDecimal followableRate  = new BigDecimal("0.35");
        private BigDecimal alpha30s        = BigDecimal.ZERO;
        private BigDecimal alpha5m         = BigDecimal.ZERO;
        private BigDecimal alphaRule       = new BigDecimal("0.25");
        private BigDecimal winRate30d      = new BigDecimal("0.15");
    }
}
