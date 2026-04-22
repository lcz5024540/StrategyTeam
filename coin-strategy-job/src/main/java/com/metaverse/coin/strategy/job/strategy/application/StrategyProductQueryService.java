package com.metaverse.coin.strategy.job.strategy.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.HedgeOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.HedgePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgeOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.HedgePositionEntity;
import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.hedge.service.GlobalRiskGuard;
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
import com.metaverse.coin.strategy.job.strategy.model.StrategyInsightView;
import com.metaverse.coin.strategy.job.strategy.model.StrategySummaryDetailView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyMetricsView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategySummaryView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyTimelinePointView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StrategyProductQueryService {

    private static final String POLYMARKET_CODE = "POLYMARKET_COPY";
    private static final String HEDGE_CODE = "HEDGE";
    private static final DateTimeFormatter BJT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final SmartWalletAccountingService accountingService;
    private final HedgePositionMapper hedgePositionMapper;
    private final HedgeOrderMapper hedgeOrderMapper;
    private final HedgeProperties hedgeProperties;
    private final GlobalRiskGuard globalRiskGuard;

    public StrategyProductQueryService(SmartWalletAccountingService accountingService,
                                       HedgePositionMapper hedgePositionMapper,
                                       HedgeOrderMapper hedgeOrderMapper,
                                       HedgeProperties hedgeProperties,
                                       GlobalRiskGuard globalRiskGuard) {
        this.accountingService = accountingService;
        this.hedgePositionMapper = hedgePositionMapper;
        this.hedgeOrderMapper = hedgeOrderMapper;
        this.hedgeProperties = hedgeProperties;
        this.globalRiskGuard = globalRiskGuard;
    }

    public StrategyDashboardOverviewView overview() {
        return overview(null);
    }

    public StrategyDashboardOverviewView overview(String mode) {
        StrategySummaryDetailView polymarket = summaryDetail(POLYMARKET_CODE, mode);
        StrategySummaryDetailView hedge = summaryDetail(HEDGE_CODE, mode);
        List<StrategySummaryView> strategies = new ArrayList<>();
        strategies.add(polymarket.summary());
        strategies.add(hedge.summary());
        strategies.add(placeholder("ROBOT", "机器人策略", "机器人", "PLANNED", "后续用于事件驱动机器人、盘口套利和自动化规则执行。"));
        strategies.add(placeholder("INTELLIGENT", "智能策略", "智能", "PLANNED", "后续用于模型评分、智能分层择时和多因子调度。"));
        return new StrategyDashboardOverviewView(BJT_FORMATTER.format(Instant.now()), POLYMARKET_CODE, strategies);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 拆分子查询方法 —— 供拆分后的子接口独立调用
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 策略概要（summary + metrics + insights），不含列表数据，首屏快速加载。
     */
    public StrategySummaryDetailView summaryDetail(String strategyCode, String mode) {
        if (POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            return buildPolymarketSummaryDetail(mode);
        }
        if (HEDGE_CODE.equalsIgnoreCase(strategyCode)) {
            return buildHedgeSummaryDetail(mode);
        }
        StrategySummaryView summary = placeholder(strategyCode, strategyCode, "待扩展", "PLANNED", "当前策略尚未接入实时数据。");
        return new StrategySummaryDetailView(
                summary,
                emptyMetrics(),
                List.of(new StrategyInsightView("INFO", "策略待接入", "当前页面结构已预留，后续可继续接入真实策略数据。"))
        );
    }

    /**
     * 策略持仓列表（当前活跃持仓）。
     */
    public List<StrategyPositionRowView> positions(String strategyCode, String mode) {
        if (POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            return accountingService.listActivePositions(mode).stream().map(this::toPosition).toList();
        }
        if (HEDGE_CODE.equalsIgnoreCase(strategyCode)) {
            return hedgePositionMapper.selectList(new LambdaQueryWrapper<HedgePositionEntity>()
                    .eq(HedgePositionEntity::getStatus, "OPEN")
                    .orderByDesc(HedgePositionEntity::getOpenedAt)
                    .orderByDesc(HedgePositionEntity::getId))
                    .stream().map(this::toHedgePosition).toList();
        }
        return List.of();
    }

    /**
     * 待结算持仓列表。
     */
    public List<StrategyPositionRowView> nextSettlements(String strategyCode, int limit, String mode) {
        if (POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            return accountingService.listNextSettlements(limit, mode).stream().map(this::toPosition).toList();
        }
        return List.of();
    }

    /**
     * 最近订单列表。
     */
    public List<StrategyOrderRowView> recentOrders(String strategyCode, int limit, String mode) {
        if (POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            return accountingService.listOrders(limit, mode).stream().map(this::toOrder).toList();
        }
        return List.of();
    }

    /**
     * 收益时间线。
     */
    public List<StrategyTimelinePointView> timeline(String strategyCode, int days, String mode) {
        if (POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            return accountingService.pnlTimeline(days, mode).stream().map(this::toTimeline).toList();
        }
        return List.of();
    }

    /**
     * 冲突市场列表。
     */
    public List<StrategyConflictRowView> conflicts(String strategyCode, int limit, String mode) {
        if (POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            return accountingService.conflictingMarkets(limit, false, mode).stream().map(this::toConflict).toList();
        }
        return List.of();
    }

    // ──────────────────────────────────────────────────────────────────────────

    public StrategyDetailView detail(String strategyCode) {
        return detail(strategyCode, null);
    }

    /** @deprecated 使用拆分后的子接口替代全量查询 */
    @Deprecated
    public StrategyDetailView detail(String strategyCode, String mode) {
        if (POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            return buildPolymarketDetail(mode);
        }
        if (HEDGE_CODE.equalsIgnoreCase(strategyCode)) {
            return buildHedgeDetail(mode);
        }
        if (!POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            StrategySummaryView summary = placeholder(strategyCode, strategyCode, "待扩展", "PLANNED", "当前策略尚未接入实时数据。");
            return new StrategyDetailView(summary,
                    new StrategyMetricsView(0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO,
                            0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO),
                    List.of(new StrategyInsightView("INFO", "策略待接入", "当前页面结构已预留，后续可继续接入真实策略数据。")),
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return buildPolymarketDetail(mode);
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
        StrategySummaryView summary = buildPolymarketSummary(mode, performance);
        StrategyMetricsView metrics = buildPolymarketMetrics(performance, pnlSummary, capacity);
        return new StrategySummaryDetailView(summary, metrics, buildInsights(metrics, nextSettlements, mode));
    }

    private StrategySummaryView buildPolymarketSummary(String mode, SmartWalletPerformanceView performance) {
        return new StrategySummaryView(
                POLYMARKET_CODE, "Polymarket 跟单策略", "Polymarket", "ACTIVE",
                buildDescription(mode),
                performance.getOpenPositions(), safe(performance.getCompositePnlUsdc()), safe(performance.getNetPnlUsdc()),
                safe(performance.getRealizedPnlUsdc()),
                safe(performance.getUnrealizedPnlUsdc()), safe(performance.getPendingSettlementPnlUsdc()),
                safe(performance.getWinRate()), performance.getTotalOrders()
        );
    }

    private StrategyMetricsView buildPolymarketMetrics(SmartWalletPerformanceView performance,
                                                       SmartWalletPnlSummaryView pnlSummary,
                                                       SmartWalletCapacitySummaryView capacity) {
        return new StrategyMetricsView(
                performance.getTotalOrders(), performance.getFilledOrders(), performance.getOpenPositions(),
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
                performance.getStopLossOrderCount(), safe(performance.getStopLossRealizedPnlUsdc())
        );
    }

    private StrategyDetailView buildHedgeDetail(String mode) {
        List<HedgePositionEntity> allPositions = hedgePositionMapper.selectList(new LambdaQueryWrapper<HedgePositionEntity>()
                .orderByDesc(HedgePositionEntity::getOpenedAt)
                .orderByDesc(HedgePositionEntity::getId));
        List<HedgePositionEntity> openPositions = allPositions.stream()
                .filter(position -> "OPEN".equalsIgnoreCase(position.getStatus()))
                .toList();
        List<HedgePositionEntity> unresolvedPositions = allPositions.stream()
                .filter(this::isHedgeUnresolved)
                .toList();
        List<HedgePositionEntity> settledPositions = allPositions.stream()
                .filter(position -> "SETTLED".equalsIgnoreCase(position.getStatus()))
                .toList();
        List<HedgePositionEntity> settlementCandidates = allPositions.stream()
                .filter(this::isHedgeSettlementCandidate)
                .sorted(Comparator
                        .comparing((HedgePositionEntity position) -> position.getMarketEndAt() == null ? Long.MAX_VALUE : position.getMarketEndAt())
                        .thenComparing(position -> position.getOpenedAt() == null ? Long.MAX_VALUE : position.getOpenedAt().getTime()))
                .limit(8)
                .toList();
        List<HedgePositionEntity> recentPositions = allPositions.stream()
                .limit(30)
                .toList();
        Map<Long, List<HedgeOrderEntity>> orderMap = loadHedgeOrdersByPositionId(recentPositions);

        BigDecimal realizedPnl = sum(settledPositions, HedgePositionEntity::getRealizedPnlUsdc);
        BigDecimal pendingSettlementPnl = sum(unresolvedPositions, HedgePositionEntity::getExpectedProfitUsdc);
        BigDecimal compositePnl = realizedPnl.add(pendingSettlementPnl);
        BigDecimal tradedVolume = sum(allPositions, HedgePositionEntity::getTotalCostUsdc);
        BigDecimal feeUsdc = sum(settledPositions, HedgePositionEntity::getFeeUsdc);
        BigDecimal lockedClaimProceeds = settlementCandidates.stream()
                .map(this::resolveHedgeClaimableProceeds)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int closedPositions = settledPositions.size();
        int winningPositions = (int) settledPositions.stream()
                .filter(position -> safe(position.getRealizedPnlUsdc()).compareTo(BigDecimal.ZERO) > 0)
                .count();
        int losingPositions = (int) settledPositions.stream()
                .filter(position -> safe(position.getRealizedPnlUsdc()).compareTo(BigDecimal.ZERO) < 0)
                .count();
        int cancelledPositions = (int) allPositions.stream()
                .filter(position -> "CANCELLED".equalsIgnoreCase(position.getStatus()))
                .count();
        BigDecimal winRate = closedPositions == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(winningPositions)
                .divide(BigDecimal.valueOf(closedPositions), 4, RoundingMode.HALF_UP);

        GlobalRiskGuard.GlobalExposureSummary exposure = globalRiskGuard.getSummary();
        BigDecimal hedgeRemainingBudget = positiveOrZero(safe(hedgeProperties.getMaxTotalExposureUsdc()).subtract(safe(exposure.hedgeExposureUsdc())));
        BigDecimal globalRemainingBudget = safe(hedgeProperties.getGlobalMaxTotalExposureUsdc()).compareTo(BigDecimal.ZERO) > 0
                ? positiveOrZero(safe(hedgeProperties.getGlobalMaxTotalExposureUsdc()).subtract(safe(exposure.totalExposureUsdc())))
                : hedgeRemainingBudget;
        BigDecimal availableCapital = safe(hedgeProperties.getGlobalMaxTotalExposureUsdc()).compareTo(BigDecimal.ZERO) > 0
                ? hedgeRemainingBudget.min(globalRemainingBudget)
                : hedgeRemainingBudget;
        int remainingPositionSlots = Math.max(0, hedgeProperties.getMaxOpenPositions() - openPositions.size());
        String status = hedgeProperties.isEnabled() ? "ACTIVE" : "PAUSED";

        StrategySummaryView summary = new StrategySummaryView(
                HEDGE_CODE,
                "Polymarket 对冲策略",
                "Polymarket",
                status,
                buildHedgeDescription(mode),
                openPositions.size(),
                compositePnl,
                realizedPnl,
                realizedPnl,
                pendingSettlementPnl,
                pendingSettlementPnl,
                winRate,
                allPositions.size()
        );

        StrategyMetricsView metrics = new StrategyMetricsView(
                allPositions.size(),
                Math.max(allPositions.size() - cancelledPositions, 0),
                openPositions.size(),
                closedPositions,
                winningPositions,
                losingPositions,
                winRate,
                tradedVolume,
                feeUsdc,
                realizedPnl,
                pendingSettlementPnl,
                pendingSettlementPnl,
                compositePnl,
                realizedPnl,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                BigDecimal.ZERO,
                remainingPositionSlots,
                availableCapital,
                availableCapital,
                lockedClaimProceeds,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO
        );

        return new StrategyDetailView(
                summary,
                metrics,
                buildHedgeInsights(metrics, settlementCandidates, unresolvedPositions, mode),
                openPositions.stream().map(this::toHedgePosition).toList(),
                settlementCandidates.stream().map(this::toHedgeSettlement).toList(),
                recentPositions.stream().map(position -> toHedgeOrder(position, orderMap.get(position.getId()))).toList(),
                List.of(),
                List.of()
        );
    }

    private StrategySummaryDetailView buildHedgeSummaryDetail(String mode) {
        List<HedgePositionEntity> allPositions = hedgePositionMapper.selectList(new LambdaQueryWrapper<HedgePositionEntity>()
                .orderByDesc(HedgePositionEntity::getOpenedAt)
                .orderByDesc(HedgePositionEntity::getId));
        List<HedgePositionEntity> openPositions = allPositions.stream()
                .filter(position -> "OPEN".equalsIgnoreCase(position.getStatus()))
                .toList();
        List<HedgePositionEntity> unresolvedPositions = allPositions.stream()
                .filter(this::isHedgeUnresolved)
                .toList();
        List<HedgePositionEntity> settledPositions = allPositions.stream()
                .filter(position -> "SETTLED".equalsIgnoreCase(position.getStatus()))
                .toList();
        List<HedgePositionEntity> settlementCandidates = allPositions.stream()
                .filter(this::isHedgeSettlementCandidate)
                .sorted(Comparator
                        .comparing((HedgePositionEntity position) -> position.getMarketEndAt() == null ? Long.MAX_VALUE : position.getMarketEndAt())
                        .thenComparing(position -> position.getOpenedAt() == null ? Long.MAX_VALUE : position.getOpenedAt().getTime()))
                .limit(8)
                .toList();

        BigDecimal realizedPnl = sum(settledPositions, HedgePositionEntity::getRealizedPnlUsdc);
        BigDecimal pendingSettlementPnl = sum(unresolvedPositions, HedgePositionEntity::getExpectedProfitUsdc);
        BigDecimal compositePnl = realizedPnl.add(pendingSettlementPnl);
        BigDecimal tradedVolume = sum(allPositions, HedgePositionEntity::getTotalCostUsdc);
        BigDecimal feeUsdc = sum(settledPositions, HedgePositionEntity::getFeeUsdc);
        BigDecimal lockedClaimProceeds = settlementCandidates.stream()
                .map(this::resolveHedgeClaimableProceeds)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int closedPositions = settledPositions.size();
        int winningPositions = (int) settledPositions.stream()
                .filter(position -> safe(position.getRealizedPnlUsdc()).compareTo(BigDecimal.ZERO) > 0)
                .count();
        int losingPositions = (int) settledPositions.stream()
                .filter(position -> safe(position.getRealizedPnlUsdc()).compareTo(BigDecimal.ZERO) < 0)
                .count();
        int cancelledPositions = (int) allPositions.stream()
                .filter(position -> "CANCELLED".equalsIgnoreCase(position.getStatus()))
                .count();
        BigDecimal winRate = closedPositions == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(winningPositions)
                .divide(BigDecimal.valueOf(closedPositions), 4, RoundingMode.HALF_UP);

        GlobalRiskGuard.GlobalExposureSummary exposure = globalRiskGuard.getSummary();
        BigDecimal hedgeRemainingBudget = positiveOrZero(safe(hedgeProperties.getMaxTotalExposureUsdc()).subtract(safe(exposure.hedgeExposureUsdc())));
        BigDecimal globalRemainingBudget = safe(hedgeProperties.getGlobalMaxTotalExposureUsdc()).compareTo(BigDecimal.ZERO) > 0
                ? positiveOrZero(safe(hedgeProperties.getGlobalMaxTotalExposureUsdc()).subtract(safe(exposure.totalExposureUsdc())))
                : hedgeRemainingBudget;
        BigDecimal availableCapital = safe(hedgeProperties.getGlobalMaxTotalExposureUsdc()).compareTo(BigDecimal.ZERO) > 0
                ? hedgeRemainingBudget.min(globalRemainingBudget)
                : hedgeRemainingBudget;
        int remainingPositionSlots = Math.max(0, hedgeProperties.getMaxOpenPositions() - openPositions.size());
        String status = hedgeProperties.isEnabled() ? "ACTIVE" : "PAUSED";

        StrategySummaryView summary = new StrategySummaryView(
                HEDGE_CODE,
                "Polymarket 对冲策略",
                "Polymarket",
                status,
                buildHedgeDescription(mode),
                openPositions.size(),
                compositePnl,
                realizedPnl,
                realizedPnl,
                pendingSettlementPnl,
                pendingSettlementPnl,
                winRate,
                allPositions.size()
        );

        StrategyMetricsView metrics = new StrategyMetricsView(
                allPositions.size(),
                Math.max(allPositions.size() - cancelledPositions, 0),
                openPositions.size(),
                closedPositions,
                winningPositions,
                losingPositions,
                winRate,
                tradedVolume,
                feeUsdc,
                realizedPnl,
                pendingSettlementPnl,
                pendingSettlementPnl,
                compositePnl,
                realizedPnl,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0,
                0,
                BigDecimal.ZERO,
                remainingPositionSlots,
                availableCapital,
                availableCapital,
                lockedClaimProceeds,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO,
                0,
                BigDecimal.ZERO
        );
        return new StrategySummaryDetailView(
                summary,
                metrics,
                buildHedgeInsights(metrics, settlementCandidates, unresolvedPositions, mode)
        );
    }

    private StrategyMetricsView emptyMetrics() {
        return new StrategyMetricsView(
                0, 0, 0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, BigDecimal.ZERO,
                0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO
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

    private List<StrategyInsightView> buildHedgeInsights(StrategyMetricsView metrics,
                                                         List<HedgePositionEntity> nextSettlements,
                                                         List<HedgePositionEntity> unresolvedPositions,
                                                         String mode) {
        List<StrategyInsightView> insights = new ArrayList<>();
        if (!hedgeProperties.isEnabled()) {
            insights.add(new StrategyInsightView("WARN", "策略当前已关闭", "polymarket.hedge.enabled=false，页面仍展示历史数据，但不会继续自动开新对冲单。"));
        }
        if (hedgeProperties.isEnabled() && !hedgeProperties.isAutoExecutionEnabled()) {
            insights.add(new StrategyInsightView("INFO", "当前只扫描不自动执行", "系统会继续发现 yes/no 对冲机会并记录日志，但不会自动下双边订单。"));
        }
        if (metrics.remainingCapitalBudgetUsdc() != null
                && metrics.remainingCapitalBudgetUsdc().compareTo(safe(hedgeProperties.getMinSinglePositionUsdc())) < 0) {
            insights.add(new StrategyInsightView("WARN", "可用预算不足", "剩余对冲预算已低于单笔最小投入，当前即便扫到机会也很难继续建仓。"));
        }
        if (!nextSettlements.isEmpty()) {
            HedgePositionEntity next = nextSettlements.get(0);
            insights.add(new StrategyInsightView("INFO", "待结算对冲单", next.getQuestion() + "，预计北京时间 " + formatEpochMillis(next.getMarketEndAt()) + "。"));
        } else {
            insights.add(new StrategyInsightView("INFO", "暂无待结算对冲单", "当前没有已过结算窗口或已进入 SETTLING 的对冲持仓。"));
        }
        if (!unresolvedPositions.isEmpty()) {
            insights.add(new StrategyInsightView("INFO", "双边仓位仍在持有中", "当前共有 " + unresolvedPositions.size() + " 组 yes/no 对冲仓位未最终结算，订单历史会按成对订单展示。"));
        }
        if ("LIVE".equalsIgnoreCase(mode) || "PAPER".equalsIgnoreCase(mode)) {
            insights.add(new StrategyInsightView("INFO", "当前口径说明", "对冲策略当前不区分 PAPER/LIVE，页面会复用同一套对冲仓位与订单数据。"));
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

    private String buildHedgeDescription(String mode) {
        if ("LIVE".equalsIgnoreCase(mode) || "PAPER".equalsIgnoreCase(mode)) {
            return "基于 Polymarket yes/no 双边下单的对冲策略视图；当前 PAPER/LIVE 切换共用同一套对冲数据。";
        }
        return "基于 Polymarket yes/no 双边下单的对冲策略视图。";
    }

    private StrategyPositionRowView toPosition(SmartWalletPositionView row) {
        return new StrategyPositionRowView(row.getId(), row.getMarketId(), row.getQuestion(), row.getOutcome(), row.getStatus(),
                row.getQuantity(), row.getAverageEntryPrice(), row.getCostBasisUsdc(), row.getOriginalCostBasisUsdc(),
                row.getRealizedPnlUsdc(), row.getUnrealizedPnlUsdc(), row.getPendingSettlementNetPnlUsdc(),
                row.getClaimableProceedsUsdc(), row.getMarketEndAtBjt());
    }

    private StrategyPositionRowView toHedgePosition(HedgePositionEntity row) {
        BigDecimal pendingProfit = safe(row.getExpectedProfitUsdc());
        return new StrategyPositionRowView(
                row.getId(),
                row.getMarketId(),
                row.getQuestion(),
                "YES / NO",
                row.getStatus(),
                row.getQuantity(),
                safe(row.getYesEntryPrice()).add(safe(row.getNoEntryPrice())),
                safe(row.getTotalCostUsdc()),
                safe(row.getTotalCostUsdc()),
                safe(row.getRealizedPnlUsdc()),
                pendingProfit,
                pendingProfit,
                resolveHedgeClaimableProceeds(row),
                formatEpochMillis(row.getMarketEndAt())
        );
    }

    private StrategyPositionRowView toHedgeSettlement(HedgePositionEntity row) {
        BigDecimal pendingProfit = safe(row.getExpectedProfitUsdc());
        return new StrategyPositionRowView(
                row.getId(),
                row.getMarketId(),
                row.getQuestion(),
                row.getFinalOutcome() == null ? "YES / NO" : row.getFinalOutcome(),
                row.getStatus(),
                row.getQuantity(),
                safe(row.getYesEntryPrice()).add(safe(row.getNoEntryPrice())),
                safe(row.getTotalCostUsdc()),
                safe(row.getTotalCostUsdc()),
                safe(row.getRealizedPnlUsdc()),
                pendingProfit,
                pendingProfit,
                resolveHedgeClaimableProceeds(row),
                formatEpochMillis(row.getMarketEndAt())
        );
    }

    private StrategyOrderRowView toOrder(SmartWalletOrderView row) {
        return new StrategyOrderRowView(row.getId(), row.getFollowTaskId(), row.getExecutionMode(), row.getQuestion(),
                row.getOutcome(), row.getSide(), row.getOrderType(), row.getEarlyExit(), row.getExitReason(),
                row.getStatus(), row.getPrice(), row.getSize(),
                row.getNotionalUsdc(), row.getCostBasisUsdc(), row.getRealizedPnlUsdc(), row.getEstimatedFeeUsdc(), row.getEstimatedSlippageUsdc(),
                row.getFollowProbability(), row.getCalibratedFollowProbability(), row.getSlippageSpikeProbability(), row.getEstimateSampleSize(),
                row.getMarketEndAtBjt(), row.getPlacedAt() == null ? null : row.getPlacedAt().toString());
    }

    private StrategyOrderRowView toHedgeOrder(HedgePositionEntity position, List<HedgeOrderEntity> orders) {
        Date placedAt = earliestSubmittedAt(position, orders);
        BigDecimal feeUsdc = safe(position.getFeeUsdc()).add(sum(orders, HedgeOrderEntity::getFeeUsdc));
        return new StrategyOrderRowView(
                position.getId(),
                position.getId(),
                "HEDGE_PAIR",
                position.getQuestion(),
                "YES / NO",
                "PAIR",
                "PAIR",
                false,
                null,
                buildHedgePairOrderStatus(position, orders),
                safe(position.getYesEntryPrice()).add(safe(position.getNoEntryPrice())),
                position.getQuantity(),
                safe(position.getTotalCostUsdc()),
                safe(position.getTotalCostUsdc()),
                safe(position.getRealizedPnlUsdc()),
                feeUsdc,
                BigDecimal.ZERO,
                null,
                null,
                null,
                orders == null ? 0 : orders.size(),
                formatEpochMillis(position.getMarketEndAt()),
                formatDate(placedAt)
        );
    }

    private StrategyTimelinePointView toTimeline(SmartWalletPnlTimelinePoint row) {
        return new StrategyTimelinePointView(row.getBucketAt() == null ? null : row.getBucketAt().toString(),
                row.getNetPnlUsdc(), row.getCumulativeNetPnlUsdc());
    }

    private StrategyConflictRowView toConflict(SmartWalletConflictMarketView row) {
        return new StrategyConflictRowView(row.getMarketId(), row.getQuestion(), row.getOutcomes(), row.getOpenPositionCount(),
                row.getNetPnlUsdc(), row.getMarketEndAtBjt());
    }

    private StrategySummaryView placeholder(String code, String name, String category, String status, String description) {
        return new StrategySummaryView(code, name, category, status, description, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isHedgeUnresolved(HedgePositionEntity position) {
        String status = position.getStatus();
        return "OPEN".equalsIgnoreCase(status) || "SETTLING".equalsIgnoreCase(status);
    }

    private boolean isHedgeSettlementCandidate(HedgePositionEntity position) {
        if ("SETTLING".equalsIgnoreCase(position.getStatus())) {
            return true;
        }
        Long marketEndAt = position.getMarketEndAt();
        return "OPEN".equalsIgnoreCase(position.getStatus())
                && marketEndAt != null
                && marketEndAt > 0
                && marketEndAt <= System.currentTimeMillis();
    }

    private Map<Long, List<HedgeOrderEntity>> loadHedgeOrdersByPositionId(List<HedgePositionEntity> positions) {
        if (positions == null || positions.isEmpty()) {
            return Map.of();
        }
        List<Long> positionIds = positions.stream()
                .map(HedgePositionEntity::getId)
                .toList();
        return hedgeOrderMapper.selectList(new LambdaQueryWrapper<HedgeOrderEntity>()
                        .in(HedgeOrderEntity::getHedgePositionId, positionIds)
                        .orderByDesc(HedgeOrderEntity::getSubmittedAt)
                        .orderByDesc(HedgeOrderEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(HedgeOrderEntity::getHedgePositionId));
    }

    private Date earliestSubmittedAt(HedgePositionEntity position, List<HedgeOrderEntity> orders) {
        return (orders == null ? List.<HedgeOrderEntity>of() : orders).stream()
                .map(HedgeOrderEntity::getSubmittedAt)
                .filter(date -> date != null)
                .min(Date::compareTo)
                .orElse(position.getOpenedAt());
    }

    private String buildHedgePairOrderStatus(HedgePositionEntity position, List<HedgeOrderEntity> orders) {
        String positionStatus = position.getStatus();
        if ("SETTLING".equalsIgnoreCase(positionStatus) || "SETTLED".equalsIgnoreCase(positionStatus)) {
            return positionStatus;
        }
        if (orders == null || orders.isEmpty()) {
            return positionStatus == null ? "-" : positionStatus;
        }
        String yesStatus = findOrderStatus(orders, "YES");
        String noStatus = findOrderStatus(orders, "NO");
        if (yesStatus.equalsIgnoreCase(noStatus)) {
            return yesStatus;
        }
        return "YES:" + yesStatus + " / NO:" + noStatus;
    }

    private String findOrderStatus(List<HedgeOrderEntity> orders, String side) {
        return orders.stream()
                .filter(order -> side.equalsIgnoreCase(order.getSide()))
                .map(HedgeOrderEntity::getStatus)
                .filter(status -> status != null && !status.isBlank())
                .findFirst()
                .orElse("PENDING");
    }

    private BigDecimal resolveHedgeClaimableProceeds(HedgePositionEntity row) {
        if ("SETTLING".equalsIgnoreCase(row.getStatus())) {
            return safe(row.getQuantity());
        }
        if ("SETTLED".equalsIgnoreCase(row.getStatus())) {
            return safe(row.getClaimedUsdc());
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal positiveOrZero(BigDecimal value) {
        return safe(value).max(BigDecimal.ZERO);
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
}
