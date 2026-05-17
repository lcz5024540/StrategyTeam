package com.metaverse.coin.strategy.common.service.smartwallet.config;

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
    private Audit audit = new Audit();
    private Claim claim = new Claim();
    private MarketMonitoring marketMonitoring = new MarketMonitoring();
    private List<WalletSeed> wallets = new ArrayList<>();
    private List<MarketSeed> markets = new ArrayList<>();
    private Intelligence intelligence = new Intelligence();
    private FollowFitWeights followFitWeights = new FollowFitWeights();
    private FeeSchedule feeSchedule = new FeeSchedule();
    private AllowlistAggregationConfig allowlistAggregation = new AllowlistAggregationConfig();
    private ExposureLimitConfig exposureLimit = new ExposureLimitConfig();
    private IntentAggregationConfig intentAggregation = new IntentAggregationConfig();
    /** 2026-05-17 ray@cowork: 周期性刷新 smart_wallet_market_info.market_end_at 的 Job 配置 */
    private MarketInfoRefresh marketInfoRefresh = new MarketInfoRefresh();

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

        // ── F-P1-7: LIVE WebSocket book channel 切 hot path ─────────────────
        /**
         * 是否启用风控 / 入场漂移 / 流动性深度的 hot-path 优先读 WS 内存 snapshot。
         * <p>开启后 {@code MarketSnapshotCollectorService.findFreshSnapshot} 会优先尝试
         * {@code PolymarketMarketDataStreamService.getSnapshot(tokenId)}（in-memory WS 数据，
         * 实时性 ≤ wsHotPathSnapshotMaxAgeMs），不可用时再回落到 45s polling 入库的
         * {@code findLatestSnapshotAtOrBefore}。
         * <p>默认 false（保留旧行为，避免 WS 推流尚未稳定时把 polling 的 DB 数据替换为
         * 残缺的 in-memory snapshot 导致 G6/G7/G9 误判）；test 验证 WS 推流稳定 7d 后再切 true。
         */
        private boolean enableWsHotPathSnapshot = false;
        /**
         * WS in-memory snapshot 在 hot-path 中可被接受的最大年龄（毫秒）。
         * <p>默认 5000ms：WS 推流正常时延应 < 1s，留 5s 容差防止单个 tick 抖动导致整段 fallback；
         * 超过此值会自动回落到 DB polling 结果，保证最差也是当前 45s polling 行为，不退化。
         */
        private long wsHotPathSnapshotMaxAgeMs = 5_000L;

        // ── F-P1-8: trade-event WebSocket 接入 ──────────────────────────────
        /**
         * 是否启用 WS user-trades / market-trades 推流入口（leader trade 30s polling → < 2s 推流）。
         * <p>开启后 WS 客户端可直接调 {@code PolymarketSmartWalletMonitorService.ingestWsTradeEvent(String payload)}
         * 把 raw WS payload 推到风控链路，事件级触发 {@code recordTradeEvent} 而不再等 30s 轮询窗口。
         * <p>默认 false（保留旧行为，避免 WS 客户端尚未实装稳定时引入双写/伪事件）；
         * REST polling 路径 ({@code scheduledSyncWatchlistTrades}) 始终保留作为兜底，
         * 即便此开关 ON 也会继续以 30s 节奏跑，靠 {@code SmartWalletFollowTaskService.recordTradeEvent}
         * 中 externalTradeId 唯一性检查 (line 124-145) 做 DB 级去重。
         */
        private boolean enableWsTradeEventPush = false;
        /**
         * WS 推流路径本地 LRU 去重窗口（毫秒）。
         * <p>{@code ingestWsTradeEvent} 维护一个 externalTradeId → seenAt 的内存 map，命中即跳过 DB 写入，
         * 减少 WS 风暴时的无效 DB 查询。窗口外的条目会被惰性清理。
         * <p>默认 60_000ms（1 分钟）：覆盖典型的 WS retry / replay 场景，又不会无限增长。
         * 设为 0 或负数表示禁用本地 LRU，全部 trade 都进入 {@code recordTradeEvent} 让 DB 唯一性兜底。
         */
        private long wsTradeEventDedupeWindowMs = 60_000L;
        /**
         * WS 推流路径本地 LRU 容量上限（条数）。
         * <p>防止 WS 风暴时 map 无限膨胀。达到上限后按插入顺序淘汰最旧的条目（LinkedHashMap removeEldestEntry）。
         * <p>默认 5000 条：常规 leader 集 (~50 wallet × ~10 trade/min) 足够覆盖 10 分钟窗口。
         */
        private int wsTradeEventDedupeMaxEntries = 5_000;

        // ── F-P1-7 / F-P1-8 第十轮：真实 Polymarket WS 客户端 ─────────────────
        /**
         * 是否启动真实 Polymarket WebSocket 客户端。
         * <p>默认 false（先灰度）：开启后由 {@code PolymarketWebSocketClient} 用 OkHttp WS 连接
         * {@link #wsClientMarketChannelUrl} 订阅 market book/price channel，并将事件路由到
         * {@code PolymarketMarketDataStreamService.handleMessage}；同时连接 {@link #wsClientUserChannelUrl}
         * 订阅 user-trades channel，将事件路由到 {@code PolymarketSmartWalletMonitorService.ingestWsTradeEvent}。
         * <p>关闭时（默认）所有 hot-path / trade-event WS 推流都走 ingestMockEvent / 测试入口；
         * 不影响既有 REST polling 兜底链路（{@code MarketSnapshotCollectorService} 45s / {@code scheduledSyncWatchlistTrades} 30s）。
         * <p>该开关独立于 {@link #enableWsHotPathSnapshot} / {@link #enableWsTradeEventPush}：
         * client 连接成功并不强制 consumer 改路径，consumer 端开关分别控制。
         */
        private boolean enableWsClient = false;
        /** market book/price WS channel URL；Polymarket 官方文档示例：wss://ws-subscriptions-clob.polymarket.com/ws/market */
        private String wsClientMarketChannelUrl = "wss://ws-subscriptions-clob.polymarket.com/ws/market";
        /** user-trades WS channel URL；Polymarket 官方文档示例：wss://ws-subscriptions-clob.polymarket.com/ws/user */
        private String wsClientUserChannelUrl = "wss://ws-subscriptions-clob.polymarket.com/ws/user";
        /** 重连初始延迟（ms），断线后指数退避基线。 */
        private long wsClientReconnectInitialDelayMs = 2_000L;
        /** 重连最大延迟（ms）兜底，避免长断线时退避无穷增长。 */
        private long wsClientReconnectMaxDelayMs = 60_000L;
        /** 单次连接重试最大次数；超出后按 wsClientReconnectMaxDelayMs 节奏继续慢重连不再快速重试。 */
        private int wsClientMaxReconnectAttempts = 30;
        /** 应用层心跳间隔（s），发送 ping 帧维持长连接。设 0 关闭主动 ping。 */
        private int wsClientPingIntervalSeconds = 30;
        /** OkHttp 连接超时（ms）。 */
        private long wsClientConnectTimeoutMs = 10_000L;
        /** OkHttp 读超时（ms）；超过此值无消息且 ping 也失败时触发 onFailure。 */
        private long wsClientReadTimeoutMs = 90_000L;
        /** 订阅消息发送间隔（ms），用于分批订阅 tokenId（避免单帧过大）。 */
        private long wsClientSubscribeBatchDelayMs = 200L;
        /** 单次订阅消息携带 tokenId 上限。 */
        private int wsClientSubscribeBatchSize = 100;
        /** user channel auth token，订阅 user-trades 时由 client 写入 {auth_token: ...} 字段。 */
        private String wsClientUserChannelAuthToken;

        // ── F-P1-7 / F-P1-8 第十一轮：watchlist 自动订阅协调 ─────────────────────
        /**
         * 自动 watchlist 订阅总开关。
         * <p>开启后由 {@code PolymarketWatchlistSubscriptionService} 周期性地把
         * profileStoreService.listExecutableWatchlist() 的 HOT 钱包地址 +
         * 近期活跃 tradeEvent 涉及的 market tokenIds 增量地推到
         * {@link com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWebSocketClient#subscribeMarkets(java.util.List)} /
         * {@code subscribeUsers}。
         * <p>关闭时（默认）订阅必须由调用方手动触发 {@code subscribeMarkets / subscribeUsers}，
         * 保持与 F-P1-7 / F-P1-8 第十轮上线时一致的"先 client 起来不主动订阅"行为。
         */
        private boolean enableWsAutoSubscribe = false;
        /** 自动订阅刷新周期（ms），定时从 profile + tradeEvent 表增量计算"新进入 watchlist 的钱包 / 市场"。 */
        private long wsAutoSubscribeRefreshIntervalMs = 60_000L;
        /** 自动订阅启动延迟（ms），让 WS client 先完成首次连接再开始批量订阅，避免 onOpen 之前订阅消息全部进 pending 队列。 */
        private long wsAutoSubscribeInitialDelayMs = 30_000L;
        /** 自动订阅 market 端 lookback 行数：取最近 N 条 tradeEvent 提取 marketId → tokenId 集合。 */
        private int wsAutoSubscribeMarketLookback = 200;
        /** 单次刷新最多新增订阅 tokenId 上限，避免冷启动一次把上万 tokenId 全部推送过去引发上游限流。 */
        private int wsAutoSubscribeMaxNewMarketsPerCycle = 200;
        /** 单次刷新最多新增订阅钱包上限。 */
        private int wsAutoSubscribeMaxNewWalletsPerCycle = 200;

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

        // ── Fix-C: 动态滑点配置 ──────────────────────────────────────────
        /** 是否启用基于 orderbook 深度的动态滑点（覆盖固定 priceSlippageRate） */
        private boolean enableDynamicSlippage = false;
        /** 高流动性（askDepth3c >= highLiquidityThreshold）时使用的滑点 */
        private BigDecimal highLiquiditySlippageRate = new BigDecimal("0.01");
        /** 中等流动性时使用的滑点（即默认 priceSlippageRate，2%） */
        private BigDecimal mediumLiquiditySlippageRate = new BigDecimal("0.02");
        /** 低流动性（askDepth3c < lowLiquidityThreshold）时使用的滑点 */
        private BigDecimal lowLiquiditySlippageRate = new BigDecimal("0.03");
        /** 高流动性判定阈值（askDepth USDC） */
        private BigDecimal highLiquidityThresholdUsdc = new BigDecimal("5000");
        /** 低流动性判定阈值（askDepth USDC），低于此值使用更宽滑点 */
        private BigDecimal lowLiquidityThresholdUsdc = new BigDecimal("1000");

        // ── Fix-B3: Maker 挂单策略配置 ──────────────────────────────────
        /**
         * 订单策略：
         * TAKER  — 默认，limitPrice = rawPrice + slippage，倾向立即成交（支付 taker fee）
         * MAKER  — limitPrice = bestBid + 1 tick，挂单等撮合（fee=0 甚至 rebate）
         * ADAPTIVE — 根据市场流动性自动选择：高流动性用 MAKER，低流动性用 TAKER
         */
        private String orderStrategy = "TAKER";
        /** ADAPTIVE 模式下，askDepth3c 超过此值的市场使用 MAKER 策略 */
        private BigDecimal makerStrategyMinDepthUsdc = new BigDecimal("5000");
        /** ADAPTIVE 模式下，spreadBps 低于此值的市场才使用 MAKER 策略 */
        private BigDecimal makerStrategyMaxSpreadBps = new BigDecimal("200");

        // ── F-P0-1: LIVE 部分退出 ─────────────────────────────────────────
        /**
         * 是否启用 LIVE 模式真正的部分退出（按 ratio 拆 SELL 限价单）。
         * 默认 true（P0 修复后）。失败/紧急回滚时可切 false 退化为全平。
         */
        private boolean enableLivePartialExit = true;
        /** 部分退出最小可下单量（低于此值不切单，避免下错 size） */
        private BigDecimal livePartialExitMinSize = new BigDecimal("1");

        // ── F-P1-3: LIVE GTC 全局超时取消 ─────────────────────────────────
        /**
         * 是否启用全局 GTC 超时取消（含 TAKER/MAKER/MAKER_ONLY/MAKER_FIRST 所有 GTC 单）。
         * 启用后由 {@code GtcOrderTimeoutTracker} 在 {@link #gtcDefaultTimeoutSeconds}
         * 到期后调 cancelOrder；失败累计 {@link #gtcCancelMaxAttempts} 次后标记 CANCEL_FAILED。
         *
         * 默认 false（先灰度），test 14d 稳定后切 true 上 prod。
         */
        private boolean enableGtcGlobalTimeout = false;
        /** GTC 全局超时秒数（所有未指定专用超时的 GTC 单兜底），默认 60 秒。 */
        private int gtcDefaultTimeoutSeconds = 60;
        /** GTC 超时取消最大尝试次数，超过后标记 CANCEL_FAILED 走人工处理。 */
        private int gtcCancelMaxAttempts = 3;
        /** GTC tracker scan 扫描间隔（毫秒），默认 3s。 */
        private long gtcCancelScanIntervalMs = 3_000L;
        /**
         * 是否启用 tracker 持久化（pending_order_timeout_tracker 表 write-through）。
         * 默认 true，关闭时退化为纯 in-memory（重启即丢失，与 MakerOnlyOrderTimeoutTracker 同行为）。
         */
        private boolean enablePendingTrackerPersistence = true;

        // ── F-P1-15: MAKER 限价参考实时 best bid ─────────────────────────
        /**
         * 是否启用 MAKER 模式下"参考实时 best bid 收口"的限价计算。
         * 启用后 limitPrice = min(rawPrice + makerTickBufferTicks × tick, bestBid + 1 tick)，
         * 既保证 maker 不会因 rawPrice 滞后于盘口而无法成交，也不会因 rawPrice 已经追到
         * 远高于 bestBid 时把单子推到 taker 区。
         *
         * 默认 true（修复后开启），如 bestBid 不可用会自动 fallback 到 rawPrice + 1 tick。
         */
        private boolean enableMakerBestBidPricing = true;
        /**
         * MAKER 模式下相对 rawPrice 允许向上挂多少个 tick；默认 1（与历史 rawPrice + 1 tick 行为一致）。
         * 取值范围建议 1~3：值越大越接近 taker（成交概率↑、fee 风险↑）。
         */
        private int makerTickBufferTicks = 1;
        /**
         * MAKER 模式下相对 bestBid 允许向上挂多少个 tick；默认 1（=bestBid + 1 tick，标准 maker 位置）。
         */
        private int makerBestBidOffsetTicks = 1;
        /**
         * MAKER best-bid snapshot 的最大可接受年龄（毫秒）；超过此值视为 stale，按 fallback 计算。
         * 默认 30s（与 MarketSnapshotCollectorService 的 45s polling 留 15s 容差）。
         */
        private long makerBestBidSnapshotMaxAgeMs = 30_000L;
        /**
         * MAKER 单挂着未成交时允许重新报价（cancel + repost）的最大次数；默认 3 次。
         * 配合 {@link com.metaverse.coin.strategy.job.smartwallet.service.MakerOnlyOrderTimeoutTracker}
         * 在 maker-only-timeout-seconds 到期前的中间窗口 (每 makerPriceFollowupIntervalMs 一次)
         * 检查 bestBid 漂移，超过 makerPriceFollowupDriftBps 时撤单 + 重报。
         */
        private int makerPriceFollowupMaxRetries = 3;
        /**
         * MAKER 重报触发的 bestBid 漂移阈值 (bps)。
         * 当前限价距离最新 bestBid + makerBestBidOffsetTicks × tick 偏离超过此值时触发重报。
         * 默认 50 bps（≈0.5%）。
         */
        private int makerPriceFollowupDriftBps = 50;
        /**
         * MAKER 重报扫描间隔 (毫秒); 默认 3s，比 makerOnlyScanIntervalMs (2s) 稍长，避免抖动。
         */
        private long makerPriceFollowupIntervalMs = 3_000L;
        /**
         * F-P1-15 第五轮：MAKER 重报循环主开关。默认 false，先在 test 灰度后再 prod。
         * <p>开启后由 {@code MakerPriceFollowupService} 在每个 makerPriceFollowupIntervalMs
         * 扫描 pending_order_timeout_tracker 中 cancel_status=PENDING 且 order_strategy 以 MAKER 起头
         * 的条目，按 makerPriceFollowupDriftBps 判定漂移并 cancel + repost。
         */
        private boolean enableMakerPriceFollowup = false;
        /**
         * MAKER 重报判定的 best-bid snapshot 最大年龄（毫秒）；默认 30s，与
         * {@link #makerBestBidSnapshotMaxAgeMs} 同一窗口（重报时不应使用 stale 数据）。
         */
        private long makerPriceFollowupSnapshotMaxAgeMs = 30_000L;

        // ── F-P1-6: 分批出场 (Staged Exit) ────────────────────────────────
        /**
         * 是否启用自动退出（take-profit / stop-loss）的分批出场。
         * <p>启用后，单次 trigger 不再一次性全平，而是按 {@link #partialExitRatios} 配置的
         * 比例（默认 [0.30, 0.30, 0.40]）分多次扫描周期逐档卖出，降低单次冲击对薄市场盘口的滑点影响。
         * <p>每次扫描周期最多提交一档 SELL 单；待该单成交（{@code hasPendingLiveExitOrder} 通过）
         * 后下一周期再发下一档；当剩余 quantity 占原始仓位 ≤ {@code stagedExitMinResidualRatio}
         * 时合并为全平避免留下"尾巴"。
         * <p>默认 false（灰度起步），上线步骤同 F-P1-15: test 7d → prod。
         */
        private boolean enableStagedExit = false;
        /**
         * 分批出场比例配置（按顺序从第一档开始）。
         * <p>每个值表示"该档卖出占 <b>原始仓位</b>（{@code originalCostBasisUsdc}）的比例"，
         * 必须为正且总和 ≤ 1.0；当配置总和 < 1.0 时，最后一档会被自动放大到剩余全部 quantity。
         * <p>默认 [0.30, 0.30, 0.40] —— 三档卖出，符合学术界关于"减少 market impact"的常见
         * 3-leg TWAP 配置（参考 P9 修复说明）。
         */
        private java.util.List<BigDecimal> partialExitRatios = java.util.List.of(
                new BigDecimal("0.30"), new BigDecimal("0.30"), new BigDecimal("0.40"));
        /**
         * 当剩余 quantity 占原始仓位 ≤ 此比例时，强制走全平（避免留下零碎 quantity 长期占用 capital）。
         * 默认 0.05（5%）。
         */
        private BigDecimal stagedExitMinResidualRatio = new BigDecimal("0.05");
        /**
         * 单笔分批出场最小可下单 USDC 名义值。低于此值时合并到下一档或回退全平。
         * 默认 5 USDC（与 {@code minOrderUsdc} 同一量级，避免下出会被 Polymarket 拒绝的极小单）。
         */
        private BigDecimal stagedExitMinLegNotionalUsdc = new BigDecimal("5");
    }

    @Data
    public static class SignalRisk {
        private boolean allowSellSignal = false;
        private boolean enableAutoStopLoss = false;
        private BigDecimal autoStopLossThresholdRate = new BigDecimal("0.50");
        private boolean enableAutoTakeProfit = false;
        private BigDecimal autoTakeProfitThresholdRate = new BigDecimal("0.35");
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

        // ── Leader 滑动窗口胜率门（W2-T2 落实）──────────────────────────────────
        // 目标：近 N 笔跟单胜率 < minWinRate 的 leader 自动暂停，进入 COOLING 期。
        // 状态机：ACTIVE → (winRate 不达标) COOLING → (paper canary 通过) ACTIVE → (持续不达标) SUSPENDED
        //  - ACTIVE     正常下单
        //  - COOLING    冷却期，只走 paperCanaryStake 试单 N 笔验证
        //  - SUSPENDED  完全停跟，需人工恢复
        /** 是否启用 Leader 滑动窗口胜率门（默认 false，先观察 W1-T4 影子记账数据后再启用） */
        private boolean enableLeaderRollingGate = false;
        /** 滑动窗口大小 N（最近 N 笔跟单），默认 20 */
        private Integer leaderRollingWindowSize = 20;
        /** 触发 COOLING 的最小胜率阈值（实际胜率 < 该值时进入 COOLING） */
        private BigDecimal leaderRollingMinWinRate = new BigDecimal("0.45");
        /** 至少累积多少笔跟单后才允许触发 COOLING，避免冷启动 leader 因小样本被误判 */
        private Integer leaderRollingMinSamples = 8;
        /** 已结清仓位 ROI < 0 视为亏损；该阈值用于把"轻微赚回手续费"也认为是胜 */
        private BigDecimal leaderRollingWinRoiThreshold = new BigDecimal("0.00");
        /** COOLING 状态最少冷却天数 */
        private Integer leaderRollingCoolingDays = 7;
        /** COOLING 期 paper canary 验证笔数，达到后才有资格恢复 ACTIVE */
        private Integer leaderRollingCanaryProbeCount = 5;
        /** COOLING 期 paper canary 验证胜率门槛 */
        private BigDecimal leaderRollingCanaryMinWinRate = new BigDecimal("0.55");
        /** COOLING 期单笔仓位倍率（相对正常 Kelly 仓位），默认 0.05 = 5% Kelly */
        private BigDecimal leaderRollingCoolingStakeMultiplier = new BigDecimal("0.05");
        /** 连续 N 次 COOLING 复活失败后进入 SUSPENDED（完全停跟），默认 3 */
        private Integer leaderRollingSuspendAfterFailedRecoveries = 3;
        /** SUSPENDED 状态默认持续天数（人工干预前提下，超过此时间自动尝试 COOLING 重试） */
        private Integer leaderRollingSuspendDays = 30;
        /** 缓存 leader 滑动窗口指标的 TTL（毫秒），降低 DB 压力 */
        private long leaderRollingCacheTtlMs = 60_000L;
        /** 手动白名单是否可以绕过 COOLING/SUSPENDED 状态。默认 false（保护胜率优先于白名单便利性） */
        private boolean manualAllowlistBypassLeaderRollingGate = false;

        // ── Leader 行为漂移门 (W2-T6 落实) ────────────────────────────────────
        // 目标: leader 当前下单类别 / 大小 / 频率 偏离其 WalletBehaviorProfile 显著时触发降级或拒绝.
        // 实现: LeaderBehaviorDriftService 计算 7 维特征向量 (size + side + 5-cat one-hot),
        //       与最近 N 天 trade event 的"画像 centroid"做 cosine 距离比较.
        // 状态:
        //   distance < threshold        → ACTIVE      正常下单
        //   threshold ≤ distance < severe → DRIFTED   仍开单但 stake × leaderDriftStakeMultiplier (默认 0.25, 与 COOLING 语义共享)
        //   distance ≥ severe           → DRIFTED_SEVERE  直接拒绝 (等同 SUSPENDED)
        // 默认 false: 先观察 W1-T4 影子记账数据 1-2 周再灰度启用.
        /** 是否启用 Leader 行为漂移门 (默认 false) */
        private boolean enableLeaderDriftGate = false;
        /** 画像回溯天数 (默认 30) */
        private Integer leaderDriftLookbackDays = 30;
        /** 触发漂移判定所需的最小历史样本数 (低于此值视为 cold start, 放行) */
        private Integer leaderDriftMinSamples = 10;
        /** DRIFTED 触发阈值 (cosine 距离, 0~2; 0 为完全一致, 0.4 表示有显著偏离) */
        private BigDecimal leaderDriftCosineThreshold = new BigDecimal("0.40");
        /** DRIFTED_SEVERE 拒绝阈值 (cosine 距离 >= 该值则直接拒绝, 等同 SUSPENDED) */
        private BigDecimal leaderDriftSevereCosineThreshold = new BigDecimal("0.60");
        /** DRIFTED 状态下 stake 倍率 (相对正常 Kelly), 默认 0.25 (比 leaderRolling COOLING 略宽松, 因为漂移可能是有效的策略切换) */
        private BigDecimal leaderDriftStakeMultiplier = new BigDecimal("0.25");
        /** leader 画像 centroid 缓存 TTL (毫秒), 默认 5 分钟 */
        private long leaderDriftCacheTtlMs = 300_000L;
        /** 手动白名单是否可以绕过 DRIFTED/DRIFTED_SEVERE 状态 (默认 false) */
        private boolean manualAllowlistBypassLeaderDriftGate = false;

        // ── 价格冲击 / Slippage 三段式门控（W2-T4 落实调整 2）─────────────────────
        // 监控周期分钟级 → leader 与我方下单间隔 1-5 min → 1-2¢ 漂移常见。
        // 不再用一刀切 USDC 硬阈值，按估算 slippage bps 分三段处置：
        //   <  downgradeBps          → 全仓通过
        //   downgradeBps ~ severe    → 降级 ×downgradeMultiplier（默认 0.50） + MAKER_FIRST
        //   severe ~ reject          → 降级 ×severeDowngradeMultiplier（默认 0.25） + MAKER_ONLY
        //   ≥ rejectBps              → 拒绝
        /** 是否启用三段式价格冲击门控（默认开启）。关闭时仅依赖既有 maxExpectedSlippageUsdc 硬阈值。 */
        private boolean enablePriceImpactGate = true;
        /**
         * 第十二批: 是否启用 VWAP 逐档估算器 ({@code MarketPriceImpactEstimator})
         * 替代现有 {@code profitabilityEstimate.getEstimatedSlippageUsdc()} 代理。
         * 默认 false: 仍走代理估算; estimator 拿不到 snapshot 时也会 fallback。
         * 启用后 priceImpact 三段式门控用真实订单簿深度算 slippageBps，更接近真实成交。
         */
        private boolean enableVwapSlippageEstimator = false;
        /** 拒绝阈值（bps），默认 500（≈5%）。来自决议文档调整 2。 */
        private int priceImpactRejectBps = 500;
        /** 重度降级阈值（bps），默认 300（≈3%）。bps 在 [severe, reject) 时仓位 ×severeDowngradeMultiplier。 */
        private int priceImpactSevereDowngradeBps = 300;
        /** 轻度降级阈值（bps），默认 100（≈1%）。bps 在 [downgrade, severe) 时仓位 ×downgradeMultiplier。 */
        private int priceImpactDowngradeBps = 100;
        /** 轻度降级倍率（默认 0.50） */
        private BigDecimal priceImpactDowngradeMultiplier = new BigDecimal("0.50");
        /** 重度降级倍率（默认 0.25） */
        private BigDecimal priceImpactSevereDowngradeMultiplier = new BigDecimal("0.25");
        /** 重度降级后挂单最长等待秒数（MAKER_ONLY），超过后放弃 */
        private int priceImpactMakerOnlyTimeoutSeconds = 10;
        /**
         * 第十二批: 是否启用 MakerOnlyOrderTimeoutTracker 实际撤单. 默认 false:
         * tracker 不注册不扫描; 启用后由 LoggingPolymarketExecutionGateway 在 LIVE
         * 成功下单后, 把 priceImpactExecutionMode=MAKER_ONLY 的订单注册到 tracker,
         * tracker 每 makerOnlyScanIntervalMs 扫描一次, 超时主动撤单.
         */
        private boolean makerOnlyTimeoutTrackerEnabled = false;
        /** tracker 扫描间隔 (毫秒), 默认 2000ms; 配合 priceImpactMakerOnlyTimeoutSeconds. */
        private long makerOnlyScanIntervalMs = 2000L;
        /** 是否对 MAKER_FIRST 单也启用超时撤单 (默认 false, 仅 MAKER_ONLY 启用). */
        private boolean makerOnlyTrackMakerFirst = false;

        // ── F-P0-5: 执行前价格漂移二次检查（pre-execute drift gate） ────────────
        /**
         * 是否在 {@code claimTaskForExecution} 之后、{@code prepareLocalDraft} 之前，
         * 重新查 best bid/ask 与 triggerPrice 比较，若漂移超过 {@link #preExecutePriceDriftBps}
         * 则 SKIP（primarySkipReason=PRE_EXECUTE_PRICE_DRIFT_EXCEEDED）。
         * 默认 true（修复后开启），紧急回滚时可关闭。
         */
        private boolean enablePreExecutePriceDriftCheck = true;
        /**
         * 执行前价格漂移容忍上限（bps），默认 80（=0.80%）。
         * |currentPrice - triggerPrice| / triggerPrice × 10000 超过此阈值时 SKIP。
         */
        private int preExecutePriceDriftBps = 80;

        // ── F-P1-10: 多仓位调整并发 ───────────────────────────────────────
        /**
         * 是否对同事件触发的多条 follower 仓位调整启用并发执行（CompletableFuture.allOf）。
         * 默认 true（修复后开启），紧急回滚切 false 退化为顺序循环。
         * 由 {@code FollowPositionAdjustmentService} 用专用 fixed thread pool + Semaphore 限流，
         * 实际并发不会超过 {@link #positionAdjustmentMaxConcurrency}。
         */
        private boolean enablePositionAdjustmentConcurrency = true;
        /**
         * 并发执行的最大并发度，默认 4。
         * 经验：单仓位退出 ≈ 1-3s 网络往返，4 路并发可让 6 仓位退出延迟控制在单仓位 × 2 以内。
         * 取值范围建议 2~8；过大可能撞上交易所频率限制。
         */
        private int positionAdjustmentMaxConcurrency = 4;

        // ── F-P1-11: OPPOSITE_BUY 自动反手建仓 ────────────────────────────────
        /**
         * 是否在 leader 买入反向 outcome (OPPOSITE_BUY) 时，
         * follower 平掉同侧仓位后自动反手买入对手 outcome（REDUCE_THEN_REVERSE 的 reverse leg）。
         *
         * <p>实现策略：通过 mapper.insert 创建一条 status=PENDING 的新 follow_task，
         * intentKey={@code REVERSE_FROM_<originalTaskId>}，让既有调度链路完成
         * 信号风控 / 价格漂移 / 执行 / 持仓登记 —— 不绕过任何 risk gate。
         *
         * <p><b>默认 false 灰度</b>：上线后先 test 14d 观察 reverse leg 通过率 / 撤单率，
         * 再 prod 灰度。回滚切回 false 即停止反手，已 PENDING 的反手任务由既有
         * follow-task 调度链路按正常 risk gate 处理（不会丢单也不会暴露资金）。
         */
        private boolean enableAutoReverseOnOppositeBuy = false;
        /**
         * 反手建仓的金额 = exitedNotionalUsdc × reverseStakeRatio，封顶 {@link #reverseStakeCapUsdc}。
         * 默认 1.0 即"以平仓回收的 USDC 全额反手"；保守可调 0.5。
         */
        private BigDecimal reverseStakeRatio = BigDecimal.ONE;
        /**
         * 反手建仓金额上限（USDC），避免因 exitedUsdc 异常（如全仓退出时数额过大）一次性反手过重。
         * 默认 50 USDC；为 0 / null 时不封顶，仅受 wallet.maxOrderUsdc 控制。
         */
        private BigDecimal reverseStakeCapUsdc = new BigDecimal("50");

        // ── W2-T5 距结算时间门（time-to-expiry gate, 三段式）─────────────────────
        // 来自决议文档第四节：
        //   remainingTime < timeToExpiryRejectMinutes        → 拒绝
        //   remainingTime < timeToExpiryDowngradeMinutes     → 降级 ×timeToExpiryDowngradeMultiplier
        //   remainingTime ≥ timeToExpiryDowngradeMinutes     → 全仓通过
        // 默认对所有市场启用；天气 / BTC 5min 等结构化短期市场可通过
        // timeToExpiryExemptMarketTypes 豁免（避免一刀切阻塞）。
        /** 是否启用距结算时间门（默认开启）。关闭后仅依赖既有 maxHoursToMarketEnd 上限。 */
        private boolean enableTimeToExpiryGate = true;
        /** 拒绝阈值（分钟），默认 30 min。距结算 < 此值则直接拒绝新开仓。 */
        private int timeToExpiryRejectMinutes = 30;
        /** 降级阈值（分钟），默认 120 min。距结算 < 此值但 ≥ rejectMin 则仓位 ×timeToExpiryDowngradeMultiplier。 */
        private int timeToExpiryDowngradeMinutes = 120;
        /** 降级倍率（默认 0.50）。 */
        private BigDecimal timeToExpiryDowngradeMultiplier = new BigDecimal("0.50");
        /**
         * 豁免门控的 marketType 列表（与 W2-T5 决议文档「天气 / BTC 5min 不一刀切」一致）。
         * 默认空集合：跟单阶段所有 marketType 都受门控约束。短期市场策略上线时再追加。
         * 通过 {@code smart-wallet.signal-risk.time-to-expiry-exempt-market-types[0]=...} 注入。
         */
        private List<String> timeToExpiryExemptMarketTypes = new ArrayList<>();

        // ── W2-T1 流动性 P30 滚动阈值 (轻量化第一版) ──
        // 启用后由 MarketLiquidityPercentileService 按品类返回 P30 流动性, 替代固定的
        // smart-wallet.min-market-liquidity 硬阈值; 服务样本不足时仍回退到硬阈值。
        // 默认关闭, 一键回退即可。
        /** 是否启用按品类的 P30 滚动流动性门 (默认 false, 回退到硬阈值)。 */
        private boolean enableLiquidityRollingPercentile = false;
        /** 触发滚动门所需的最少品类样本数 (低于此值仍回退到硬阈值)。 */
        private int liquidityRollingMinSamples = 6;

        // ── W2-T1.4 价差 P70 hot path 门 (2026-05-16 第十一批) ──
        // 在 enableSpreadP70Gate=true 且 pm_market_liquidity_percentile_daily 表中有
        // spread_bps_p70 数据时, 取该品类 P70 作为容忍上限:
        //   spread <= P70                            → 全仓通过
        //   spread in (P70, P70 * downgradeFactor]   → 仓位 × spreadP70DowngradeMultiplier
        //   spread >  P70 * rejectFactor             → 拒绝
        // 与 W2-T4 价格冲击三段式 (slippage USDC) 是不同维度: priceImpact 看"成交价偏离",
        // spreadP70 看"挂单两侧价差"。两者均默认关闭, 单独由 enableXxxGate 控制。
        /** 是否启用价差 P70 门 (默认 false, 与 W2-T1 同源理由: 待数据持久化 + 灰度验证)。 */
        private boolean enableSpreadP70Gate = false;
        /** 触发价差 P70 门所需的最少品类样本数 (低于此值跳过, 沿用既有 maxSpreadBps 等硬阈值)。 */
        private int spreadP70MinSamples = 6;
        /** 触发降级的倍率: spread > P70 * downgradeFactor → 降级仓位 (默认 1.20, 即 P70 上抬 20%)。 */
        private BigDecimal spreadP70DowngradeFactor = new BigDecimal("1.20");
        /** 触发拒绝的倍率: spread > P70 * rejectFactor → 拒绝 (默认 1.80, 即 P70 上抬 80%)。 */
        private BigDecimal spreadP70RejectFactor = new BigDecimal("1.80");
        /** 降级倍率 (与 W2-T4 一致取 0.50, 命中即 ×0.5 Kelly, 受 minOrderUsdc 兜底)。 */
        private BigDecimal spreadP70DowngradeMultiplier = new BigDecimal("0.50");
        /** 价差快照陈旧度上限 (毫秒, 超过即跳过本门控)。默认 5 分钟与既有 askDepth 一致。 */
        private long spreadP70SnapshotMaxAgeMs = 300_000L;

        private Integer maxOpenPositions = 10;
        /**
         * F-P1-17: {@link com.metaverse.coin.strategy.job.smartwallet.service.CapitalRecycleExitService#countPendingSignals}
         * 查询 PENDING 任务时回看的小时数。默认 24h，足够覆盖一日内积压；调到更短可让评分更"实时"，
         * 调到更长可纳入更多积压（但要警惕僵尸数据虚增）。
         */
        private Integer pendingSignalLookbackHours = 24;
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
        /**
         * 共识门控模式（W2-T3 落实调整1：监控样本不足时不应作为硬门控）：
         *   BOOST_ONLY  - 共识仅作为加分项；单实体也允许跟单（默认）
         *   HARD_GATE   - 共识不足则拒绝（监控地址数量充裕时启用）
         * 默认 BOOST_ONLY，未来监控地址数 ≥ 100 高质量 leader 后可切换为 HARD_GATE。
         */
        private String consensusGateMode = "BOOST_ONLY";
        private String policyTag = "candidate-v2";

        // ── F-P1-2: ReplayLiveWindow baseline A/B ────────────────────────────
        /**
         * F-P1-2：是否在 {@code replayLiveWindow} 中跑 baseline (旧策略) vs candidate (当前策略) 对比.
         * 默认 true，可在扫描成本高时通过 {@code signal-risk.enable-replay-live-window-baseline=false} 关闭.
         * 关闭后 baseline = candidate，diff 报告里 policy_diff=NONE.
         */
        private Boolean enableReplayLiveWindowBaseline = Boolean.TRUE;

        // ── F-P1-5: 退出阈值扣除双边 taker fee ────────────────────────────────
        /**
         * F-P1-5：是否在 auto-stop-loss / auto-take-profit 阈值上额外加上 2 × observedTakerFeeRate.
         * 公式：{@code effectiveThreshold = configuredThreshold + 2 × observedTakerFeeRate}.
         * 默认 true，紧急回滚时切 false 退化到旧行为.
         */
        private boolean enableExitThresholdFeeAdjustment = true;
        /** 扣除费率倍数，默认 2 (一买一卖各一份 taker fee). */
        private BigDecimal exitThresholdFeeMultiplier = new BigDecimal("2");
        /** 单次扣除上限（避免极端高 fee 把阈值拉得过高）. */
        private BigDecimal exitThresholdFeeAdjustmentCap = new BigDecimal("0.08");
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
        // W2-T3 落实调整 1：将共识强度从硬门控调整为"仓位加成"。
        // 单实体看多 → ×1.0；2 实体共识 → ×1.20；3+ 实体共识 → ×1.50；
        // 仍受 maxStakeUsdc / consensusAmplifierMaxMultiplier 约束以防极端场景。
        /** 是否启用共识多实体仓位放大器 */
        private boolean enableConsensusStakeAmplifier = true;
        /** dedupedEntityCount=2 时的仓位放大倍率（默认 1.20，与决议文档一致） */
        private BigDecimal consensusAmplifierTier2Multiplier = new BigDecimal("1.20");
        /** dedupedEntityCount>=3 时的仓位放大倍率（默认 1.50，与决议文档一致） */
        private BigDecimal consensusAmplifierTier3PlusMultiplier = new BigDecimal("1.50");
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
        /**
         * F-P0-3: 集中度数据不可用时的兜底策略。
         * <ul>
         *   <li>{@code false}（默认）— fail-closed：API 失败或快照缺失时拒绝信号
         *       （primarySkipReason=CONCENTRATION_DATA_UNAVAILABLE），避免集中度风控被静默绕过。</li>
         *   <li>{@code true} — fail-open：紧急回滚开关，回到旧行为（不阻断信号）。</li>
         * </ul>
         */
        private boolean enableConcentrationFailOpen = false;
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
        /** 手动白名单是否允许绕过 fast-event 新入场拦截。默认关闭，test paper 可显式打开。 */
        private boolean manualAllowlistBypassFastEventBlock = false;
        /** 手动白名单是否允许绕过 Kelly negative-EV 拒绝。默认关闭，test paper 可显式打开。 */
        private boolean manualAllowlistBypassKellyNegativeEv = false;
        /** 行为稳定度过低时开始缩仓 */
        private BigDecimal stakeStabilityLowScore = new BigDecimal("0.45");
        /** 行为稳定度过低时的保留仓位比例 */
        private BigDecimal stakeStabilityLowMultiplier = new BigDecimal("0.70");
        /** 行为稳定度较高时允许的小幅加仓阈值 */
        private BigDecimal stakeStabilityHighScore = new BigDecimal("0.75");
        /** 行为稳定度较高时的加仓倍率 */
        private BigDecimal stakeStabilityHighMultiplier = new BigDecimal("1.05");
        // ── 深度感知仓位调整 ────────────────────────────────────────────
        /** 是否启用 orderbook 深度感知仓位调整（超出深度部分自动缩减仓位） */
        private boolean enableDepthAwareSizing = true;
        /** 允许吃掉的 ask 深度比例上限（±3c 范围内），超出部分自动缩减仓位 */
        private BigDecimal depthConsumptionMaxRate = new BigDecimal("0.50");
        /** 深度检查使用的价格区间：1c / 3c / 5c */
        private String depthCheckRange = "3c";
        /** 最低可接受深度（USDC），低于此值直接拒绝入场 */
        private BigDecimal minAcceptableDepthUsdc = new BigDecimal("100");

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

    /**
     * W1 审计配置. 涵盖:
     *   - Live ↔ Replay 一致性校验阈值 (T1)
     *   - 影子记账 (T4) 的结算回填 Job 与周报
     *
     * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md.
     */
    @Data
    public static class Audit {
        /** W1-T1 Live↔Replay PnL diff 告警阈值 (bps). */
        private Long liveReplayPnlDiffBps = 100L;
        /** W1-T1 Live↔Replay 信号缺失百分比告警阈值 (0-1). */
        private BigDecimal liveReplaySignalMissPct = new BigDecimal("0.05");
        /** W1-T1.3 是否开启 LiveReplayDiffJob. */
        private boolean liveReplayDiffEnabled = true;
        /** W1-T1.3 调度 cron, 默认每日 02:00 (Asia/Shanghai). 6 段 Spring cron. */
        private String liveReplayDiffCron = "0 0 2 * * *";
        /** W1-T1.3 调度时区. */
        private String liveReplayDiffZone = "Asia/Shanghai";

        // ── W1-T4 影子结算回填 Job ──
        /** 是否开启 ShadowSettlementBackfillJob. */
        private boolean shadowBackfillEnabled = true;
        /** Job 调度间隔 (毫秒), 默认 10 分钟. */
        private Long shadowBackfillFixedDelayMs = 600000L;
        /** 每次调度处理的最大行数 (避免一次性扫描过多 PENDING 记录). */
        private Integer shadowBackfillBatchSize = 200;
        /** 影子记录最长保留天数, 超期且市场未结算的标记为 EXPIRED. */
        private Integer shadowMaxAgeDays = 60;
        /** 单次 Job 整体超时 (毫秒), 超时记录 warn 但不 throw, 防止单次调度长期占用线程. */
        private Long shadowBackfillRunTimeoutMs = 300000L;

        // ── W1-T4 周报 ──
        /** 是否开启周报输出. */
        private boolean shadowWeeklyReportEnabled = true;
        /** 周报 cron, 默认每周一 06:00 (Asia/Shanghai). 字段格式为 6 段 Spring cron. */
        private String shadowWeeklyReportCron = "0 0 6 * * MON";
        /** 周报回看窗口 (天). 默认 7 天. */
        private Integer shadowWeeklyReportLookbackDays = 7;
        /** 周报输出阈值: 影子-真实 PnL 差异 (USDC) 超过此值时 WARN. */
        private BigDecimal shadowWeeklyReportPnlDiffWarnUsdc = new BigDecimal("50");

        // ── W1-T2 结算源对齐审计 (轻量化第一版) ──
        /**
         * 是否开启 {@code SettlementSourceAuditJob}. 默认开启 (纯只读聚合, 无副作用).
         * 关闭后整个 Job 不再触发, 也不会输出日志.
         */
        private boolean settlementSourceAuditEnabled = true;
        /**
         * 结算源对齐审计 cron, 默认每小时整点 00 分触发. 6 段 Spring cron 格式.
         */
        private String settlementSourceAuditCron = "0 0 * * * *";
        /** 结算源对齐审计调度时区. */
        private String settlementSourceAuditZone = "Asia/Shanghai";

        // ── W2-T4.5 滑点事后统计 (post-fact slippage report) ──
        /**
         * 是否开启滑点事后统计 Job. 默认开启 (纯只读聚合, 无副作用).
         * 关闭后整个 Job 不再触发, 也不会输出日志.
         */
        private boolean slippagePostFactEnabled = true;
        /**
         * 滑点事后统计 cron, 默认每周一 07:00 (紧跟 shadow weekly report 之后).
         * 6 段 Spring cron 格式.
         */
        private String slippagePostFactCron = "0 0 7 * * MON";
        /** 滑点事后统计调度时区. */
        private String slippagePostFactZone = "Asia/Shanghai";
        /** 滑点事后统计回看窗口 (天). 默认 7 天. */
        private Integer slippagePostFactLookbackDays = 7;
        /**
         * 单次扫描的最大订单数, 防止极端流量场景下 Job 阻塞.
         * 仅作软上限, 超出仅打 warn 但不中断.
         */
        private Integer slippagePostFactMaxOrders = 5000;
        /**
         * 单一品类 |slippage_bps| 的 P95 超过此阈值时 WARN.
         * 默认 300bps (= 3%) 与 W2-T4 priceImpactSevereDowngradeBps 一致.
         */
        private Integer slippagePostFactWarnP95Bps = 300;

        // ── W1-T1.5 / W1-T4.5 审计告警通道接入 ──
        /**
         * 是否开启统一审计告警 Sink (写库 strategy_alert + 日志). 默认开启.
         * 关闭后所有审计 Job 仍照常输出 WARN 日志, 仅不再 fire 进 strategy_alert 表.
         * <p>未来接入飞书 / 邮件时仅需在 Sink 实现层加装饰器, 此开关即同步生效.</p>
         */
        private boolean alertSinkEnabled = true;

        // ── F-P1-9: 飞书 audit sink ────────────────────────────────────
        /**
         * 是否启用飞书 webhook sink (装饰在 strategy_alert sink 之上).
         * 启用后，每次 publish 都会在落 strategy_alert 表 / 日志的同时往飞书机器人推送一条
         * 卡片消息；webhook 失败 swallow，不影响主流程。
         *
         * 默认 false：依赖 webhook 配置；上线前先在 test 验证 webhook 健康度。
         */
        private boolean feishuSinkEnabled = false;
        /** 飞书机器人 webhook URL (含 token)，feishuSinkEnabled=true 时必填。 */
        private String feishuWebhookUrl;
        /** 飞书机器人 secret，若机器人启用签名校验则必填。 */
        private String feishuWebhookSecret;
        /**
         * 飞书 webhook 调用超时（毫秒），默认 3 秒。
         * 设过低易触发告警丢失，设过高会阻塞 sink 调用线程。
         */
        private int feishuWebhookTimeoutMs = 3_000;
        /**
         * 同 alertType 在多少毫秒内只往飞书推一次（避免飞书机器人被限流）。
         * 默认 5 分钟。落库的 strategy_alert 表自己也有去重 (alertType+strategyId)，
         * 但飞书这层去重是为了避免同一告警在多次刷新里反复 ping ops。
         */
        private long feishuDedupeIntervalMs = 300_000L;
        /**
         * 允许推送到飞书的告警等级名单 (逗号分隔)。空 = 全部允许；
         * 实践中常配 "WARN,CRITICAL" 过滤掉 INFO 噪音。
         */
        private String feishuAllowedSeverities = "WARN,CRITICAL";
        /**
         * Leader 行为漂移告警 Sink 是否启用 (W2-T6 与 W1-T1.5 同步接入).
         * 因为 LeaderBehaviorDriftService 在每个 SignalRiskService 信号路径上调用,
         * 单独开关防止极端高流量场景下重复 fire (StrategyAlertService 自带去重, 但配置层
         * 多一道开关便于灰度).
         */
        private boolean leaderDriftAlertEnabled = true;

        // ── W2-T6 / 第八批: Leader 行为漂移聚合 Job (LeaderDriftAggregationJob) ──
        /**
         * 是否开启 LeaderDriftAggregationJob. 默认开启 (纯只读聚合, 无副作用).
         * <p>本 Job 作为「热路径 LeaderBehaviorDriftService 不直接 fire」的补集 — 把
         * 单 leader 单次 hit 的 DRIFTED/SEVERE 聚合到日 / 周窗口再批量 publish
         * {@code AUDIT_LEADER_DRIFT}, 既避免告警表噪音也让运维提前看到 drift 分布.</p>
         */
        private boolean leaderDriftAggregationEnabled = true;
        /** 聚合 Job cron, 默认每天 08:00 (Asia/Shanghai), 紧跟夜间监控数据增量完成之后. */
        private String leaderDriftAggregationCron = "0 0 8 * * *";
        /** 聚合 Job 调度时区. */
        private String leaderDriftAggregationZone = "Asia/Shanghai";
        /** 评估的 leader 数量上限, 防止极端钱包池场景下 Job 阻塞. */
        private Integer leaderDriftAggregationMaxLeaders = 500;
        /**
         * 每个 leader 取最近 N 小时内的一笔 trade event 作为 "current vector" 进行评估.
         * 默认 24h: 与日聚合频率匹配, 避免重复算同一笔事件.
         */
        private Integer leaderDriftAggregationLookbackHours = 24;
        /**
         * 当 (DRIFTED + DRIFTED_SEVERE) / evaluated >= 此比例时 publish AUDIT_LEADER_DRIFT.
         * 默认 0.15 (15%). 设为 0 表示任何 DRIFTED leader 都告警 (调试用),
         * 设为 1 以上表示完全不告警 (仅落日志).
         */
        private BigDecimal leaderDriftAggregationWarnDriftedRatio = new BigDecimal("0.15");
        /** 即使比例未超阈值, 若 DRIFTED_SEVERE 绝对数量 >= 此值也直接告警 (兜底). */
        private Integer leaderDriftAggregationWarnSevereCount = 3;

        // ── 第十一批: Gamma / UMA / 我方 结算三方 diff (ResolutionTriDiffJob) ──
        /** 是否开启结算三方 diff Job. 默认开启 (纯只读, 无副作用). */
        private boolean resolutionTriDiffEnabled = true;
        /** Job cron, 默认每 4 小时第 15 分钟 (Asia/Shanghai), 错开整点高峰. */
        private String resolutionTriDiffCron = "0 15 */4 * * *";
        /** 调度时区. */
        private String resolutionTriDiffZone = "Asia/Shanghai";
        /** 三方 diff 回看窗口 (小时), 默认 24h. */
        private Integer resolutionTriDiffLookbackHours = 24;
        /**
         * 不一致总数告警阈值: anomalyTotal (oursVsUma + oursVsGamma + umaVsGamma + historyConflict)
         * >= 此值时 publish AUDIT_RESOLUTION_TRIDIFF. 默认 5.
         */
        private Integer resolutionTriDiffWarnThreshold = 5;
        /**
         * 第十二批 #4: 是否在 ResolutionTriDiff Job 后期对 top conflictedMarkets 拉取 live 状态.
         * 默认 false: 仅做"audit 表内部 diff"; 启用后会调 Polymarket Gamma API 拉"现在"状态,
         * 强化对"我方观测过时"场景的发现.
         */
        private boolean resolutionTriDiffLiveRefreshEnabled = false;
        /** Live refresh 单次最多刷新市场数, 防止压垮上游. */
        private Integer resolutionTriDiffLiveRefreshMaxPerRun = 20;

        // ── W2-T1.2 流动性 P30 持久化 (MarketLiquidityPercentileSnapshotJob) ──
        /**
         * 是否开启 MarketLiquidityPercentileSnapshotJob. 默认开启 (纯写入 + 内存聚合, 无副作用).
         * 关闭后 {@code MarketLiquidityPercentileService} 自动回退到现有内存即时聚合.
         */
        private boolean liquidityPercentileSnapshotEnabled = true;
        /** 快照 cron, 默认每日 02:30 (Asia/Shanghai), 紧跟 LiveReplayDiff 02:00 之后. */
        private String liquidityPercentileSnapshotCron = "0 30 2 * * *";
        /** 调度时区. */
        private String liquidityPercentileSnapshotZone = "Asia/Shanghai";
        /**
         * MarketLiquidityPercentileService 优先读 daily 表的最大陈旧度 (天). 超过此值视为 stale,
         * 自动 fallback 内存即时聚合, 避免 Job 失败时门控 用陈旧数据.
         */
        private Integer liquidityPercentileMaxStaleDays = 2;

        // [第十八批 cleanup] 删除第十一批 W1-T2.2 段重复的 resolutionTriDiff* 字段
        // (与上面第十一批 Gamma/UMA/我方 段同名, Java 不允许同一类内字段名冲突).
        // 唯一 unique 的 lookbackDays / maxMarkets / warnInconsistentCount / gammaPaceMs 全仓库
        // 无使用方 (grep 仅匹配此声明本身), 直接删除即可恢复编译.

        // ── W2-T2 灰度自动化 (第十八批): LeaderRollingMonitorJob ───────
        /**
         * 是否开启 LeaderRollingMonitorJob (W2-T2 leader 滑动窗口胜率灰度日级聚合).
         * 默认开启 (纯只读聚合 + upsert 到 {@code leader_rolling_aggregation_daily} 表, 无副作用).
         * <p>本 Job 与 {@code LeaderRollingPerformanceService} (hot path) 完全解耦, 仅做日级 trend
         * 与异常告警 publish. 关闭后 W2-T2 退回"运维每日人工 SOP 查询"。</p>
         * <p>详见 docs/polymarket/audit-observability/w2-t2-grayscale-sop.md.</p>
         */
        private boolean leaderRollingAggregationEnabled = true;
        /** 聚合 Job cron, 默认每日 09:30 (Asia/Shanghai), 紧跟 LeaderDriftAggregation 08:00 之后. */
        private String leaderRollingAggregationCron = "0 30 9 * * *";
        /** 调度时区. */
        private String leaderRollingAggregationZone = "Asia/Shanghai";
        /**
         * 聚合所使用的 lookback 天数 (与 SOP 中 7d / 14d 一致).
         * 同一天每个 lookback 各占一行, 主键 (as_of_date, lookback_days) 唯一.
         * 默认 7d 单档; 上线 14d 需在 YAML 配置 {@code 7,14}.
         */
        private java.util.List<Integer> leaderRollingAggregationLookbackDays =
                new java.util.ArrayList<>(java.util.Collections.singletonList(7));
        /**
         * coolingRatio (= coolingCount / evaluatedCount) 超过此阈值时, 把 healthStatus
         * 标记为 WARN_HIGH_COOLING 并 publish AUDIT_LEADER_ROLLING_HEALTH.
         * 默认 0.40 (40%) — 与 SOP 一致.
         */
        private BigDecimal leaderRollingAggregationWarnCoolingRatio = new BigDecimal("0.40");
        /**
         * overallWinRate (所有 evaluated leader 加权胜率) 低于此阈值时, 标记
         * WARN_LOW_WIN_RATE 并告警. 默认 0.48 (48%) — 与 SOP 阈值树一致.
         */
        private BigDecimal leaderRollingAggregationWarnMinOverallWinRate = new BigDecimal("0.48");
        /**
         * top-N 低胜率 leader 抓取数量, 写入 {@code top_cooling_leaders_json} 供运维快速 review.
         * 默认 5; 设为 0 表示不抓取.
         */
        private Integer leaderRollingAggregationTopNToCapture = 5;
        /**
         * evaluatedCount 至少要 >= 此值才会做 health 判定, 否则统一记为 OK (样本不足时不告警).
         * 默认 10; 与 SOP 中"灰度初期 leader 池 < 10 个不算 cooling"一致.
         */
        private Integer leaderRollingAggregationMinEvaluatedCount = 10;
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

    // ── Taker Fee 动态费率配置 ──────────────────────────────────────────
    @Data
    public static class FeeSchedule {
        /**
         * 品类 → taker fee rate 映射。
         * key = 品类 slug（小写，如 "politics", "sports", "crypto"），
         * value = fee rate（如 0.018 表示 1.8%）。
         *
         * <p>Polymarket 自 2025-03 起的典型费率：
         * politics=0.02, sports=0.015, crypto=0.01, pop-culture=0.01, other=0.02</p>
         */
        private Map<String, BigDecimal> categoryFeeRates = new LinkedHashMap<>();

        /**
         * 全局默认 taker fee rate（当品类未映射或 conditionId 无观测缓存时使用）。
         * 默认 0.02 = 2%，偏保守，确保不低估手续费对 PNL 的侵蚀。
         */
        private BigDecimal defaultTakerFeeRate = new BigDecimal("0.02");

        /**
         * 是否在仓位计算（SignalRiskService）中预先扣减 taker fee。
         * true = stake 按 rawStake / (1 + feeRate) 缩减；
         * false = 保持旧逻辑（事后核算 fee，不影响下单金额）。
         */
        private boolean enablePreTradeFeeDeduction = true;
    }

    // ── 白名单小额高频跟单聚合配置 ──

    @Data
    public static class AllowlistAggregationConfig {
        /** 是否启用聚合缓冲器 */
        private boolean enabled = true;

        /** 是否使用固定最低下单保底模式（推荐开启） */
        private boolean useFixedMinStake = true;

        /**
         * 固定最低下单金额（当 useFixedMinStake=true 时生效）。
         * 当等比缩放后低于 minOrderUsdc 时，直接用此值下单。
         */
        private BigDecimal fixedMinStakeUsdc = new BigDecimal("5");

        /** 聚合窗口时长（毫秒）。超时未达到阈值则放弃累积。默认 30 分钟 */
        private long aggregationWindowMs = 1_800_000L;

        /** 单次聚合下单最大金额上限。防止窗口内累积过多 */
        private BigDecimal maxAggregatedStakeUsdc = new BigDecimal("50");
    }

    // ── 跟单暴露上限配置 ──

    @Data
    public static class ExposureLimitConfig {
        /** 是否启用暴露上限检查 */
        private boolean enabled = true;

        /** 单市场持仓上限 (USDC)。防止在一个市场上投入过多资金 */
        private BigDecimal maxPerMarketUsdc = new BigDecimal("200");

        /** 单跟单地址持仓上限 (USDC)。防止对单一白名单地址过度依赖 */
        private BigDecimal maxPerFollowWalletUsdc = new BigDecimal("300");

        /** 总 TVL 利用率上限。所有跟单持仓总成本 / 总 TVL 不超过此比例 */
        private BigDecimal maxTvlUtilizationPct = new BigDecimal("0.80");
    }

    // ── 意图聚合配置 ──

    @Data
    public static class IntentAggregationConfig {
        /** 是否启用意图聚合 */
        private boolean enabled = true;

        /** 聚合时间窗口（分钟）。同 wallet+market+outcome+side 在此窗口内的事件归属同一意图 */
        private int windowMinutes = 5;

        /** 聚合后最小触发金额 (USDC)。聚合总金额低于此值不生成 follow task */
        private BigDecimal minAggregatedTriggerUsdc = new BigDecimal("5");

        /**
         * 意图聚合激活时的执行延迟（秒）。
         * 当检测到同一 wallet+market+outcome+side 在聚合窗口内有多笔事件时，
         * 延长 followTask 的 scheduledAt，给后续碎片更多时间到达并合并。
         * 默认 30 秒（覆盖 1 个完整 event-sync 周期 30s）。
         */
        private int executionDelaySeconds = 30;

        /**
         * F-P0-4: 单次意图聚合的最大累加倍数。
         * <p>
         * 聚合后 stake 上限 = {@code initialStake × maxStakeMultiplier}。
         * 超过该上限的碎片不再合入（视为新的、独立的意图，由后续窗口处理）。
         * 防止 leader 在窗口内连续加仓 → 单任务 stake 爆 {@code maxOrderUsdc}。
         * <p>
         * 默认 3.0（即同一意图最多放大到首笔 size 的 3 倍）。
         */
        private BigDecimal maxStakeMultiplier = new BigDecimal("3.0");
    }

    /**
     * 2026-05-17 ray@cowork: MarketInfoEndAtRefreshJob 用的配置。
     * <p>
     * market_info migration 之后, 旧 backfill 全 noop, 没有人在补 endAt → 76% 行 NULL。
     * 本 Job 周期性扫 lifecycle=ACTIVE 但 endAt 缺失/过期的行, 强制从 Polymarket Gamma 重拉。
     */
    @Data
    public static class MarketInfoRefresh {
        /** 总开关; 默认开启 */
        private boolean enabled = true;

        /** 单批最大扫描行数; 默认 200, 避免单轮跑爆 Polymarket API */
        private int batchSize = 200;
    }
}
