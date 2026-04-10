package com.metaverse.coin.strategy.job.strategy.application;

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
import com.metaverse.coin.strategy.job.strategy.model.StrategyMetricsView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyOrderRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyPositionRowView;
import com.metaverse.coin.strategy.job.strategy.model.StrategySummaryView;
import com.metaverse.coin.strategy.job.strategy.model.StrategyTimelinePointView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class StrategyProductQueryService {

    private static final String POLYMARKET_CODE = "POLYMARKET_COPY";
    private static final DateTimeFormatter BJT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withLocale(Locale.SIMPLIFIED_CHINESE)
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final SmartWalletAccountingService accountingService;

    public StrategyProductQueryService(SmartWalletAccountingService accountingService) {
        this.accountingService = accountingService;
    }

    public StrategyDashboardOverviewView overview() {
        return overview(null);
    }

    public StrategyDashboardOverviewView overview(String mode) {
        StrategyDetailView polymarket = detail(POLYMARKET_CODE, mode);
        List<StrategySummaryView> strategies = new ArrayList<>();
        strategies.add(polymarket.summary());
        strategies.add(placeholder("HEDGE", "对冲策略", "对冲", "PLANNED", "后续用于市场中性、风险对冲和跨市场保护仓位。"));
        strategies.add(placeholder("ROBOT", "机器人策略", "机器人", "PLANNED", "后续用于事件驱动机器人、盘口套利和自动化规则执行。"));
        strategies.add(placeholder("INTELLIGENT", "智能策略", "智能", "PLANNED", "后续用于模型评分、智能分层择时和多因子调度。"));
        return new StrategyDashboardOverviewView(BJT_FORMATTER.format(Instant.now()), POLYMARKET_CODE, strategies);
    }

    public StrategyDetailView detail(String strategyCode) {
        return detail(strategyCode, null);
    }

    public StrategyDetailView detail(String strategyCode, String mode) {
        if (!POLYMARKET_CODE.equalsIgnoreCase(strategyCode)) {
            StrategySummaryView summary = placeholder(strategyCode, strategyCode, "待扩展", "PLANNED", "当前策略尚未接入实时数据。");
            return new StrategyDetailView(summary,
                    new StrategyMetricsView(0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO,
                            0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                    List.of(new StrategyInsightView("INFO", "策略待接入", "当前页面结构已预留，后续可继续接入真实策略数据。")),
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }

        SmartWalletPerformanceView performance = accountingService.performance(mode);
        SmartWalletPnlSummaryView pnlSummary = accountingService.summary(mode);
        SmartWalletCapacitySummaryView capacity = accountingService.capacitySummary(mode);
        List<SmartWalletPositionView> positions = accountingService.listActivePositions(mode);
        List<SmartWalletPositionView> nextSettlements = accountingService.listNextSettlements(8, mode);
        List<SmartWalletOrderView> orders = accountingService.listOrders(30, mode);
        List<SmartWalletPnlTimelinePoint> timeline = accountingService.pnlTimeline(30, mode);
        List<SmartWalletConflictMarketView> conflicts = accountingService.conflictingMarkets(10, false, mode);

        StrategySummaryView summary = new StrategySummaryView(
                POLYMARKET_CODE, "Polymarket 跟单策略", "Polymarket", "ACTIVE",
                buildDescription(mode),
                performance.getOpenPositions(), safe(performance.getCompositePnlUsdc()), safe(performance.getNetPnlUsdc()),
                safe(performance.getRealizedPnlUsdc()),
                safe(performance.getUnrealizedPnlUsdc()), safe(performance.getPendingSettlementPnlUsdc()),
                safe(performance.getWinRate()), performance.getTotalOrders()
        );

        StrategyMetricsView metrics = new StrategyMetricsView(
                performance.getTotalOrders(), performance.getFilledOrders(), performance.getOpenPositions(),
                performance.getClosedPositions(), performance.getWinningPositions(), performance.getLosingPositions(),
                safe(performance.getWinRate()), safe(pnlSummary.getTradedVolumeUsdc()), safe(pnlSummary.getFeeUsdc()),
                safe(performance.getRealizedPnlUsdc()), safe(performance.getUnrealizedPnlUsdc()),
                safe(performance.getPendingSettlementPnlUsdc()), safe(performance.getCompositePnlUsdc()),
                safe(performance.getNetPnlUsdc()),
                safe(performance.getProfitFactor()), safe(performance.getMaxDrawdownUsdc()), performance.getConflictingMarkets(),
                performance.getOpenConflictingMarkets(), safe(performance.getConflictNetPnlUsdc()),
                capacity.getRemainingPositionSlots(), safe(capacity.getRemainingCapitalBudgetUsdc()),
                safe(capacity.getAvailableCapitalUsdc()), safe(capacity.getLockedClaimProceedsUsdc())
        );

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
        BigDecimal pendingSettlementAmount = "RESOLVED_UNCLAIMED".equalsIgnoreCase(row.getStatus())
                ? safe(row.getClaimableProceedsUsdc())
                : safe(row.getUnrealizedPnlUsdc());
        return new StrategyPositionRowView(row.getId(), row.getMarketId(), row.getQuestion(), row.getOutcome(), row.getStatus(),
                row.getQuantity(), row.getAverageEntryPrice(), row.getCostBasisUsdc(), row.getRealizedPnlUsdc(),
                row.getUnrealizedPnlUsdc(), pendingSettlementAmount, row.getMarketEndAtBjt());
    }

    private StrategyOrderRowView toOrder(SmartWalletOrderView row) {
        return new StrategyOrderRowView(row.getId(), row.getFollowTaskId(), row.getExecutionMode(), row.getQuestion(),
                row.getOutcome(), row.getSide(), row.getStatus(), row.getPrice(), row.getSize(),
                row.getNotionalUsdc(), row.getExpectedNetEvUsdc(), row.getEstimatedFeeUsdc(), row.getEstimatedSlippageUsdc(),
                row.getFollowProbability(), row.getCalibratedFollowProbability(), row.getSlippageSpikeProbability(), row.getEvSampleSize(),
                row.getMarketEndAtBjt(), row.getPlacedAt() == null ? null : row.getPlacedAt().toString());
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
}
