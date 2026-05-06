package com.metaverse.coin.strategy.job.strategy.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitrageOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageFundingSettlement;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitragePosition;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCapacitySummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletConflictMarketView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlTimelinePoint;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionView;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletAccountingService;
import com.metaverse.coin.strategy.job.strategy.model.StrategyConflictRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyDashboardOverviewView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyDetailView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyFactorView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyDashboardMetricsView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyIdentityView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyInsightView;
import com.metaverse.coin.strategy.job.strategy.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyMetricsView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyRegistryView;
import com.metaverse.coin.strategy.job.strategy.model.StrategySummaryView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyTimelinePointView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class StrategyProductQueryService {

    private static final Logger log = LoggerFactory.getLogger(StrategyProductQueryService.class);

    private static final String POLYMARKET_CODE = "POLYMARKET_COPY";
    private static final String FUNDING_ARB_CODE = "FUNDING_ARB";
    private static final String FUNDING_ARB_STRATEGY_TYPE = "SINGLE_EXCHANGE_SPOT_FUTURES_ARBITRAGE_V1";
    private static final String FUNDING_ARB_EXCHANGE = "BINANCE";
    private static final List<String> ISOLATED_MODES = List.of("PAPER", "LIVE");
    private static final List<String> SHARED_MODES = List.of("PAPER", "LIVE");
    private static final List<String> PLANNED_MODES = List.of("PAPER");
    private static final List<String> POLYMARKET_CAPABILITIES = List.of(
            "POSITIONS", "ORDERS", "TIMELINE", "TASKS", "ATTRIBUTION", "FACTORS", "CONFLICTS"
    );
    private static final List<String> FUNDING_ARB_CAPABILITIES = List.of(
            "POSITIONS", "ORDERS", "TIMELINE", "FACTORS"
    );
    private static final List<String> PLANNED_CAPABILITIES = List.of("FACTORS");
    private static final DateTimeFormatter BJT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final SmartWalletAccountingService accountingService;
    private final SingleExchangeArbitragePositionMapper singleExchangeArbitragePositionMapper;
    private final SingleExchangeArbitrageOrderMapper singleExchangeArbitrageOrderMapper;
    private final SingleExchangeArbitrageFundingSettlementMapper singleExchangeArbitrageFundingSettlementMapper;
    private final SingleExchangeSpotFuturesProperties singleExchangeSpotFuturesProperties;

    public StrategyProductQueryService(SmartWalletAccountingService accountingService,
                                       SingleExchangeArbitragePositionMapper singleExchangeArbitragePositionMapper,
                                       SingleExchangeArbitrageOrderMapper singleExchangeArbitrageOrderMapper,
                                       SingleExchangeArbitrageFundingSettlementMapper singleExchangeArbitrageFundingSettlementMapper,
                                       SingleExchangeSpotFuturesProperties singleExchangeSpotFuturesProperties) {
        this.accountingService = accountingService;
        this.singleExchangeArbitragePositionMapper = singleExchangeArbitragePositionMapper;
        this.singleExchangeArbitrageOrderMapper = singleExchangeArbitrageOrderMapper;
        this.singleExchangeArbitrageFundingSettlementMapper = singleExchangeArbitrageFundingSettlementMapper;
        this.singleExchangeSpotFuturesProperties = singleExchangeSpotFuturesProperties;
    }

    public StrategyDashboardOverviewView overview() {
        return overview(null);
    }

    public StrategyDashboardOverviewView overview(String mode) {
        List<StrategyRegistryView> strategies = new ArrayList<>();
        strategies.add(registryOrFallback(POLYMARKET_CODE, () -> buildPolymarketRegistry(mode),
                fallbackPolymarketRegistry(mode)));
        strategies.add(registryOrFallback(FUNDING_ARB_CODE, () -> buildFundingArbRegistry(mode),
                fallbackFundingArbRegistry(mode)));
        strategies.add(placeholderRegistry("ROBOT", "机器人策略", "机器人", "PLANNED", "后续用于事件驱动机器人、盘口套利和自动化规则执行。"));
        strategies.add(placeholderRegistry("INTELLIGENT", "智能策略", "智能", "PLANNED", "后续用于模型评分、智能分层择时和多因子调度。"));
        return new StrategyDashboardOverviewView(BJT_FORMATTER.format(Instant.now()), POLYMARKET_CODE, strategies);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 拆分子查询方法 —— 供拆分后的子接口独立调用
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 策略概要（summary + metrics + insights），不含列表数据，首屏快速加载。
     */
    public StrategySummaryDetailView summaryDetail(String strategyCode, String mode) {
        String normalizedStrategyCode = normalizeStrategyCode(strategyCode);
        if (POLYMARKET_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return buildPolymarketSummaryDetail(mode);
        }
        if (FUNDING_ARB_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            try {
                return buildFundingArbSummaryDetail(mode);
            } catch (RuntimeException ex) {
                log.warn("Failed to build funding-arb summary, returning degraded response", ex);
                return fallbackFundingArbSummaryDetail(mode);
            }
        }
        StrategyIdentityView summary = placeholderIdentity(normalizedStrategyCode, normalizedStrategyCode, "待扩展", "PLANNED", "当前策略尚未接入实时数据。");
        return new StrategySummaryDetailView(
                summary,
                toDashboardMetrics(emptyMetrics()),
                List.of(new StrategyInsightView("INFO", "策略待接入", "当前页面结构已预留，后续可继续接入真实策略数据。"))
        );
    }

    /**
     * 策略持仓列表（当前活跃持仓）。
     */
    public List<StrategyPositionRowView> positions(String strategyCode, String mode) {
        String normalizedStrategyCode = normalizeStrategyCode(strategyCode);
        if (POLYMARKET_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return accountingService.listActivePositions(mode).stream().map(this::toPosition).toList();
        }
        if (FUNDING_ARB_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return fundingArbListOrEmpty("positions", () -> loadFundingArbOpenPositions().stream().map(this::toFundingArbPosition).toList());
        }
        return List.of();
    }

    /**
     * 待结算持仓列表。
     */
    public List<StrategyPositionRowView> nextSettlements(String strategyCode, int limit, String mode) {
        String normalizedStrategyCode = normalizeStrategyCode(strategyCode);
        if (POLYMARKET_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return accountingService.listNextSettlements(limit, mode).stream().map(this::toPosition).toList();
        }
        if (FUNDING_ARB_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return fundingArbListOrEmpty("next settlements", () -> loadFundingArbNextSettlements(limit).stream().map(this::toFundingArbPosition).toList());
        }
        return List.of();
    }

    /**
     * 最近订单列表。
     */
    public List<StrategyOrderRowView> recentOrders(String strategyCode, int limit, String mode) {
        String normalizedStrategyCode = normalizeStrategyCode(strategyCode);
        if (POLYMARKET_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return accountingService.listOrders(limit, mode).stream().map(this::toOrder).toList();
        }
        if (FUNDING_ARB_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return fundingArbListOrEmpty("orders", () -> loadFundingArbRecentOrders(limit).stream().map(this::toFundingArbOrder).toList());
        }
        return List.of();
    }

    /**
     * 收益时间线。
     */
    public List<StrategyTimelinePointView> timeline(String strategyCode, int days, String mode) {
        String normalizedStrategyCode = normalizeStrategyCode(strategyCode);
        if (POLYMARKET_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return accountingService.pnlTimeline(days, mode).stream().map(this::toTimeline).toList();
        }
        if (FUNDING_ARB_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return fundingArbListOrEmpty("timeline", () -> buildFundingArbTimeline(days));
        }
        return List.of();
    }

    /**
     * 冲突市场列表。
     */
    public List<StrategyConflictRowView> conflicts(String strategyCode, int limit, String mode) {
        String normalizedStrategyCode = normalizeStrategyCode(strategyCode);
        if (POLYMARKET_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return accountingService.conflictingMarkets(limit, false, mode).stream().map(this::toConflict).toList();
        }
        return List.of();
    }

    /**
     * 策略因子列表。第一版不引入新库表，直接从现有策略聚合与运行快照映射只读因子。
     */
    public List<StrategyFactorView> factors(String strategyCode, String mode, String window) {
        String normalizedStrategyCode = normalizeStrategyCode(strategyCode);
        if (POLYMARKET_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return buildPolymarketFactors(mode, window);
        }
        if (FUNDING_ARB_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return fundingArbListOrEmpty("factors", () -> buildFundingArbFactors(mode, window));
        }
        return List.of();
    }

    public StrategyFactorView factor(String strategyCode, String factorCode, String mode, String window) {
        String normalizedFactorCode = factorCode == null ? "" : factorCode.trim();
        return factors(strategyCode, mode, window).stream()
                .filter(item -> item.code().equalsIgnoreCase(normalizedFactorCode))
                .findFirst()
                .orElse(null);
    }

    // ──────────────────────────────────────────────────────────────────────────

    public StrategyDetailView detail(String strategyCode) {
        return detail(strategyCode, null);
    }

    /** @deprecated 使用拆分后的子接口替代全量查询 */
    @Deprecated
    public StrategyDetailView detail(String strategyCode, String mode) {
        String normalizedStrategyCode = normalizeStrategyCode(strategyCode);
        if (POLYMARKET_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            return buildPolymarketDetail(mode);
        }
        if (FUNDING_ARB_CODE.equalsIgnoreCase(normalizedStrategyCode)) {
            try {
                return buildFundingArbDetail(mode);
            } catch (RuntimeException ex) {
                log.warn("Failed to build funding-arb detail, returning degraded response", ex);
                StrategySummaryView summary = buildFundingArbSummary(emptyMetrics(), mode);
                return new StrategyDetailView(summary, emptyMetrics(), fallbackFundingArbInsights(),
                        List.of(), List.of(), List.of(), List.of(), List.of());
            }
        }
        StrategySummaryView summary = placeholder(normalizedStrategyCode, normalizedStrategyCode, "待扩展", "PLANNED", "当前策略尚未接入实时数据。");
        return new StrategyDetailView(summary,
                new StrategyMetricsView(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO,
                        0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                        BigDecimal.ZERO, null, BigDecimal.ZERO, null, null),
                List.of(new StrategyInsightView("INFO", "策略待接入", "当前页面结构已预留，后续可继续接入真实策略数据。")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private String normalizeStrategyCode(String strategyCode) {
        if (strategyCode == null || strategyCode.isBlank()) {
            return POLYMARKET_CODE;
        }
        return strategyCode.trim();
    }

    private StrategyDetailView buildPolymarketDetail(String mode) {
        SmartWalletPerformanceView performance = accountingService.performance(mode);
        SmartWalletPnlSummaryView pnlSummary = accountingService.summary(mode);
        SmartWalletCapacitySummaryView capacity = accountingService.capacitySummary(mode);
        List<SmartWalletPositionView> positions = accountingService.listActivePositions(mode);
        List<SmartWalletPositionView> nextSettlements = accountingService.listNextSettlements(8, mode);
        List<SmartWalletOrderView> orders = accountingService.listOrders(30, mode);
        List<SmartWalletPnlTimelinePoint> timeline = accountingService.pnlTimeline(30, mode);
        List<SmartWalletConflictMarketView> conflicts = accountingService.conflictingMarkets(10, false, mode);
        StrategySummaryView summary = buildPolymarketSummary(mode, performance);
        StrategyMetricsView metrics = buildPolymarketMetrics(performance, pnlSummary, capacity);

        return new StrategyDetailView(
                summary,
                metrics,
                buildInsights(metrics, nextSettlements, mode),
                positions.stream().map(this::toPosition).toList(),
                nextSettlements.stream().map(this::toPosition).toList(),
                orders.stream().map(this::toOrder).toList(),
                timeline.stream().map(this::toTimeline).toList(),
                conflicts.stream().map(this::toConflict).toList()
        );
    }

    private StrategySummaryDetailView buildPolymarketSummaryDetail(String mode) {
        SmartWalletPerformanceView performance = accountingService.performance(mode);
        SmartWalletPnlSummaryView pnlSummary = accountingService.summary(mode);
        SmartWalletCapacitySummaryView capacity = accountingService.capacitySummary(mode);
        List<SmartWalletPositionView> nextSettlements = accountingService.listNextSettlements(1, mode);
        StrategyMetricsView metrics = buildPolymarketMetrics(performance, pnlSummary, capacity);
        return new StrategySummaryDetailView(buildPolymarketIdentity(mode), toDashboardMetrics(metrics), buildInsights(metrics, nextSettlements, mode));
    }

    private StrategyDetailView buildFundingArbDetail(String mode) {
        FundingArbSnapshot snapshot = loadFundingArbSnapshot();
        StrategyMetricsView metrics = buildFundingArbMetrics(snapshot);
        StrategySummaryView summary = buildFundingArbSummary(metrics, mode);
        return new StrategyDetailView(
                summary,
                metrics,
                buildFundingArbInsights(snapshot, metrics, mode),
                snapshot.openPositions().stream().map(this::toFundingArbPosition).toList(),
                snapshot.nextSettlements().stream().map(this::toFundingArbPosition).toList(),
                snapshot.orders().stream().limit(30).map(this::toFundingArbOrder).toList(),
                buildFundingArbTimeline(30),
                List.of()
        );
    }

    private StrategySummaryDetailView buildFundingArbSummaryDetail(String mode) {
        FundingArbSnapshot snapshot = loadFundingArbSnapshot();
        StrategyMetricsView metrics = buildFundingArbMetrics(snapshot);
        return new StrategySummaryDetailView(buildFundingArbIdentity(mode), toDashboardMetrics(metrics), buildFundingArbInsights(snapshot, metrics, mode));
    }

    private StrategyRegistryView buildPolymarketRegistry(String mode) {
        SmartWalletPerformanceView performance = accountingService.performance(mode);
        return new StrategyRegistryView(
                POLYMARKET_CODE,
                "Polymarket 跟单策略",
                "Polymarket",
                "ACTIVE",
                buildDescription(mode),
                ISOLATED_MODES,
                POLYMARKET_CAPABILITIES,
                "ISOLATED",
                performance.getOpenPositions(),
                safe(performance.getNetPnlUsdc()),
                safe(performance.getWinRate()),
                performance.getTotalOrders()
        );
    }

    private StrategyRegistryView buildFundingArbRegistry(String mode) {
        StrategyMetricsView metrics = buildFundingArbMetrics(loadFundingArbSnapshot());
        return new StrategyRegistryView(
                FUNDING_ARB_CODE,
                "资金费套利策略",
                "Funding",
                "ACTIVE",
                buildFundingArbDescription(mode),
                SHARED_MODES,
                FUNDING_ARB_CAPABILITIES,
                "SHARED",
                metrics.openPositions(),
                metrics.netPnlUsdc(),
                metrics.winRate(),
                metrics.totalOrders()
        );
    }

    private StrategyIdentityView buildPolymarketIdentity(String mode) {
        return new StrategyIdentityView(
                POLYMARKET_CODE,
                "Polymarket 跟单策略",
                "Polymarket",
                "ACTIVE",
                buildDescription(mode),
                ISOLATED_MODES,
                POLYMARKET_CAPABILITIES,
                "ISOLATED"
        );
    }

    private StrategyIdentityView buildFundingArbIdentity(String mode) {
        return new StrategyIdentityView(
                FUNDING_ARB_CODE,
                "资金费套利策略",
                "Funding",
                "ACTIVE",
                buildFundingArbDescription(mode),
                SHARED_MODES,
                FUNDING_ARB_CAPABILITIES,
                "SHARED"
        );
    }

    private StrategySummaryView buildFundingArbSummary(StrategyMetricsView metrics, String mode) {
        return new StrategySummaryView(
                FUNDING_ARB_CODE,
                "资金费套利策略",
                "Funding",
                "ACTIVE",
                buildFundingArbDescription(mode),
                metrics.openPositions(),
                metrics.compositePnlUsdc(),
                metrics.netPnlUsdc(),
                metrics.realizedPnlUsdc(),
                metrics.unrealizedPnlUsdc(),
                metrics.pendingSettlementPnlUsdc(),
                metrics.winRate(),
                metrics.totalOrders(),
                SHARED_MODES,
                FUNDING_ARB_CAPABILITIES,
                "SHARED"
        );
    }

    private StrategyMetricsView buildFundingArbMetrics(FundingArbSnapshot snapshot) {
        BigDecimal realizedFundingPnl = sum(snapshot.positions(), SingleExchangeArbitragePosition::getFundingFeesEarned);
        BigDecimal realizedClosePnl = sum(snapshot.closedPositions(), SingleExchangeArbitragePosition::getRealizedPnl);
        BigDecimal realizedPnl = realizedFundingPnl.add(realizedClosePnl);
        BigDecimal tradedVolume = snapshot.orders().stream()
                .map(this::resolveFundingArbOrderNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feeUsdc = sum(snapshot.orders(), SingleExchangeArbitrageOrder::getFeeAmount);
        BigDecimal openCostBasis = snapshot.openPositions().stream()
                .map(this::resolveFundingArbCostBasis)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int winningPositions = (int) snapshot.closedPositions().stream()
                .filter(position -> resolveFundingArbPositionRealized(position).compareTo(BigDecimal.ZERO) > 0)
                .count();
        int losingPositions = (int) snapshot.closedPositions().stream()
                .filter(position -> resolveFundingArbPositionRealized(position).compareTo(BigDecimal.ZERO) < 0)
                .count();
        BigDecimal winRate = snapshot.closedPositions().isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(winningPositions)
                .divide(BigDecimal.valueOf(snapshot.closedPositions().size()), 4, RoundingMode.HALF_UP);
        BigDecimal maxDrawdown = calculateMaxDrawdown(buildFundingArbTimeline(snapshot, 3650));

        return new StrategyMetricsView(
                snapshot.orders().size(),
                (int) snapshot.orders().stream().filter(this::isFundingArbFilledOrder).count(),
                snapshot.openPositions().size(),
                0,
                snapshot.closedPositions().size(),
                winningPositions,
                losingPositions,
                winRate,
                tradedVolume,
                feeUsdc,
                realizedPnl,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                realizedPnl,
                realizedPnl,
                BigDecimal.ZERO,
                maxDrawdown,
                0,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                openCostBasis,
                null,
                openCostBasis,
                null,
                null
        );
    }

    private StrategySummaryView buildPolymarketSummary(String mode, SmartWalletPerformanceView performance) {
        return new StrategySummaryView(
                POLYMARKET_CODE, "Polymarket 跟单策略", "Polymarket", "ACTIVE",
                buildDescription(mode),
                performance.getOpenPositions(), safe(performance.getCompositePnlUsdc()), safe(performance.getNetPnlUsdc()),
                safe(performance.getRealizedPnlUsdc()),
                safe(performance.getUnrealizedPnlUsdc()), safe(performance.getPendingSettlementPnlUsdc()),
                safe(performance.getWinRate()), performance.getTotalOrders(),
                ISOLATED_MODES,
                POLYMARKET_CAPABILITIES,
                "ISOLATED"
        );
    }

    private StrategyMetricsView buildPolymarketMetrics(SmartWalletPerformanceView performance,
                                                       SmartWalletPnlSummaryView pnlSummary,
                                                       SmartWalletCapacitySummaryView capacity) {
        BigDecimal tvlUsdc = safe(capacity.getAvailableCapitalUsdc()).add(safe(capacity.getLockedClaimProceedsUsdc()));
        return new StrategyMetricsView(
                performance.getTotalOrders(), performance.getFilledOrders(), performance.getOpenPositions(),
                performance.getPendingPositions(),
                performance.getClosedPositions(), performance.getWinningPositions(), performance.getLosingPositions(),
                safe(performance.getWinRate()), safe(pnlSummary.getTradedVolumeUsdc()), safe(pnlSummary.getFeeUsdc()),
                safe(performance.getRealizedPnlUsdc()), safe(performance.getUnrealizedPnlUsdc()),
                safe(performance.getPendingSettlementPnlUsdc()), safe(performance.getCompositePnlUsdc()),
                safe(performance.getNetPnlUsdc()),
                safe(performance.getProfitFactor()), safe(performance.getMaxDrawdownUsdc()), performance.getConflictingMarkets(),
                performance.getOpenConflictingMarkets(), safe(performance.getConflictNetPnlUsdc()),
                capacity.getRemainingPositionSlots(), safe(capacity.getRemainingCapitalBudgetUsdc()),
                safe(capacity.getAvailableCapitalUsdc()), safe(capacity.getLockedClaimProceedsUsdc()),
                performance.getEarlyExitOrderCount(), safe(performance.getEarlyExitRealizedPnlUsdc()),
                performance.getTakeProfitOrderCount(), safe(performance.getTakeProfitRealizedPnlUsdc()),
                performance.getStopLossOrderCount(), safe(performance.getStopLossRealizedPnlUsdc()),
                tvlUsdc,
                null,
                tvlUsdc,
                null,
                null
        );
    }


    private FundingArbSnapshot loadFundingArbSnapshot() {
        List<SingleExchangeArbitragePosition> positions = singleExchangeArbitragePositionMapper.selectList(
                new LambdaQueryWrapper<SingleExchangeArbitragePosition>()
                        .eq(SingleExchangeArbitragePosition::getStrategyType, FUNDING_ARB_STRATEGY_TYPE)
                        .eq(SingleExchangeArbitragePosition::getExchangeName, FUNDING_ARB_EXCHANGE)
                        .orderByDesc(SingleExchangeArbitragePosition::getEntryTime)
                        .orderByDesc(SingleExchangeArbitragePosition::getId)
        );
        if (positions.isEmpty()) {
            return new FundingArbSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        Set<Long> positionIds = positions.stream()
                .map(SingleExchangeArbitragePosition::getId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        List<SingleExchangeArbitrageOrder> orders = positionIds.isEmpty()
                ? List.of()
                : singleExchangeArbitrageOrderMapper.selectList(
                new LambdaQueryWrapper<SingleExchangeArbitrageOrder>()
                        .in(SingleExchangeArbitrageOrder::getPositionId, positionIds)
                        .orderByDesc(SingleExchangeArbitrageOrder::getPlacedAt)
                        .orderByDesc(SingleExchangeArbitrageOrder::getCreatedAt)
                        .orderByDesc(SingleExchangeArbitrageOrder::getId)
        );

        List<SingleExchangeArbitrageFundingSettlement> settlements = positionIds.isEmpty()
                ? List.of()
                : singleExchangeArbitrageFundingSettlementMapper.selectList(
                new LambdaQueryWrapper<SingleExchangeArbitrageFundingSettlement>()
                        .in(SingleExchangeArbitrageFundingSettlement::getPositionId, positionIds)
                        .orderByDesc(SingleExchangeArbitrageFundingSettlement::getFundingTime)
                        .orderByDesc(SingleExchangeArbitrageFundingSettlement::getId)
        );

        List<SingleExchangeArbitragePosition> openPositions = positions.stream()
                .filter(this::isFundingArbOpenPosition)
                .toList();
        List<SingleExchangeArbitragePosition> closedPositions = positions.stream()
                .filter(position -> "CLOSED".equalsIgnoreCase(position.getStatus()))
                .toList();
        List<SingleExchangeArbitragePosition> nextSettlements = openPositions.stream()
                .sorted(Comparator
                        .comparing((SingleExchangeArbitragePosition position) -> position.getEntryNextFundingTime() == null ? Long.MAX_VALUE : position.getEntryNextFundingTime())
                        .thenComparing(position -> position.getEntryTime() == null ? Long.MAX_VALUE : position.getEntryTime().getTime()))
                .limit(8)
                .toList();
        return new FundingArbSnapshot(positions, openPositions, closedPositions, settlements, orders, nextSettlements);
    }

    private List<SingleExchangeArbitragePosition> loadFundingArbOpenPositions() {
        return loadFundingArbSnapshot().openPositions();
    }

    private List<SingleExchangeArbitragePosition> loadFundingArbNextSettlements(int limit) {
        return loadFundingArbSnapshot().nextSettlements().stream()
                .limit(Math.max(limit, 0))
                .toList();
    }

    private List<SingleExchangeArbitrageOrder> loadFundingArbRecentOrders(int limit) {
        return loadFundingArbSnapshot().orders().stream()
                .limit(Math.max(limit, 0))
                .toList();
    }

    private List<StrategyTimelinePointView> buildFundingArbTimeline(int days) {
        FundingArbSnapshot snapshot = loadFundingArbSnapshot();
        return buildFundingArbTimeline(snapshot, days);
    }

    private List<StrategyTimelinePointView> buildFundingArbTimeline(FundingArbSnapshot snapshot, int days) {
        if (snapshot.positions().isEmpty()) {
            return List.of();
        }
        LocalDate cutoffDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(Math.max(days, 1) - 1L);
        Map<LocalDate, BigDecimal> dailyNetPnl = new TreeMap<>();

        for (SingleExchangeArbitrageFundingSettlement settlement : snapshot.settlements()) {
            LocalDate date = toFundingArbLocalDate(settlement.getFundingTime(), settlement.getCreatedAt());
            if (date == null || date.isBefore(cutoffDate)) {
                continue;
            }
            dailyNetPnl.merge(date, safe(settlement.getSettlementAmount()), BigDecimal::add);
        }
        for (SingleExchangeArbitragePosition position : snapshot.closedPositions()) {
            LocalDate date = toFundingArbLocalDate(position.getExitTime());
            if (date == null || date.isBefore(cutoffDate)) {
                continue;
            }
            dailyNetPnl.merge(date, safe(position.getRealizedPnl()), BigDecimal::add);
        }

        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal cumulativeLoss = BigDecimal.ZERO;
        List<StrategyTimelinePointView> timeline = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : dailyNetPnl.entrySet()) {
            BigDecimal netPnl = safe(entry.getValue());
            BigDecimal lossPnl = netPnl.compareTo(BigDecimal.ZERO) < 0 ? netPnl : BigDecimal.ZERO;
            cumulative = cumulative.add(netPnl);
            cumulativeLoss = cumulativeLoss.add(lossPnl);
            timeline.add(new StrategyTimelinePointView(entry.getKey().toString(), netPnl, cumulative, lossPnl, cumulativeLoss));
        }
        return timeline;
    }

    private StrategyRegistryView registryOrFallback(String strategyCode,
                                                    Supplier<StrategyRegistryView> supplier,
                                                    StrategyRegistryView fallback) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            log.warn("Failed to build {} registry, returning fallback row", strategyCode, ex);
            return fallback;
        }
    }

    private <T> List<T> fundingArbListOrEmpty(String dataSet, Supplier<List<T>> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            log.warn("Failed to load funding-arb {}, returning empty list", dataSet, ex);
            return List.of();
        }
    }

    private StrategyRegistryView fallbackPolymarketRegistry(String mode) {
        return new StrategyRegistryView(
                POLYMARKET_CODE,
                "Polymarket 跟单策略",
                "Polymarket",
                "ACTIVE",
                buildDescription(mode),
                ISOLATED_MODES,
                POLYMARKET_CAPABILITIES,
                "ISOLATED",
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0
        );
    }

    private StrategyRegistryView fallbackFundingArbRegistry(String mode) {
        return new StrategyRegistryView(
                FUNDING_ARB_CODE,
                "资金费套利策略",
                "Funding",
                "ACTIVE",
                buildFundingArbDescription(mode),
                SHARED_MODES,
                FUNDING_ARB_CAPABILITIES,
                "SHARED",
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0
        );
    }

    private StrategySummaryDetailView fallbackFundingArbSummaryDetail(String mode) {
        return new StrategySummaryDetailView(buildFundingArbIdentity(mode), toDashboardMetrics(emptyMetrics()), fallbackFundingArbInsights());
    }

    private List<StrategyInsightView> fallbackFundingArbInsights() {
        return List.of(new StrategyInsightView(
                "WARN",
                "资金费套利数据源暂不可用",
                "当前测试环境无法读取资金费套利库表，已降级为空数据，Polymarket 策略数据不受影响。"
        ));
    }

    private List<StrategyInsightView> buildFundingArbInsights(FundingArbSnapshot snapshot,
                                                              StrategyMetricsView metrics,
                                                              String mode) {
        List<StrategyInsightView> insights = new ArrayList<>();
        if (snapshot.positions().isEmpty()) {
            insights.add(new StrategyInsightView("INFO", "暂无套利持仓历史", "当前还没有单交易所资金费套利的持仓、订单与资金费结算记录。"));
        } else if (metrics.openPositions() != null && metrics.openPositions() > 0) {
            insights.add(new StrategyInsightView("INFO", "当前存在在途套利仓位", "当前共有 " + metrics.openPositions() + " 组现货多 / 合约空持仓仍在持有中。"));
        } else {
            insights.add(new StrategyInsightView("INFO", "当前无在途套利仓位", "当前没有 OPENING、OPENED 或 CLOSING 状态的资金费套利仓位。"));
        }

        if (!snapshot.nextSettlements().isEmpty()) {
            SingleExchangeArbitragePosition next = snapshot.nextSettlements().get(0);
            insights.add(new StrategyInsightView(
                    "INFO",
                    "下一次资金费观察窗口",
                    next.getSymbol() + " 预计北京时间 " + formatEpochMillis(next.getEntryNextFundingTime()) + " 进入下一次资金费结算窗口。"
            ));
        }

        if (metrics.realizedPnlUsdc() != null && metrics.realizedPnlUsdc().compareTo(BigDecimal.ZERO) != 0) {
            insights.add(new StrategyInsightView("INFO", "已实现收益已聚合", "当前已实现 PnL 已合并资金费结算与平仓收益，累计为 " + metrics.realizedPnlUsdc() + " USDT。"));
        }

        long failedPositions = snapshot.positions().stream()
                .filter(position -> "FAILED".equalsIgnoreCase(position.getStatus()))
                .count();
        if (failedPositions > 0) {
            insights.add(new StrategyInsightView("WARN", "存在失败持仓记录", "历史上共有 " + failedPositions + " 条套利持仓进入 FAILED 状态，请结合订单明细排查异常原因。"));
        }

        insights.add(new StrategyInsightView(
                "INFO",
                "当前口径说明",
                "FundingArb 页面当前复用同一套 Binance 单交易所套利库表，PAPER/LIVE 切换不会改变数据范围。"
        ));
        insights.add(new StrategyInsightView(
                "INFO",
                "执行参数基线",
                "当前默认开仓阈值 " + safe(singleExchangeSpotFuturesProperties.getOpenThreshold())
                        + "，默认杠杆 " + singleExchangeSpotFuturesProperties.getLeverage() + "x。"
        ));
        return insights;
    }

    private StrategyMetricsView emptyMetrics() {
        return new StrategyMetricsView(
                0, 0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, BigDecimal.ZERO,
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                BigDecimal.ZERO, null, BigDecimal.ZERO, null, null
        );
    }

    private StrategyDashboardMetricsView toDashboardMetrics(StrategyMetricsView metrics) {
        return new StrategyDashboardMetricsView(
                safe(metrics.tvlUsdc()),
                metrics.todayPnlUsdc(),
                safe(metrics.capitalUsdc()),
                safe(metrics.netPnlUsdc()),
                safe(metrics.realizedPnlUsdc()),
                safe(metrics.pendingSettlementPnlUsdc()),
                metrics.totalOrders() == null ? 0 : metrics.totalOrders(),
                metrics.filledOrders() == null ? 0 : metrics.filledOrders(),
                metrics.openPositions() == null ? 0 : metrics.openPositions(),
                metrics.pendingPositions() == null ? 0 : metrics.pendingPositions(),
                metrics.closedPositions() == null ? 0 : metrics.closedPositions(),
                metrics.remainingPositionSlots() == null ? 0 : metrics.remainingPositionSlots(),
                safe(metrics.winRate()),
                metrics.winningPositions() == null ? 0 : metrics.winningPositions(),
                metrics.losingPositions() == null ? 0 : metrics.losingPositions(),
                safe(metrics.maxDrawdownUsdc())
        );
    }

    private List<StrategyInsightView> buildInsights(StrategyMetricsView metrics, List<SmartWalletPositionView> nextSettlements, String mode) {
        List<StrategyInsightView> insights = new ArrayList<>();
        BigDecimal remainingCapital = metrics.remainingCapitalBudgetUsdc() == null ? BigDecimal.ZERO : metrics.remainingCapitalBudgetUsdc();
        if (remainingCapital.compareTo(BigDecimal.valueOf(100)) < 0) {
            insights.add(new StrategyInsightView("WARN", "资金预算偏紧", "剩余可用策略预算较低，建议优先等待结算或调低新开仓节奏。"));
        }
        if (metrics.openConflictingMarkets() != null && metrics.openConflictingMarkets() > 0) {
            insights.add(new StrategyInsightView("WARN", "存在冲突市场", "同一市场持有多个 outcome 会拖低胜率，建议优先检查冲突持仓。"));
        }
        if (metrics.maxDrawdownUsdc() != null && metrics.maxDrawdownUsdc().compareTo(BigDecimal.valueOf(300)) > 0) {
            insights.add(new StrategyInsightView("WARN", "回撤偏高", "当前策略回撤较高，建议收紧信号阈值或减少同时持仓数。"));
        }
        if (metrics.pendingSettlementPnlUsdc() != null && metrics.pendingSettlementPnlUsdc().compareTo(BigDecimal.ZERO) > 0) {
            insights.add(new StrategyInsightView("WARN", "待结算估值未确认", "部分仓位已过预计结算时间，但 winner 尚未确认；这部分金额当前只是估值，不应视为真实落袋收益。"));
        }
        if (!nextSettlements.isEmpty()) {
            SmartWalletPositionView next = nextSettlements.get(0);
            insights.add(new StrategyInsightView("INFO", "待结算处理", next.getQuestion() + "，预计北京时间 " + next.getMarketEndAtBjt() + "。"));
        } else {
            insights.add(new StrategyInsightView("INFO", "暂无待结算处理项", "当前没有已到结算窗口但仍需处理的仓位。"));
        }
        if (metrics.winRate() != null && metrics.winRate().compareTo(BigDecimal.ZERO) == 0
                && metrics.closedPositions() != null && metrics.closedPositions() == 0) {
            insights.add(new StrategyInsightView("INFO", "当前胜率尚未成型", "暂时没有已结算仓位，胜率需要等待真实结算后再判断。"));
        }
        if ("LIVE".equalsIgnoreCase(mode)) {
            if ((metrics.totalOrders() == null || metrics.totalOrders() == 0)
                    && (metrics.openPositions() == null || metrics.openPositions() == 0)
                    && (metrics.closedPositions() == null || metrics.closedPositions() == 0)) {
                insights.add(new StrategyInsightView("INFO", "暂无真实交易数据", "当前 LIVE 口径还没有真实订单、持仓和收益时间线；切回 PAPER 可查看现有模拟数据。"));
            }
            insights.add(new StrategyInsightView("INFO", "当前查看真实仓", "页面已切到 LIVE 口径，指标、持仓、订单和时间线都只统计真实执行钱包。"));
        } else if ("PAPER".equalsIgnoreCase(mode)) {
            insights.add(new StrategyInsightView("INFO", "当前查看模拟仓", "页面已切到 PAPER 口径，指标、持仓、订单和时间线都只统计 paper-book 仓位。"));
        }
        return insights;
    }

    private String buildDescription(String mode) {
        if ("LIVE".equalsIgnoreCase(mode)) {
            return "基于 smart wallet 的 Polymarket 真实持仓与风控策略视图。";
        }
        if ("PAPER".equalsIgnoreCase(mode)) {
            return "基于 smart wallet 的 Polymarket 模拟持仓与风控策略视图。";
        }
        return "基于 smart wallet 的 Polymarket 跟单与风控策略。";
    }

    private StrategyPositionRowView toPosition(SmartWalletPositionView row) {
        return new StrategyPositionRowView(row.getId(), row.getMarketId(), row.getQuestion(), row.getOutcome(), row.getStatus(),
                row.getQuantity(), row.getAverageEntryPrice(), row.getCostBasisUsdc(), row.getOriginalCostBasisUsdc(),
                row.getRealizedPnlUsdc(), row.getUnrealizedPnlUsdc(), row.getPendingSettlementNetPnlUsdc(),
                row.getClaimableProceedsUsdc(), row.getMarketEndAtBjt());
    }

    private StrategyOrderRowView toOrder(SmartWalletOrderView row) {
        return new StrategyOrderRowView(row.getId(), row.getFollowTaskId(), row.getExecutionMode(), row.getQuestion(),
                row.getMarketSlug(), row.getOutcome(), row.getSide(), row.getOrderType(), row.getEarlyExit(), row.getExitReason(),
                row.getStatus(), row.getPrice(), row.getSize(),
                row.getNotionalUsdc(), row.getCostBasisUsdc(), row.getRealizedPnlUsdc(), row.getEstimatedFeeUsdc(), row.getEstimatedSlippageUsdc(),
                row.getFollowProbability(), row.getCalibratedFollowProbability(), row.getSlippageSpikeProbability(), row.getEstimateSampleSize(),
                row.getMarketEndAtBjt(), row.getPlacedAt() == null ? null : row.getPlacedAt().toString());
    }

    private StrategyPositionRowView toFundingArbPosition(SingleExchangeArbitragePosition row) {
        return new StrategyPositionRowView(
                row.getId(),
                row.getPositionCode(),
                row.getSymbol() + " @" + (row.getExchangeName() == null ? FUNDING_ARB_EXCHANGE : row.getExchangeName()),
                "SPOT LONG / FUTURES SHORT",
                row.getStatus(),
                row.getQuantity(),
                safe(row.getEntrySpotPrice()),
                resolveFundingArbCostBasis(row),
                resolveFundingArbCostBasis(row),
                resolveFundingArbPositionRealized(row),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                formatEpochMillis(row.getEntryNextFundingTime())
        );
    }

    private StrategyOrderRowView toFundingArbOrder(SingleExchangeArbitrageOrder row) {
        return new StrategyOrderRowView(
                row.getId(),
                row.getPositionId(),
                buildFundingArbExecutionMode(row),
                row.getSymbol(),
                null,
                buildFundingArbOrderOutcome(row),
                row.getSide(),
                row.getOrderType(),
                false,
                row.getErrorMessage(),
                row.getStatus(),
                resolveFundingArbOrderPrice(row),
                resolveFundingArbOrderSize(row),
                resolveFundingArbOrderNotional(row),
                BigDecimal.ZERO,
                safe(row.getFeeAmount()),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null,
                null,
                row.getClientOrderId() == null ? 0 : 1,
                null,
                formatDate(row.getPlacedAt() == null ? row.getCreatedAt() : row.getPlacedAt())
        );
    }

    private StrategyTimelinePointView toTimeline(SmartWalletPnlTimelinePoint row) {
        return new StrategyTimelinePointView(row.getBucketAt() == null ? null : row.getBucketAt().toString(),
                row.getNetPnlUsdc(), row.getCumulativeNetPnlUsdc(), row.getLossPnlUsdc(), row.getCumulativeLossPnlUsdc());
    }

    private StrategyConflictRowView toConflict(SmartWalletConflictMarketView row) {
        return new StrategyConflictRowView(row.getMarketId(), row.getQuestion(), row.getOutcomes(), row.getOpenPositionCount(),
                row.getNetPnlUsdc(), row.getMarketEndAtBjt());
    }

    private String buildFundingArbDescription(String mode) {
        if ("LIVE".equalsIgnoreCase(mode) || "PAPER".equalsIgnoreCase(mode)) {
            return "Binance 单交易所现货多 / 合约空资金费套利视图；当前 PAPER/LIVE 共用同一套利数据集。";
        }
        return "Binance 单交易所现货多 / 合约空资金费套利视图。";
    }

    private List<StrategyFactorView> buildPolymarketFactors(String mode, String window) {
        SmartWalletPerformanceView performance = accountingService.performance(mode);
        SmartWalletCapacitySummaryView capacity = accountingService.capacitySummary(mode);
        List<SmartWalletOrderView> orders = accountingService.listOrders(100, mode);
        List<SmartWalletConflictMarketView> conflicts = accountingService.conflictingMarkets(20, false, mode);
        BigDecimal avgFollowProbability = average(orders, SmartWalletOrderView::getCalibratedFollowProbability);
        BigDecimal avgSlippage = average(orders, SmartWalletOrderView::getEstimatedSlippageUsdc);
        int sampleSize = performance.getTotalOrders() == null ? orders.size() : performance.getTotalOrders();
        String generatedAt = BJT_FORMATTER.format(Instant.now());

        return List.of(
                factorView(
                        "WALLET_QUALITY",
                        "钱包质量",
                        "SOURCE",
                        "ACTIVE",
                        "POSITIVE",
                        new BigDecimal("0.28"),
                        generatedAt,
                        safe(performance.getWinRate()),
                        percentDisplay(safe(performance.getWinRate())),
                        scoreFromRatio(safe(performance.getWinRate())),
                        signalFromRatio(safe(performance.getWinRate()), new BigDecimal("0.55"), new BigDecimal("0.45")),
                        new BigDecimal("0.08"),
                        new BigDecimal("0.11"),
                        new BigDecimal("0.72"),
                        coverage(performance.getFilledOrders(), performance.getTotalOrders()),
                        sampleSize,
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "winningPositions", performance.getWinningPositions(), "losingPositions", performance.getLosingPositions())
                ),
                factorView(
                        "FOLLOW_PROBABILITY",
                        "跟随概率",
                        "SIGNAL",
                        "ACTIVE",
                        "POSITIVE",
                        new BigDecimal("0.22"),
                        generatedAt,
                        avgFollowProbability,
                        percentDisplay(avgFollowProbability),
                        scoreFromRatio(avgFollowProbability),
                        signalFromRatio(avgFollowProbability, new BigDecimal("0.60"), new BigDecimal("0.45")),
                        new BigDecimal("0.06"),
                        new BigDecimal("0.09"),
                        new BigDecimal("0.58"),
                        coverage(orders.size(), Math.max(sampleSize, orders.size())),
                        orders.size(),
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "source", "recentOrders.calibratedFollowProbability")
                ),
                factorView(
                        "SLIPPAGE_SPIKE",
                        "滑点冲击",
                        "EXECUTION",
                        "ACTIVE",
                        "NEGATIVE",
                        new BigDecimal("0.16"),
                        generatedAt,
                        avgSlippage,
                        moneyDisplay(avgSlippage),
                        inverseMoneyScore(avgSlippage, new BigDecimal("5")),
                        avgSlippage.compareTo(new BigDecimal("5")) > 0 ? "RISK" : "STABLE",
                        new BigDecimal("-0.04"),
                        new BigDecimal("-0.05"),
                        new BigDecimal("-0.32"),
                        coverage(orders.size(), Math.max(sampleSize, orders.size())),
                        orders.size(),
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "source", "recentOrders.estimatedSlippageUsdc")
                ),
                factorView(
                        "MARKET_CONCENTRATION",
                        "市场集中度",
                        "RISK",
                        "ACTIVE",
                        "NEGATIVE",
                        new BigDecimal("0.18"),
                        generatedAt,
                        BigDecimal.valueOf(performance.getOpenConflictingMarkets() == null ? 0 : performance.getOpenConflictingMarkets()),
                        String.valueOf(performance.getOpenConflictingMarkets() == null ? 0 : performance.getOpenConflictingMarkets()),
                        inverseCountScore(performance.getOpenConflictingMarkets(), 5),
                        performance.getOpenConflictingMarkets() != null && performance.getOpenConflictingMarkets() > 0 ? "WATCH" : "CLEAR",
                        new BigDecimal("-0.03"),
                        new BigDecimal("-0.04"),
                        new BigDecimal("-0.26"),
                        coverage(conflicts.size(), Math.max(1, performance.getOpenPositions() == null ? 0 : performance.getOpenPositions())),
                        conflicts.size(),
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "conflictingMarkets", performance.getConflictingMarkets(), "conflictNetPnlUsdc", safe(performance.getConflictNetPnlUsdc()))
                ),
                factorView(
                        "CAPITAL_ROOM",
                        "资金容量",
                        "RISK",
                        "ACTIVE",
                        "POSITIVE",
                        new BigDecimal("0.16"),
                        generatedAt,
                        safe(capacity.getAvailableCapitalUsdc()),
                        moneyDisplay(safe(capacity.getAvailableCapitalUsdc())),
                        cappedMoneyScore(safe(capacity.getAvailableCapitalUsdc()), new BigDecimal("1000")),
                        safe(capacity.getAvailableCapitalUsdc()).compareTo(new BigDecimal("100")) < 0 ? "TIGHT" : "AVAILABLE",
                        new BigDecimal("0.02"),
                        new BigDecimal("0.03"),
                        new BigDecimal("0.20"),
                        BigDecimal.ONE,
                        sampleSize,
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "remainingPositionSlots", capacity.getRemainingPositionSlots(), "remainingCapitalBudgetUsdc", safe(capacity.getRemainingCapitalBudgetUsdc()))
                )
        );
    }

    private List<StrategyFactorView> buildFundingArbFactors(String mode, String window) {
        FundingArbSnapshot snapshot = loadFundingArbSnapshot();
        String generatedAt = BJT_FORMATTER.format(Instant.now());
        BigDecimal avgFundingRate = average(snapshot.settlements(), SingleExchangeArbitrageFundingSettlement::getFundingRate);
        BigDecimal avgEntryBasisRate = average(snapshot.positions(), SingleExchangeArbitragePosition::getEntryBasisRate);
        BigDecimal avgFee = average(snapshot.orders(), SingleExchangeArbitrageOrder::getFeeAmount);
        BigDecimal avgLeverage = averageInteger(snapshot.positions(), SingleExchangeArbitragePosition::getLeverage);
        BigDecimal failedRatio = snapshot.positions().isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(snapshot.positions().stream().filter(position -> "FAILED".equalsIgnoreCase(position.getStatus())).count())
                .divide(BigDecimal.valueOf(snapshot.positions().size()), 4, RoundingMode.HALF_UP);

        return List.of(
                factorView(
                        "FUNDING_RATE",
                        "资金费率",
                        "CARRY",
                        "ACTIVE",
                        "POSITIVE",
                        new BigDecimal("0.30"),
                        generatedAt,
                        avgFundingRate,
                        percentDisplay(avgFundingRate),
                        scoreFromRatio(avgFundingRate.multiply(BigDecimal.valueOf(100))),
                        avgFundingRate.compareTo(BigDecimal.ZERO) > 0 ? "EARN" : "NEUTRAL",
                        new BigDecimal("0.10"),
                        new BigDecimal("0.13"),
                        new BigDecimal("0.80"),
                        coverage(snapshot.settlements().size(), Math.max(1, snapshot.positions().size())),
                        snapshot.settlements().size(),
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "modeBehavior", "SHARED", "source", "funding settlements")
                ),
                factorView(
                        "BASIS_SPREAD",
                        "基差率",
                        "PRICING",
                        "ACTIVE",
                        "POSITIVE",
                        new BigDecimal("0.24"),
                        generatedAt,
                        avgEntryBasisRate,
                        percentDisplay(avgEntryBasisRate),
                        scoreFromRatio(avgEntryBasisRate.abs().multiply(BigDecimal.valueOf(20))),
                        avgEntryBasisRate.abs().compareTo(new BigDecimal("0.002")) > 0 ? "WIDE" : "NORMAL",
                        new BigDecimal("0.07"),
                        new BigDecimal("0.09"),
                        new BigDecimal("0.62"),
                        coverage(snapshot.positions().size(), Math.max(1, snapshot.positions().size())),
                        snapshot.positions().size(),
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "modeBehavior", "SHARED", "openPositions", snapshot.openPositions().size())
                ),
                factorView(
                        "EXECUTION_COST",
                        "执行成本",
                        "EXECUTION",
                        "ACTIVE",
                        "NEGATIVE",
                        new BigDecimal("0.18"),
                        generatedAt,
                        avgFee,
                        moneyDisplay(avgFee),
                        inverseMoneyScore(avgFee, new BigDecimal("3")),
                        avgFee.compareTo(new BigDecimal("3")) > 0 ? "COSTLY" : "STABLE",
                        new BigDecimal("-0.04"),
                        new BigDecimal("-0.05"),
                        new BigDecimal("-0.34"),
                        coverage(snapshot.orders().size(), Math.max(1, snapshot.positions().size() * 2)),
                        snapshot.orders().size(),
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "modeBehavior", "SHARED", "filledOrders", snapshot.orders().stream().filter(this::isFundingArbFilledOrder).count())
                ),
                factorView(
                        "LEVERAGE_RISK",
                        "杠杆风险",
                        "RISK",
                        "ACTIVE",
                        "NEGATIVE",
                        new BigDecimal("0.16"),
                        generatedAt,
                        avgLeverage,
                        avgLeverage.setScale(2, RoundingMode.HALF_UP) + "x",
                        inverseCountScore(avgLeverage.intValue(), 8),
                        avgLeverage.compareTo(BigDecimal.valueOf(5)) > 0 ? "ELEVATED" : "CONTROLLED",
                        new BigDecimal("-0.02"),
                        new BigDecimal("-0.03"),
                        new BigDecimal("-0.18"),
                        coverage(snapshot.openPositions().size(), Math.max(1, snapshot.positions().size())),
                        snapshot.openPositions().size(),
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "modeBehavior", "SHARED", "nextSettlements", snapshot.nextSettlements().size())
                ),
                factorView(
                        "EXCHANGE_HEALTH",
                        "交易所状态",
                        "RISK",
                        "ACTIVE",
                        "NEGATIVE",
                        new BigDecimal("0.12"),
                        generatedAt,
                        failedRatio,
                        percentDisplay(failedRatio),
                        inverseRatioScore(failedRatio),
                        failedRatio.compareTo(new BigDecimal("0.05")) > 0 ? "DEGRADED" : "HEALTHY",
                        new BigDecimal("-0.03"),
                        new BigDecimal("-0.04"),
                        new BigDecimal("-0.22"),
                        coverage(snapshot.orders().size(), Math.max(1, snapshot.positions().size() * 2)),
                        snapshot.positions().size(),
                        metadata("window", window, "mode", normalizeModeForDisplay(mode), "modeBehavior", "SHARED", "exchange", FUNDING_ARB_EXCHANGE)
                )
        );
    }

    private StrategyFactorView factorView(String code,
                                          String name,
                                          String category,
                                          String status,
                                          String direction,
                                          BigDecimal weight,
                                          String updatedAtBjt,
                                          BigDecimal value,
                                          String displayValue,
                                          BigDecimal score,
                                          String signal,
                                          BigDecimal ic,
                                          BigDecimal rankIc,
                                          BigDecimal ir,
                                          BigDecimal coverage,
                                          Integer sampleSize,
                                          Map<String, Object> metadata) {
        return new StrategyFactorView(code, name, category, status, direction, safe(weight), updatedAtBjt,
                safe(value), displayValue, safe(score), signal, safe(ic), safe(rankIc), safe(ir), safe(coverage),
                sampleSize == null ? 0 : sampleSize, metadata == null ? Map.of() : metadata);
    }

    private StrategySummaryView placeholder(String code, String name, String category, String status, String description) {
        return new StrategySummaryView(code, name, category, status, description, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                PLANNED_MODES, PLANNED_CAPABILITIES, "UNSUPPORTED");
    }

    private StrategyRegistryView placeholderRegistry(String code, String name, String category, String status, String description) {
        return new StrategyRegistryView(code, name, category, status, description,
                PLANNED_MODES, PLANNED_CAPABILITIES, "UNSUPPORTED",
                0, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private StrategyIdentityView placeholderIdentity(String code, String name, String category, String status, String description) {
        return new StrategyIdentityView(code, name, category, status, description,
                PLANNED_MODES, PLANNED_CAPABILITIES, "UNSUPPORTED");
    }

    private <T> BigDecimal average(List<T> rows, Function<T, BigDecimal> mapper) {
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> values = rows.stream()
                .map(mapper)
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private <T> BigDecimal averageInteger(List<T> rows, Function<T, Integer> mapper) {
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<Integer> values = rows.stream()
                .map(mapper)
                .filter(Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int sum = values.stream().mapToInt(Integer::intValue).sum();
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal coverage(Integer numerator, Integer denominator) {
        int safeNumerator = numerator == null ? 0 : Math.max(numerator, 0);
        int safeDenominator = denominator == null ? 0 : Math.max(denominator, 0);
        if (safeDenominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(safeNumerator).divide(BigDecimal.valueOf(safeDenominator), 4, RoundingMode.HALF_UP);
    }

    private String percentDisplay(BigDecimal value) {
        return safe(value).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private String moneyDisplay(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP) + " USDC";
    }

    private BigDecimal scoreFromRatio(BigDecimal value) {
        return safe(value).multiply(BigDecimal.valueOf(100)).max(BigDecimal.ZERO).min(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal inverseRatioScore(BigDecimal value) {
        return BigDecimal.valueOf(100).subtract(scoreFromRatio(value)).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal inverseMoneyScore(BigDecimal value, BigDecimal cautionLevel) {
        BigDecimal level = safe(cautionLevel).compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ONE : safe(cautionLevel);
        BigDecimal ratio = safe(value).divide(level, 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(scoreFromRatio(ratio)).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal cappedMoneyScore(BigDecimal value, BigDecimal fullScoreAmount) {
        BigDecimal amount = safe(fullScoreAmount).compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ONE : safe(fullScoreAmount);
        return scoreFromRatio(safe(value).divide(amount, 4, RoundingMode.HALF_UP));
    }

    private BigDecimal inverseCountScore(Integer value, int cautionLevel) {
        int level = Math.max(cautionLevel, 1);
        BigDecimal ratio = BigDecimal.valueOf(value == null ? 0 : Math.max(value, 0))
                .divide(BigDecimal.valueOf(level), 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(scoreFromRatio(ratio)).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private String signalFromRatio(BigDecimal value, BigDecimal high, BigDecimal low) {
        BigDecimal safeValue = safe(value);
        if (safeValue.compareTo(high) >= 0) {
            return "STRONG";
        }
        if (safeValue.compareTo(low) <= 0) {
            return "WEAK";
        }
        return "NEUTRAL";
    }

    private String normalizeModeForDisplay(String mode) {
        return mode == null || mode.isBlank() ? "PAPER" : mode.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> metadata(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (entries == null) {
            return map;
        }
        for (int index = 0; index + 1 < entries.length; index += 2) {
            Object key = entries[index];
            if (key == null) {
                continue;
            }
            map.put(String.valueOf(key), entries[index + 1]);
        }
        return map;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isFundingArbOpenPosition(SingleExchangeArbitragePosition position) {
        String status = position.getStatus();
        return "OPENING".equalsIgnoreCase(status)
                || "OPENED".equalsIgnoreCase(status)
                || "CLOSING".equalsIgnoreCase(status);
    }

    private boolean isFundingArbFilledOrder(SingleExchangeArbitrageOrder order) {
        String status = order.getStatus();
        return "FILLED".equalsIgnoreCase(status) || "PARTIALLY_FILLED".equalsIgnoreCase(status);
    }

    private BigDecimal resolveFundingArbPositionRealized(SingleExchangeArbitragePosition position) {
        return safe(position.getRealizedPnl()).add(safe(position.getFundingFeesEarned()));
    }

    private BigDecimal resolveFundingArbCostBasis(SingleExchangeArbitragePosition position) {
        BigDecimal totalInvestment = safe(position.getTotalInvestment());
        if (totalInvestment.compareTo(BigDecimal.ZERO) > 0) {
            return totalInvestment;
        }
        return safe(position.getSpotNotional()).add(safe(position.getFuturesNotional()));
    }

    private BigDecimal resolveFundingArbOrderPrice(SingleExchangeArbitrageOrder order) {
        BigDecimal avgPrice = safe(order.getAvgPrice());
        return avgPrice.compareTo(BigDecimal.ZERO) > 0 ? avgPrice : safe(order.getRequestedPrice());
    }

    private BigDecimal resolveFundingArbOrderSize(SingleExchangeArbitrageOrder order) {
        BigDecimal executedQty = safe(order.getExecutedQty());
        return executedQty.compareTo(BigDecimal.ZERO) > 0 ? executedQty : safe(order.getRequestedQty());
    }

    private BigDecimal resolveFundingArbOrderNotional(SingleExchangeArbitrageOrder order) {
        BigDecimal quoteQty = safe(order.getQuoteQty());
        if (quoteQty.compareTo(BigDecimal.ZERO) > 0) {
            return quoteQty;
        }
        return resolveFundingArbOrderPrice(order).multiply(resolveFundingArbOrderSize(order));
    }

    private String buildFundingArbExecutionMode(SingleExchangeArbitrageOrder order) {
        String marketType = order.getMarketType() == null ? "UNKNOWN" : order.getMarketType();
        String phase = order.getTradePhase() == null ? "TRADE" : order.getTradePhase();
        return marketType + "_" + phase;
    }

    private String buildFundingArbOrderOutcome(SingleExchangeArbitrageOrder order) {
        String hedgeSide = order.getHedgeSide() == null ? "-" : order.getHedgeSide();
        String positionSide = order.getPositionSide() == null ? "-" : order.getPositionSide();
        return hedgeSide + " / " + positionSide;
    }

    private LocalDate toFundingArbLocalDate(Long epochMillis, Date fallback) {
        if (epochMillis != null && epochMillis > 0) {
            return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        }
        return toFundingArbLocalDate(fallback);
    }

    private LocalDate toFundingArbLocalDate(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    private String formatEpochMillis(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }
        return BJT_FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    private String formatDate(Date date) {
        if (date == null) {
            return null;
        }
        return BJT_FORMATTER.format(date.toInstant());
    }

    private <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> mapper) {
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return rows.stream()
                .map(mapper)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateMaxDrawdown(List<StrategyTimelinePointView> timeline) {
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (StrategyTimelinePointView point : timeline) {
            BigDecimal cumulative = safe(point.cumulativeNetPnlUsdc());
            if (cumulative.compareTo(peak) > 0) {
                peak = cumulative;
            }
            BigDecimal drawdown = peak.subtract(cumulative);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }
        return maxDrawdown;
    }

    private record FundingArbSnapshot(
            List<SingleExchangeArbitragePosition> positions,
            List<SingleExchangeArbitragePosition> openPositions,
            List<SingleExchangeArbitragePosition> closedPositions,
            List<SingleExchangeArbitrageFundingSettlement> settlements,
            List<SingleExchangeArbitrageOrder> orders,
            List<SingleExchangeArbitragePosition> nextSettlements
    ) {
    }
}
