package com.metaverse.coin.strategy.job.smartwallet.controller;
import com.metaverse.coin.strategy.common.service.smartwallet.model.ConcentrationSnapshot;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.analytics.application.StrategyAnalyticsApplicationService;
import com.metaverse.coin.strategy.job.execution.application.ExecutionApplicationService;
import com.metaverse.coin.strategy.job.follow.application.FollowTaskApplicationService;
import com.metaverse.coin.strategy.job.marketdata.application.PredictionMarketDataApplicationService;
import com.metaverse.coin.strategy.job.risk.application.BaselineModelConfigApplicationService;
import com.metaverse.coin.strategy.job.risk.application.RiskPolicyApplicationService;
import com.metaverse.coin.strategy.job.risk.application.StrategyRuntimeConfigService;
import com.metaverse.coin.strategy.job.settlement.application.SettlementApplicationService;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.AbnormalMarketManualReviewRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.AbnormalMarketManualReviewResult;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionWalletStatus;
import com.metaverse.coin.strategy.job.smartwallet.model.HistoricalPaperSettlementReviewView;
import com.metaverse.coin.strategy.job.smartwallet.model.LiveValidationRunSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.LiveValidationStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.LocalSettlementConfirmRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.LeaderTradeOutcomeCoverageView;
import com.metaverse.coin.strategy.job.smartwallet.model.LeaderTradeOutcomeSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.BaselineModelConfigView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.DiscoverySessionCoverageView;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketMarketDataDiagnosticsView;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketMockWsEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketOrderDryRunRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketProductOverview;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.OrderDiagnosticReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.PositionMarketDiagnosticView;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskPresetCatalogView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderPnlCategoryAttributionView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletMarketMonitoringSummaryView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletMarketMonitorItemView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualAllowlistRequest;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualAllowlistSummaryView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualAllowlistView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualBlocklistRequest;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualBlocklistSummaryView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletManualBlocklistView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketLinkView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketIdRepairRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketIdRepairSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOpsCandidateView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.ResetStrategyRuntimeConfigRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyRuntimeConfigCatalogView;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyRuntimeConfigView;
import com.metaverse.coin.strategy.job.smartwallet.model.UpdateStrategyRuntimeConfigRequest;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfileStaleDiagnosticView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfileTierDiagnosticsSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileTruthAuditView;
import com.metaverse.coin.strategy.job.strategy.model.CursorPageView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.OffsetPageView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFillView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowAddressDailyReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCashLedgerView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalHealthSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCapacitySummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletConflictMarketView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlTimelinePoint;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletDailyReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReportAttributionItem;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletTradeView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletTradeOrderView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRunView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryReportView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfile;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimRetryReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimRetryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimRunView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletScanResponse;
import com.metaverse.coin.strategy.job.smartwallet.model.UpsertMarketRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.UpdateBaselineModelConfigRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.UpsertWalletRequest;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletDiagnosisView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderAnalysisView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderStatsView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletScreenerView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletBehaviorProfileView;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketSmartWalletMonitorService;
import com.metaverse.coin.strategy.job.smartwallet.service.AbnormalMarketReviewService;
import com.metaverse.coin.strategy.job.smartwallet.service.ClosedPositionSyncDispatcher;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketConsensusSignalService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketProductOverviewService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketMarketDataStreamService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.PredictionMarketOpportunityService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletAccountingService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletClaimService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletLiveValidationService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletMarketStateService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletMarketLinkService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletMarketRepairService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletMarketMonitoringService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualAllowlistService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletManualBlocklistService;
import com.metaverse.coin.strategy.job.smartwallet.service.MarketSnapshotCollectorService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletDataResetService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletRecoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletRegistryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSelectionService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletTradeOrderService;
import com.metaverse.coin.strategy.job.smartwallet.service.FollowTradeAttributionService;
import com.metaverse.coin.strategy.job.smartwallet.service.LeaderTradeOutcomeRebuildService;
import com.metaverse.coin.strategy.job.smartwallet.service.MarketConcentrationService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSettlementSyncService;
import com.metaverse.coin.strategy.job.smartwallet.service.WalletArchetypeClassifierService;
import com.metaverse.coin.strategy.job.smartwallet.service.WalletBehaviorProfileService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletOrderAnalysisService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletProfileStoreService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletProfileTruthRefreshService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletReplayBacktestService;
import com.metaverse.coin.strategy.job.smartwallet.service.WalletOrderStatsService;
import com.metaverse.coin.strategy.job.smartwallet.service.WalletProfileAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.env.Environment;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/smart-wallet")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Smart Wallet", description = "Polymarket 聪明钱包跟踪、模拟跟单、风控分析、结算与报表接口")
public class SmartWalletController {

    private static final DateTimeFormatter EXPORT_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final SmartWalletProperties properties;
    private final SmartWalletRegistryService walletRegistryService;
    private final PredictionMarketOpportunityService opportunityService;
    private final SmartWalletFollowService followService;
    private final SmartWalletAccountingService accountingService;
    private final PolymarketSmartWalletMonitorService polymarketMonitorService;
    private final PolymarketConsensusSignalService consensusSignalService;
    private final PolymarketClobExecutionService clobExecutionService;
    private final PolymarketMarketDataStreamService marketDataStreamService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final PolymarketProductOverviewService productOverviewService;
    private final PolymarketWalletDiscoveryService polymarketWalletDiscoveryService;
    private final SmartWalletSelectionService selectionService;
    private final SmartWalletFollowTaskService followTaskService;
    private final SmartWalletClaimService claimService;
    private final SmartWalletLiveValidationService liveValidationService;
    private final SmartWalletMarketStateService marketStateService;
    private final ClosedPositionSyncDispatcher closedPositionSyncDispatcher;
    private final SmartWalletMarketRepairService smartWalletMarketRepairService;
    private final SmartWalletMarketLinkService marketLinkService;
    private final SmartWalletTradeOrderService tradeOrderService;
    private final SmartWalletSettlementSyncService settlementSyncService;
    private final SmartWalletDataResetService dataResetService;
    private final SmartWalletRecoveryService recoveryService;
    private final Environment environment;
    private final PredictionMarketDataApplicationService predictionMarketDataApplicationService;
    private final FollowTaskApplicationService followTaskApplicationService;
    private final ExecutionApplicationService executionApplicationService;
    private final SettlementApplicationService settlementApplicationService;
    private final RiskPolicyApplicationService riskPolicyApplicationService;
    private final BaselineModelConfigApplicationService baselineModelConfigApplicationService;
    private final StrategyRuntimeConfigService strategyRuntimeConfigService;
    private final StrategyAnalyticsApplicationService strategyAnalyticsApplicationService;
    private final MarketConcentrationService marketConcentrationService;
    private final FollowTradeAttributionService followTradeAttributionService;
    private final WalletArchetypeClassifierService walletArchetypeClassifierService;
    private final WalletBehaviorProfileService walletBehaviorProfileService;
    private final SmartWalletOrderAnalysisService smartWalletOrderAnalysisService;
    private final WalletOrderStatsService walletOrderStatsService;
    private final SmartWalletProfileStoreService smartWalletProfileStoreService;
    private final WalletProfileAnalysisService walletProfileAnalysisService;
    private final SmartWalletReplayBacktestService smartWalletReplayBacktestService;
    private final SmartWalletMarketMonitoringService smartWalletMarketMonitoringService;
    private final SmartWalletManualAllowlistService smartWalletManualAllowlistService;
    private final SmartWalletManualBlocklistService smartWalletManualBlocklistService;
    private final AbnormalMarketReviewService abnormalMarketReviewService;
    private final LeaderTradeOutcomeRebuildService leaderTradeOutcomeRebuildService;
    private final SmartWalletProfileTruthRefreshService smartWalletProfileTruthRefreshService;

    /** 异步回填任务状态存储（进程内，重启丢失） */
    // [MIGRATED] 原 metricsRebuildJobs in-memory map 已废弃；
    // sync-all-positions / metrics/rebuild 状态改由 strategy_manual_trigger 表持久化，
    // 由 web 端读取。

    @Operation(summary = "健康检查", description = "查看 smart-wallet 服务是否启动，以及当前画像、观察名单、钱包与市场数量。")
    @GetMapping("/health")
    public Map<String, Object> health() {
        long profileCount = smartWalletProfileStoreService.countPolymarketProfiles();
        return Map.of(
                "service", "smart-wallet",
                "profiles", profileCount,
                "status", "UP"
        );
    }

    @Operation(summary = "产品总览", description = "查看 Polymarket smart-wallet 模块的总览信息，包括 profiles、watchlist、tasks 与执行钱包状态。")
    @GetMapping("/overview")
    public PolymarketProductOverview overview() {
        return productOverviewService.overview();
    }

    @Operation(summary = "leader 真实闭环胜率汇总", description = "读取 smart_wallet_leader_trade_outcome，按 ALL/90D/30D/7D 输出真实闭环胜率、PnL 与建议。")
    @GetMapping("/leader-outcomes/summary")
    public List<LeaderTradeOutcomeSummaryView> leaderOutcomeSummary(@RequestParam(required = false) String walletAddress,
                                                                    @RequestParam(defaultValue = "ALL") String window) {
        return leaderTradeOutcomeRebuildService.summarize(walletAddress, window);
    }

    @Operation(summary = "leader 跟单覆盖率评估", description = "比较 leader 真实闭环、我方 FOLLOWED 子集和 SKIPPED/未跟子集，用于评估是否应扩大 100% paper 跟进。")
    @GetMapping("/leader-outcomes/follow-coverage")
    public List<LeaderTradeOutcomeCoverageView> leaderOutcomeCoverage(@RequestParam(required = false) String walletAddress) {
        return leaderTradeOutcomeRebuildService.coverage(walletAddress);
    }

    // [MIGRATED] GET /follow-tasks/coverage-report → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_COVERAGE_REPORT)

    @Operation(summary = "地址画像质量审计", description = "列出 truth 样本、低置信/未结算数量，以及旧口径遗漏我方 closed loss 的数量。")
    @GetMapping("/profiles/truth-audit")
    public List<SmartWalletProfileTruthAuditView> profileTruthAudit(@RequestParam(required = false) String walletAddress) {
        return smartWalletProfileTruthRefreshService.audit(walletAddress);
    }

    // [MIGRATED] POST /admin/reset-data 和 POST /admin/recover-state 已下沉到 coin-strategy-web 的
    // SmartWalletAdminController，通过 strategy_manual_trigger 命令队列异步派发到
    // SmartWalletDataResetService / SmartWalletRecoveryService。job 端只保留 service，不再暴露 HTTP。

    @Operation(summary = "钱包画像列表", description = "查看已入库的 smart wallet Polymarket 画像信息。")
    @GetMapping("/profiles")
    public List<SmartWalletProfileEntity> profiles() {
        return polymarketMonitorService.listProfiles();
    }

    @Operation(summary = "地址画像筛选器", description = "按地址质量、可复制性、胜率来源和退出风格筛选 smart-wallet 地址，并支持排序、过滤和游标分页。")
    @GetMapping("/profiles/screener")
    public CursorPageView<WalletScreenerView> profileScreener(
            @RequestParam(defaultValue = "candidates") String scope,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "replicabilityScore") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(required = false) String screenerTier,
            @RequestParam(required = false) Boolean highQualityAddress,
            @RequestParam(required = false) Boolean highFrequencyTradingAddress,
            @RequestParam(required = false) String winSourceType,
            @RequestParam(required = false) String recommendedExitLane,
            @RequestParam(required = false) BigDecimal minQualityScore,
            @RequestParam(required = false) BigDecimal minReplicabilityScore) {
        return walletProfileAnalysisService.screen(
                resolveWatchlistScope(scope),
                limit,
                cursor,
                sortBy,
                sortDirection,
                screenerTier,
                highQualityAddress,
                highFrequencyTradingAddress,
                winSourceType,
                recommendedExitLane,
                minQualityScore,
                minReplicabilityScore
        );
    }

    @Operation(summary = "地址单笔分析", description = "查看单地址逐单 ROI、持有时长，以及是提前退出还是等到结算。")
    @GetMapping("/profiles/{walletAddress}/order-analysis")
    public CursorPageView<WalletOrderAnalysisView> walletOrderAnalysis(@PathVariable String walletAddress,
                                                                       @RequestParam(defaultValue = "20") Integer limit,
                                                                       @RequestParam(required = false) String cursor,
                                                                       @RequestParam(required = false) String exitStyle,
                                                                       @RequestParam(required = false) Boolean isWin,
                                                                       @RequestParam(defaultValue = "false") boolean refresh) {
        List<WalletOrderAnalysisView> rows = smartWalletOrderAnalysisService.toViews(
                smartWalletOrderAnalysisService.listByWalletAddress(walletAddress, refresh, exitStyle, isWin)
        );
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        int start = parseCursor(cursor);
        if (start >= rows.size()) {
            return CursorPageView.empty();
        }
        int end = Math.min(rows.size(), start + safeLimit);
        String nextCursor = end < rows.size() ? String.valueOf(end) : null;
        return CursorPageView.of(rows.subList(start, end), nextCursor);
    }

    @Operation(summary = "地址窗口统计", description = "统一输出 closed + active 的地址统计口径，剔除待结算订单。")
    @GetMapping("/profiles/{walletAddress}/order-stats")
    public WalletOrderStatsView walletOrderStats(@PathVariable String walletAddress,
                                                 @RequestParam(defaultValue = "30d") String window,
                                                 @RequestParam(defaultValue = "false") boolean refresh) {
        return walletOrderStatsService.summarize(
                walletAddress,
                smartWalletOrderAnalysisService.listFreshOrRefresh(walletAddress, refresh),
                window
        );
    }

    @Operation(summary = "地址胜率诊断", description = "输出这个地址为什么赢、适不适合跟、以及推荐的退出方式。")
    @GetMapping("/profiles/{walletAddress}/diagnosis")
    public WalletDiagnosisView walletDiagnosis(@PathVariable String walletAddress,
                                               @RequestParam(defaultValue = "false") boolean refreshOrderAnalysis) {
        return walletProfileAnalysisService.diagnose(walletAddress, refreshOrderAnalysis);
    }

    @Operation(summary = "人工覆盖列表", description = "查看当前被人工强制只观察、强制可执行或手工指定退出风格的地址。")
    @GetMapping("/profiles/overrides")
    public List<SmartWalletProfileOverrideView> profileOverrides(@RequestParam(defaultValue = "100") Integer limit) {
        return smartWalletProfileStoreService.listManualOverrides(limit);
    }

    @Operation(summary = "人工覆盖摘要", description = "聚合当前人工覆盖的地址数量、类型分布和最近 24 小时变更情况。")
    @GetMapping("/profiles/overrides/summary")
    public SmartWalletProfileOverrideSummaryView profileOverridesSummary() {
        return smartWalletProfileStoreService.summarizeManualOverrides();
    }

    @Operation(summary = "导出人工覆盖 CSV", description = "导出当前所有人工覆盖项，方便运营排查和复盘。")
    @GetMapping(value = "/profiles/overrides/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportProfileOverrides(@RequestParam(defaultValue = "500") Integer limit) {
        List<SmartWalletProfileOverrideView> rows = smartWalletProfileStoreService.listManualOverrides(limit);
        return csvResponse(
                List.of("walletAddress", "alias", "qualified", "watchEnabled", "manualWatchEnabled",
                        "manualRecommendedExitLane", "manualOverrideNote", "manualOverrideUpdatedAt"),
                rows.stream().map(row -> java.util.Arrays.asList(
                        row.walletAddress(), row.alias(), row.qualified(), row.watchEnabled(),
                        row.manualWatchEnabled(), row.manualRecommendedExitLane(),
                        row.manualOverrideNote(), row.manualOverrideUpdatedAt()
                )).toList(),
                "smart_wallet_profile_overrides", "manual");
    }

    // [MIGRATED] POST /profiles/{walletAddress}/override 和 POST /profiles/overrides/batch
    // 已下沉到 coin-strategy-web 的 SmartWalletProfileWriteController，通过 strategy_manual_trigger
    // 命令队列异步派发到 SmartWalletProfileStoreService。job 端只保留 service，不再暴露 HTTP。

    @Operation(summary = "手工地址白名单列表", description = "查看当前用于绕过地址画像准入的手工 allowlist。交易级和执行级风控仍会生效。")
    @GetMapping("/allowlist")
    public List<SmartWalletManualAllowlistView> manualAllowlist(@RequestParam(defaultValue = "100") Integer limit) {
        return smartWalletManualAllowlistService.list(limit);
    }

    @Operation(summary = "新增或更新手工地址白名单", description = "创建或更新 allowlist 项。默认 PAPER、仓位乘数 0.50、每日 LIVE 5 单、每轮 LIVE 2 单、30 天过期。")
    @PostMapping("/allowlist")
    public SmartWalletManualAllowlistView upsertManualAllowlist(@RequestBody SmartWalletManualAllowlistRequest request) {
        return smartWalletManualAllowlistService.upsert(request);
    }

    @Operation(summary = "按地址更新手工地址白名单", description = "按路径地址创建或更新 allowlist 项。")
    @PostMapping("/allowlist/{walletAddress}")
    public SmartWalletManualAllowlistView upsertManualAllowlistByWallet(@PathVariable String walletAddress,
                                                                        @RequestBody(required = false) SmartWalletManualAllowlistRequest request) {
        return smartWalletManualAllowlistService.upsert(walletAddress, request);
    }

    @Operation(summary = "禁用手工地址白名单", description = "将指定地址 allowlist 项置为 DISABLED。")
    @PostMapping("/allowlist/{walletAddress}/disable")
    public SmartWalletManualAllowlistView disableManualAllowlist(@PathVariable String walletAddress) {
        return smartWalletManualAllowlistService.disable(walletAddress);
    }

    @Operation(summary = "手工地址白名单摘要", description = "聚合 allowlist 总数、活跃数、过期数、禁用数和执行模式分布。")
    @GetMapping("/allowlist/summary")
    public SmartWalletManualAllowlistSummaryView manualAllowlistSummary() {
        return smartWalletManualAllowlistService.summary();
    }

    @Operation(summary = "清理过期手工地址白名单", description = "将已过期且仍为 ACTIVE 的 allowlist 项置为 DISABLED。")
    @PostMapping("/allowlist/cleanup-expired")
    public Map<String, Object> cleanupExpiredManualAllowlist() {
        return Map.of("updated", smartWalletManualAllowlistService.cleanupExpired());
    }

    @Operation(summary = "手工地址阻断名单列表", description = "查看当前永久阻断的钱包地址。命中后不会参与 discovery、观察、信号或跟单。")
    @GetMapping("/blocklist")
    public List<SmartWalletManualBlocklistView> manualBlocklist(@RequestParam(defaultValue = "100") Integer limit) {
        return smartWalletManualBlocklistService.list(limit);
    }

    @Operation(summary = "新增或更新手工地址阻断名单", description = "创建或更新 blocklist 项。默认 ACTIVE、永久阻断。")
    @PostMapping("/blocklist")
    public SmartWalletManualBlocklistView upsertManualBlocklist(@RequestBody SmartWalletManualBlocklistRequest request) {
        return smartWalletManualBlocklistService.upsert(request);
    }

    @Operation(summary = "批量新增或更新手工地址阻断名单", description = "批量创建或更新 blocklist 项。默认 ACTIVE、永久阻断。")
    @PostMapping("/blocklist/batch")
    public List<SmartWalletManualBlocklistView> upsertManualBlocklistBatch(@RequestBody SmartWalletManualBlocklistRequest request) {
        return smartWalletManualBlocklistService.upsertBatch(request);
    }

    @Operation(summary = "按地址更新手工地址阻断名单", description = "按路径地址创建或更新 blocklist 项。")
    @PostMapping("/blocklist/{walletAddress}")
    public SmartWalletManualBlocklistView upsertManualBlocklistByWallet(@PathVariable String walletAddress,
                                                                        @RequestBody(required = false) SmartWalletManualBlocklistRequest request) {
        return smartWalletManualBlocklistService.upsert(walletAddress, request);
    }

    @Operation(summary = "禁用手工地址阻断名单", description = "将指定地址 blocklist 项置为 DISABLED。")
    @PostMapping("/blocklist/{walletAddress}/disable")
    public SmartWalletManualBlocklistView disableManualBlocklist(@PathVariable String walletAddress) {
        return smartWalletManualBlocklistService.disable(walletAddress);
    }

    @Operation(summary = "手工地址阻断名单摘要", description = "聚合 blocklist 总数、活跃数和禁用数。")
    @GetMapping("/blocklist/summary")
    public SmartWalletManualBlocklistSummaryView manualBlocklistSummary() {
        return smartWalletManualBlocklistService.summary();
    }

    @Operation(summary = "人工覆盖筛选候选", description = "按 scope、recommendedExitLane、watchEnabled、manualWatchEnabled、updatedWithinHours 等条件筛出一批可执行运营动作的地址。")
    @GetMapping("/profiles/overrides/candidates")
    public List<SmartWalletProfileOpsCandidateView> profileOverrideCandidates(
            @RequestParam(defaultValue = "candidates") String scope,
            @RequestParam(defaultValue = "100") Integer limit,
            @RequestParam(required = false) String recommendedExitLane,
            @RequestParam(required = false) Boolean watchEnabled,
            @RequestParam(required = false) Boolean qualified,
            @RequestParam(required = false) Boolean manualWatchEnabled,
            @RequestParam(required = false, defaultValue = "false") boolean manualOnly,
            @RequestParam(required = false) Integer updatedWithinHours,
            @RequestParam(required = false) Boolean highFrequencyTradingAddress,
            @RequestParam(required = false) BigDecimal minReplicabilityScore,
            @RequestParam(required = false) BigDecimal maxReplicabilityScore) {
        int safeLimit = Math.max(1, Math.min(limit, 300));
        Long updatedCutoffMillis = updatedWithinHours == null
                ? null
                : System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(Math.max(updatedWithinHours, 0));

        return resolveWatchlistScope(scope).stream()
                .map(profile -> toOpsCandidateView(profile, walletBehaviorProfileService.getProfile(profile.getWalletAddress())))
                .filter(candidate -> matchesOverrideCandidateFilters(
                        candidate,
                        recommendedExitLane,
                        watchEnabled,
                        qualified,
                        manualWatchEnabled,
                        manualOnly,
                        updatedCutoffMillis,
                        highFrequencyTradingAddress,
                        minReplicabilityScore,
                        maxReplicabilityScore
                ))
                .sorted(Comparator
                        .comparing(SmartWalletProfileOpsCandidateView::replicabilityScore,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SmartWalletProfileOpsCandidateView::walletAddress, Comparator.nullsLast(String::compareTo)))
                .limit(safeLimit)
                .toList();
    }

    @Operation(summary = "可执行观察名单", description = "查看当前仍允许进入执行链路的 smart wallet 地址。")
    @GetMapping("/watchlist")
    public List<SmartWalletProfileEntity> watchlist() {
        return polymarketMonitorService.listExecutableWatchlist();
    }

    @Operation(summary = "候选观察名单", description = "查看通过外部画像筛选但未必仍允许执行的 smart wallet 候选地址。")
    @GetMapping("/watchlist/candidates")
    public List<SmartWalletProfileEntity> candidateWatchlist() {
        return polymarketMonitorService.listQualifiedCandidates();
    }

    @Operation(summary = "观察名单分层诊断", description = "输出 HOT/WARM/COLD 判定、数据新鲜度和第一个 HOT 阻断原因。")
    @GetMapping("/watchlist/tier-diagnostics")
    public List<SmartWalletProfileStaleDiagnosticView> watchlistTierDiagnostics(
            @RequestParam(defaultValue = "200") Integer limit) {
        return smartWalletProfileStoreService.listProfileTierDiagnostics(limit);
    }

    @Operation(summary = "观察名单分层诊断摘要", description = "按 HOT/WARM/COLD 汇总数据过期和 HOT 阻断原因分布。")
    @GetMapping("/watchlist/tier-diagnostics/summary")
    public List<SmartWalletProfileTierDiagnosticsSummaryView> watchlistTierDiagnosticsSummary() {
        return smartWalletProfileStoreService.summarizeProfileTierDiagnostics();
    }

    @Operation(summary = "可执行观察名单", description = "查看当前仍允许进入执行链路的 smart wallet 地址。")
    @GetMapping("/watchlist/executable")
    public List<SmartWalletProfileEntity> executableWatchlist() {
        return polymarketMonitorService.listExecutableWatchlist();
    }

    @Operation(summary = "保温观察名单", description = "查看当前退出执行链路但仍处于 WARM 保温监控层的 smart wallet 地址。")
    @GetMapping("/watchlist/warm")
    public List<SmartWalletProfileEntity> warmWatchlist() {
        return polymarketMonitorService.listWarmWatchlist();
    }

    @Operation(summary = "观察名单地址分型", description = "基于 wallet_feature_daily、trade_effect_label 与 profile 计算候选/可执行地址的交易风格分型与建议跟单 lane。")
    @GetMapping("/watchlist/archetypes")
    public List<WalletArchetypeView> watchlistArchetypes(@RequestParam(defaultValue = "50") Integer limit,
                                                          @RequestParam(defaultValue = "candidates") String scope) {
        return buildWatchlistArchetypes(limit, scope);
    }

    @Operation(summary = "导出观察名单地址分型 CSV", description = "导出增强版地址分型结果，包含退出风格、提前退出率和可复制分数。")
    @GetMapping(value = "/watchlist/archetypes/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportWatchlistArchetypes(@RequestParam(defaultValue = "200") Integer limit,
                                                            @RequestParam(defaultValue = "candidates") String scope) {
        List<WalletArchetypeView> rows = buildWatchlistArchetypes(limit, scope);
        return csvResponse(
                List.of("walletAddress", "archetype", "followLane", "recommendedExitLane",
                        "highFrequencyTradingAddress", "trades7d", "trades30d", "orderWinRate",
                        "averageOrderRoiRate", "medianOrderRoiRate", "settlementCompletionRate",
                        "earlyExitRate", "profitEarlyExitRate", "lossEarlyExitRate",
                        "winSourceType", "settlementWinRate", "earlySettlementRate", "stabilityScore",
                        "qualityScore", "replicabilityScore", "followFitScore", "confidence", "summary"),
                rows.stream().map(row -> java.util.Arrays.asList(
                        row.walletAddress(), row.archetype(), row.followLane(), row.recommendedExitLane(),
                        row.highFrequencyTradingAddress(), row.trades7d(), row.trades30d(), row.orderWinRate(),
                        row.averageOrderRoiRate(), row.medianOrderRoiRate(), row.settlementCompletionRate(),
                        row.earlyExitRate(), row.profitEarlyExitRate(), row.lossEarlyExitRate(),
                        row.winSourceType(), row.settlementWinRate(), row.earlySettlementRate(), row.stabilityScore(),
                        row.qualityScore(), row.replicabilityScore(), row.followFitScore(), row.confidence(), row.summary()
                )).toList(),
                "smart_wallet_watchlist_archetypes", scope);
    }

    @Operation(summary = "观察名单地址行为画像", description = "读取已缓存或预计算的监控地址行为画像。如需强制重算，请使用 POST /watchlist/behavior-profiles/refresh。")
    @GetMapping("/watchlist/behavior-profiles")
    public List<WalletBehaviorProfileView> watchlistBehaviorProfiles(@RequestParam(defaultValue = "30") Integer limit,
                                                                     @RequestParam(defaultValue = "candidates") String scope) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return resolveWatchlistScope(scope).stream()
                .limit(safeLimit)
                .map(profile -> walletBehaviorProfileService.getProfile(profile.getWalletAddress()))
                .filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparing(WalletBehaviorProfileView::replicabilityScore,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    @Operation(summary = "刷新并落库地址行为画像", description = "按 scope 批量重算监控地址行为画像，并 upsert 到 smart_wallet_behavior_profile 表。")
    @PostMapping("/watchlist/behavior-profiles/refresh")
    public Map<String, Object> refreshWatchlistBehaviorProfiles(@RequestParam(defaultValue = "30") Integer limit,
                                                                @RequestParam(defaultValue = "candidates") String scope) {
        List<WalletBehaviorProfileView> rows = walletBehaviorProfileService.refreshAndPersistProfiles(resolveWatchlistScope(scope), limit);
        return Map.of(
                "scope", scope,
                "limit", limit,
                "persisted", rows.size()
        );
    }

    // [MIGRATED] POST /profiles/metrics/rebuild + GET /profiles/metrics/rebuild/{id}
    // 已迁到 coin-strategy-web 的 SmartWalletProfileWriteController；
    // 执行逻辑封装为 SmartWalletProfileAsyncOpsService.rebuildProfileMetrics(...)，
    // 通过 strategy_manual_trigger 命令队列驱动，状态/结果存 DB（不再用 in-memory map）。

    private List<SmartWalletProfileEntity> resolveWatchlistScope(String scope) {
        if ("all".equalsIgnoreCase(scope)) {
            return polymarketMonitorService.listProfiles();
        }
        if ("warm".equalsIgnoreCase(scope)) {
            return polymarketMonitorService.listWarmWatchlist();
        }
        if ("executable".equalsIgnoreCase(scope)) {
            return polymarketMonitorService.listExecutableWatchlist();
        }
        return polymarketMonitorService.listQualifiedCandidates();
    }

    private List<WalletArchetypeView> buildWatchlistArchetypes(Integer limit, String scope) {
        return resolveWatchlistScope(scope).stream()
                .limit(Math.max(1, Math.min(limit, 200)))
                .map(profile -> enrichArchetypeView(
                        walletArchetypeClassifierService.classify(profile.getWalletAddress(), profile),
                        walletBehaviorProfileService.getProfile(profile.getWalletAddress())
                ))
                .toList();
    }

    private WalletArchetypeView enrichArchetypeView(WalletArchetypeView archetype,
                                                    WalletBehaviorProfileView behaviorProfile) {
        if (archetype == null) {
            return null;
        }
        return WalletArchetypeView.builder()
                .walletAddress(archetype.walletAddress())
                .archetype(archetype.archetype())
                .followLane(archetype.followLane())
                .archetypeReason(behaviorProfile == null ? archetype.archetypeReason() : behaviorProfile.archetypeReason())
                .archetypeConfidence(behaviorProfile == null ? archetype.archetypeConfidence() : behaviorProfile.archetypeConfidence())
                .confidence(archetype.confidence())
                .leaderQualityScore(archetype.leaderQualityScore())
                .followFitScore(archetype.followFitScore())
                .recommendedSyncIntervalMs(archetype.recommendedSyncIntervalMs())
                .labelSampleSize(archetype.labelSampleSize())
                .trades7d(archetype.trades7d())
                .trades30d(archetype.trades30d())
                .dedupOrderCount7d(behaviorProfile == null ? archetype.dedupOrderCount7d() : behaviorProfile.dedupOrderCount7d())
                .dedupOrderCount30d(behaviorProfile == null ? archetype.dedupOrderCount30d() : behaviorProfile.dedupOrderCount30d())
                .markets30d(archetype.markets30d())
                .concentrationMarket30d(archetype.concentrationMarket30d())
                .followableRate30d(archetype.followableRate30d())
                .alpha30s(archetype.alpha30s())
                .alpha5m(archetype.alpha5m())
                .alphaRule(archetype.alphaRule())
                .medianInterTradeMinutes(archetype.medianInterTradeMinutes())
                .medianHoldMinutes(behaviorProfile == null ? archetype.medianHoldMinutes() : behaviorProfile.medianHoldMinutes())
                .dataQuality(behaviorProfile == null ? archetype.dataQuality() : behaviorProfile.dataQuality())
                .highFrequencyTradingAddress(behaviorProfile == null ? null : behaviorProfile.highFrequencyTradingAddress())
                .closedPositionSampleSize(behaviorProfile == null ? null : behaviorProfile.closedPositionSampleSize())
                .orderWinRate(behaviorProfile == null ? null : behaviorProfile.orderWinRate())
                .averageOrderRoiRate(behaviorProfile == null ? null : behaviorProfile.averageOrderRoiRate())
                .medianOrderRoiRate(behaviorProfile == null ? null : behaviorProfile.medianOrderRoiRate())
                .settlementCompletionRate(behaviorProfile == null ? null : behaviorProfile.settlementCompletionRate())
                .earlyExitRate(behaviorProfile == null ? null : behaviorProfile.earlyExitRate())
                .profitEarlyExitRate(behaviorProfile == null ? null : behaviorProfile.profitEarlyExitRate())
                .lossEarlyExitRate(behaviorProfile == null ? null : behaviorProfile.lossEarlyExitRate())
                .earlySettlementRate(behaviorProfile == null ? null : behaviorProfile.earlySettlementRate())
                .winSourceType(behaviorProfile == null ? null : behaviorProfile.winSourceType())
                .settlementWinRate(behaviorProfile == null ? null : behaviorProfile.settlementWinRate())
                .stabilityScore(behaviorProfile == null ? null : behaviorProfile.stabilityScore())
                .qualityScore(behaviorProfile == null ? null : behaviorProfile.qualityScore())
                .replicabilityScore(behaviorProfile == null ? null : behaviorProfile.replicabilityScore())
                .recommendedExitLane(behaviorProfile == null ? null : behaviorProfile.recommendedExitLane())
                .summary(behaviorProfile != null && behaviorProfile.summary() != null
                        ? behaviorProfile.summary()
                        : archetype.summary())
                .build();
    }

    private SmartWalletProfileOpsCandidateView toOpsCandidateView(SmartWalletProfileEntity profile,
                                                                  WalletBehaviorProfileView behaviorProfile) {
        return SmartWalletProfileOpsCandidateView.builder()
                .walletAddress(profile.getWalletAddress())
                .alias(profile.getAlias())
                .qualified(profile.getQualified())
                .watchEnabled(profile.getWatchEnabled())
                .manualWatchEnabled(profile.getManualWatchEnabled())
                .manualRecommendedExitLane(profile.getManualRecommendedExitLane())
                .manualOverrideUpdatedAt(profile.getManualOverrideUpdatedAt())
                .archetype(behaviorProfile == null ? null : behaviorProfile.archetype())
                .followLane(behaviorProfile == null ? null : behaviorProfile.followLane())
                .highFrequencyTradingAddress(behaviorProfile == null ? null : behaviorProfile.highFrequencyTradingAddress())
                .orderWinRate(behaviorProfile == null ? null : behaviorProfile.orderWinRate())
                .averageOrderRoiRate(behaviorProfile == null ? null : behaviorProfile.averageOrderRoiRate())
                .settlementCompletionRate(behaviorProfile == null ? null : behaviorProfile.settlementCompletionRate())
                .earlyExitRate(behaviorProfile == null ? null : behaviorProfile.earlyExitRate())
                .replicabilityScore(behaviorProfile == null ? null : behaviorProfile.replicabilityScore())
                .recommendedExitLane(behaviorProfile == null ? null : behaviorProfile.recommendedExitLane())
                .summary(behaviorProfile == null ? null : behaviorProfile.summary())
                .build();
    }

    private boolean matchesOverrideCandidateFilters(SmartWalletProfileOpsCandidateView candidate,
                                                    String recommendedExitLane,
                                                    Boolean watchEnabled,
                                                    Boolean qualified,
                                                    Boolean manualWatchEnabled,
                                                    boolean manualOnly,
                                                    Long updatedCutoffMillis,
                                                    Boolean highFrequencyTradingAddress,
                                                    BigDecimal minReplicabilityScore,
                                                    BigDecimal maxReplicabilityScore) {
        if (candidate == null) {
            return false;
        }
        if (watchEnabled != null && !watchEnabled.equals(candidate.watchEnabled())) {
            return false;
        }
        if (qualified != null && !qualified.equals(candidate.qualified())) {
            return false;
        }
        if (manualWatchEnabled != null && !manualWatchEnabled.equals(candidate.manualWatchEnabled())) {
            return false;
        }
        if (manualOnly
                && candidate.manualWatchEnabled() == null
                && candidate.manualRecommendedExitLane() == null
                && candidate.manualOverrideUpdatedAt() == null) {
            return false;
        }
        if (recommendedExitLane != null) {
            String actualLane = candidate.recommendedExitLane() == null ? "" : candidate.recommendedExitLane().trim();
            if (!recommendedExitLane.trim().equalsIgnoreCase(actualLane)) {
                return false;
            }
        }
        if (updatedCutoffMillis != null) {
            if (candidate.manualOverrideUpdatedAt() == null || candidate.manualOverrideUpdatedAt().getTime() < updatedCutoffMillis) {
                return false;
            }
        }
        if (highFrequencyTradingAddress != null
                && !highFrequencyTradingAddress.equals(candidate.highFrequencyTradingAddress())) {
            return false;
        }
        if (minReplicabilityScore != null
                && (candidate.replicabilityScore() == null || candidate.replicabilityScore().compareTo(minReplicabilityScore) < 0)) {
            return false;
        }
        if (maxReplicabilityScore != null
                && candidate.replicabilityScore() != null
                && candidate.replicabilityScore().compareTo(maxReplicabilityScore) > 0) {
            return false;
        }
        return true;
    }

    private int parseCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    @Operation(summary = "地址时段覆盖诊断", description = "按 dominant_session 聚合 profiles 与 watchlist，查看地址池是否集中在某个区域/时段。")
    @GetMapping("/discovery/session-coverage")
    public DiscoverySessionCoverageView discoverySessionCoverage() {
        return polymarketWalletDiscoveryService.sessionCoverage();
    }

    @Operation(summary = "共识信号", description = "查看最近窗口内多个 smart wallet 在同一市场上的聚合共识信号。")
    @GetMapping("/consensus-signals")
    public List<ConsensusSignalView> consensusSignals(@RequestParam(required = false) Integer windowSeconds,
                                                      @RequestParam(required = false) String marketId,
                                                      @RequestParam(required = false) String outcome,
                                                      @RequestParam(required = false) String side,
                                                      @RequestParam(required = false) Integer limit) {
        return consensusSignalService.listConsensusSignals(windowSeconds, marketId, outcome, side, limit);
    }

    @Operation(summary = "市场数据诊断", description = "查看 Polymarket WS 本地缓存和 snapshot 采集命中/降级统计，便于离线联调。")
    @GetMapping("/polymarket/market-data/diagnostics")
    public PolymarketMarketDataDiagnosticsView marketDataDiagnostics() {
        PolymarketMarketDataStreamService.MarketDataStreamStats wsStats = marketDataStreamService.getStats();
        MarketSnapshotCollectorService.SnapshotCollectionStats snapshotStats = marketSnapshotCollectorService.snapshotStats();
        return PolymarketMarketDataDiagnosticsView.builder()
                .wsEnabled(wsStats.isEnabled())
                .wsConnected(wsStats.isConnected())
                .subscribedTokenCount(wsStats.getSubscribedTokenCount())
                .cachedTokenCount(wsStats.getCachedTokenCount())
                .messagesReceived(wsStats.getMessagesReceived())
                .bookEventsReceived(wsStats.getBookEventsReceived())
                .priceEventsReceived(wsStats.getPriceEventsReceived())
                .lastEventAt(wsStats.getLastEventAt())
                .wsSnapshotHits(snapshotStats.getWsHits())
                .restFallbacks(snapshotStats.getRestFallbacks())
                .build();
    }

    @Operation(summary = "注入 Mock WS 事件", description = "向本地 market-data 缓存注入一条模拟 websocket 消息，不访问外网。")
    @PostMapping("/polymarket/market-data/mock-event")
    public Map<String, Object> injectMockMarketDataEvent(@RequestBody PolymarketMockWsEventRequest request) {
        marketDataStreamService.ingestMockEvent(request == null ? null : request.getPayload());
        return Map.of("accepted", true);
    }

    // [MIGRATED] POST /polymarket/market-data/collect-snapshot → coin-strategy-web SmartWalletPolymarketWriteController
    //            (action: SMART_WALLET_POLYMARKET_MARKET_DATA_COLLECT_SNAPSHOT)

    @Operation(summary = "导入钱包指标", description = "手动写入某个 Polymarket 钱包的胜率、PnL、样本数等指标。")
    @PostMapping("/polymarket/metrics")
    public SmartWalletProfileEntity ingestWalletMetrics(@RequestBody PolymarketWalletMetricsRequest request) {
        return polymarketMonitorService.ingestWalletMetrics(request);
    }

    @Operation(summary = "导入交易事件", description = "手动写入某个 Polymarket 钱包的开单事件，用于模拟监控和跟单。")
    @PostMapping("/polymarket/events")
    public SmartWalletTradeEventEntity ingestTradeEvent(@RequestBody PolymarketTradeEventRequest request) {
        return polymarketMonitorService.ingestTradeEvent(request);
    }

    // [MIGRATED] POST /polymarket/select → coin-strategy-web SmartWalletPolymarketWriteController
    //            (action: SMART_WALLET_POLYMARKET_SELECT)
    // [MIGRATED] POST /polymarket/analyze → coin-strategy-web SmartWalletPolymarketWriteController
    //            (action: SMART_WALLET_POLYMARKET_ANALYZE)
    // [MIGRATED] POST /polymarket/watchlist/admit → coin-strategy-web SmartWalletPolymarketWriteController
    //            (action: SMART_WALLET_POLYMARKET_WATCHLIST_ADMIT)
    // [MIGRATED] POST /polymarket/sync-events → coin-strategy-web SmartWalletPolymarketWriteController
    //            (action: SMART_WALLET_POLYMARKET_SYNC_EVENTS)
    // [MIGRATED] POST /polymarket/closed-positions/backfill-local → coin-strategy-web SmartWalletPolymarketWriteController
    //            (action: SMART_WALLET_POLYMARKET_CLOSED_POSITIONS_BACKFILL_LOCAL)

    // [MIGRATED] GET /follow-tasks → coin-strategy-web SmartWalletReadController.followTasks (web-native impl)
    // [MIGRATED] GET /follow-tasks/executable → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_EXECUTABLE)
    // [MIGRATED] GET /follow-tasks/manual-allowlist → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_MANUAL_ALLOWLIST)
    // [MIGRATED] POST /follow-tasks/intelligence/reevaluate-deferred → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REEVALUATE_DEFERRED)
    // [MIGRATED] POST /follow-tasks/intelligence/replay-recent → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_INTELLIGENCE_REPLAY_RECENT)

    // [MIGRATED] GET /follow-tasks/single-wallet-override → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_SINGLE_WALLET_OVERRIDE)
    // [MIGRATED] GET /follow-tasks/opportunities → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_OPPORTUNITIES)
    // [MIGRATED] GET /follow-tasks/blocker-stats → coin-strategy-web SmartWalletReadController.blockerStats (web-native impl)
    // [MIGRATED] GET /follow-trades/attribution → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TRADES_ATTRIBUTION)

    @Operation(summary = "冲突市场报表", description = "查看同一市场持有多个 outcome 的冲突持仓与对应收益情况。")
    @GetMapping("/reports/conflicting-markets")
    public List<SmartWalletConflictMarketView> conflictingMarkets(@RequestParam(defaultValue = "50") Integer limit,
                                                                  @RequestParam(defaultValue = "false") boolean openOnly) {
        return accountingService.conflictingMarkets(limit, openOnly);
    }

    @Operation(summary = "风控预设配置", description = "查看默认风控参数和所有 signal-risk preset。")
    @GetMapping("/config/signal-risk-presets")
    public SignalRiskPresetCatalogView signalRiskPresets() {
        return riskPolicyApplicationService.presets();
    }

    @Operation(summary = "Live 调试配置", description = "查看 live-validation 绑定到 Spring 配置后的实际值，便于排查配置覆盖。")
    @GetMapping("/config/live-validation-debug")
    public Map<String, Object> liveValidationDebug() {
        SmartWalletProperties.LiveValidation config = properties.getLiveValidation();
        return Map.of(
                "environmentEnabledRaw", environment.getProperty("smart-wallet.live-validation.enabled"),
                "environmentPresetRaw", environment.getProperty("smart-wallet.live-validation.preset"),
                "boundEnabled", config.isEnabled(),
                "boundPreset", config.getPreset(),
                "boundCandidateScanLimit", config.getCandidateScanLimit(),
                "boundMaxExecutionsPerRun", config.getMaxExecutionsPerRun()
        );
    }

    @Operation(summary = "Baseline 模型配置", description = "查看当前 P1 baseline 风险/滑点模型和校准参数的运行时配置。")
    @GetMapping("/config/baseline-model")
    public BaselineModelConfigView baselineModelConfig() {
        return baselineModelConfigApplicationService.current();
    }

    @Operation(summary = "更新 Baseline 模型配置", description = "热更新当前进程中的 baseline 模型和校准参数，不修改 application.yml。")
    @PostMapping("/config/baseline-model")
    public BaselineModelConfigView updateBaselineModelConfig(@RequestBody(required = false) UpdateBaselineModelConfigRequest request) {
        assertRuntimeConfigApiEnabled();
        return baselineModelConfigApplicationService.update(request);
    }

    @Operation(summary = "运行时配置目录", description = "查看 Polymarket copy 策略允许页面编辑的配置白名单和分组。")
    @GetMapping("/config/runtime/catalog")
    public StrategyRuntimeConfigCatalogView runtimeConfigCatalog(@RequestParam(defaultValue = StrategyRuntimeConfigService.POLYMARKET_COPY) String strategyCode) {
        assertRuntimeConfigApiEnabled();
        return strategyRuntimeConfigService.catalog(strategyCode);
    }

    @Operation(summary = "运行时配置", description = "查看当前 Polymarket copy 策略的可编辑运行时配置。")
    @GetMapping("/config/runtime")
    public List<StrategyRuntimeConfigView> runtimeConfig(@RequestParam(defaultValue = StrategyRuntimeConfigService.POLYMARKET_COPY) String strategyCode) {
        assertRuntimeConfigApiEnabled();
        return strategyRuntimeConfigService.current(strategyCode);
    }

    @Operation(summary = "更新运行时配置", description = "保存 Polymarket copy 策略运行时配置到数据库，并立即同步当前进程。")
    @PostMapping("/config/runtime")
    public List<StrategyRuntimeConfigView> updateRuntimeConfig(@RequestParam(defaultValue = StrategyRuntimeConfigService.POLYMARKET_COPY) String strategyCode,
                                                              @RequestBody(required = false) UpdateStrategyRuntimeConfigRequest request) {
        assertRuntimeConfigApiEnabled();
        return strategyRuntimeConfigService.update(strategyCode, request);
    }

    @Operation(summary = "重置运行时配置", description = "删除数据库覆盖值并恢复当前进程内 YAML 默认配置。")
    @PostMapping("/config/runtime/reset")
    public List<StrategyRuntimeConfigView> resetRuntimeConfig(@RequestParam(defaultValue = StrategyRuntimeConfigService.POLYMARKET_COPY) String strategyCode,
                                                             @RequestBody(required = false) ResetStrategyRuntimeConfigRequest request) {
        assertRuntimeConfigApiEnabled();
        return strategyRuntimeConfigService.reset(strategyCode, request);
    }

    private void assertRuntimeConfigApiEnabled() {
        if (!properties.isRuntimeConfigApiEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Runtime config API is disabled until an authenticated admin module is available.");
        }
    }

    // [MIGRATED] POST /follow-tasks/simulate-risk → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_SIMULATE_RISK)
    // [MIGRATED] POST /follow-tasks/replay-backtest → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST)
    // [MIGRATED] POST /follow-tasks/replay-backtest/concentration-summary → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_REPLAY_BACKTEST_CONCENTRATION)
    // [MIGRATED] POST /follow-tasks/process → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_PROCESS, long task — enqueue + GET status)

    @Operation(summary = "Live 验证状态", description = "查看 LIVE 小额验证辅助流程的启停、熔断与最近一轮执行情况。")
    @GetMapping("/live-validation/status")
    public LiveValidationStatusView liveValidationStatus() {
        return liveValidationService.status();
    }

    @Operation(summary = "触发 Live 验证", description = "手动触发一轮 LIVE 小额验证流程。")
    @PostMapping("/live-validation/process")
    public LiveValidationRunSummary processLiveValidation() {
        return liveValidationService.processNow();
    }

    @GetMapping("/wallets")
    public List<SmartWalletProfile> wallets() {
        return followService.listWalletsForScan();
    }

    @PostMapping("/wallets")
    public SmartWalletProfile upsertWallet(@RequestBody UpsertWalletRequest request) {
        return walletRegistryService.upsert(request);
    }

    @GetMapping("/markets")
    public List<PredictionMarketOpportunity> markets() {
        return opportunityService.list();
    }

    @PostMapping("/markets")
    public PredictionMarketOpportunity upsertMarket(@RequestBody UpsertMarketRequest request) {
        return opportunityService.upsert(request);
    }

    @PostMapping("/scan")
    public SmartWalletScanResponse scan(@RequestParam(defaultValue = "1000") BigDecimal bankrollUsdc) {
        return followService.scan(bankrollUsdc);
    }

    @PostMapping("/execute/{signalId}")
    public SmartWalletExecutionResult execute(@PathVariable String signalId,
                                              @RequestParam(defaultValue = "1000") BigDecimal bankrollUsdc,
                                              @RequestParam(defaultValue = "PAPER") ExecutionMode mode) {
        return followService.executeSignal(signalId, bankrollUsdc, mode);
    }

    @Operation(summary = "执行钱包状态", description = "查看 execution wallet 是否已配置完成以及是否可进行真实下单。")
    @GetMapping("/execution-wallet/status")
    public ExecutionWalletStatus executionWalletStatus() {
        return executionApplicationService.executionWalletStatus();
    }

    /** @deprecated 建议迁移到 /orders/page 游标分页接口 */
    @Deprecated
    @Operation(summary = "订单列表（旧）", description = "查看 PAPER 或 LIVE 的订单记录。已改为内部游标分页实现，但返回格式不变，兼容现有前端。")
    @GetMapping("/orders")
    public List<SmartWalletOrderView> orders(@RequestParam(defaultValue = "100") Integer limit,
                                             @RequestParam(required = false) String mode) {
        return accountingService.listOrders(limit, mode);
    }

    @Operation(summary = "订单列表（游标分页）",
            description = "基于 lastId 游标分页查询订单，mode 过滤已下推到 SQL 层。首页不传 lastId，后续传上一页返回的 nextCursor。")
    @GetMapping("/orders/page")
    public CursorPageView<SmartWalletOrderView> ordersCursor(
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(required = false) Long lastId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String pnl,
            @RequestParam(required = false) String settlement,
            @RequestParam(required = false) String side,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return accountingService.listOrdersCursor(limit, lastId, mode, pnl, settlement, side, status, q);
    }

    @Operation(summary = "订单异常诊断", description = "按订单 ID 批量诊断当前表/归档表命中情况，并输出跟单钱包、source event、市场元数据和可选相似交易事件。")
    @GetMapping("/orders/diagnostics")
    public OrderDiagnosticReportView orderDiagnostics(@RequestParam List<Long> ids,
                                                      @RequestParam(required = false) String question,
                                                      @RequestParam(required = false) String outcome,
                                                      @RequestParam(required = false) BigDecimal price) {
        return abnormalMarketReviewService.diagnoseOrders(ids, question, outcome, price);
    }

    @Operation(summary = "持仓市场异常诊断", description = "按 positionId 批量诊断市场元数据、source trade 与可选 Polymarket 事件 URL 精确匹配结果。eventUrls 参数格式：positionId=https://polymarket.com/sports/...")
    @GetMapping("/positions/market-diagnostics")
    public List<PositionMarketDiagnosticView> positionMarketDiagnostics(@RequestParam List<Long> ids,
                                                                        @RequestParam(required = false) List<String> eventUrls) {
        return abnormalMarketReviewService.diagnosePositions(ids, eventUrls);
    }

    @Operation(summary = "交易列表（仓位聚合）",
            description = "以 position 为一笔交易，聚合 entry/exit orders，适合前端默认展示。")
    @GetMapping("/trades/page")
    public CursorPageView<SmartWalletTradeView> tradesCursor(
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(required = false) Long lastId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String pnl,
            @RequestParam(required = false) String settlement,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return accountingService.listTradesCursor(limit, lastId, mode, pnl, settlement, status, q);
    }

    @Operation(summary = "订单记录列表",
            description = "一行表示一笔完整 Polymarket 交易生命周期；BUY/SELL 执行订单只作为明细。")
    @GetMapping("/trade-orders/page")
    public CursorPageView<SmartWalletTradeOrderView> tradeOrdersCursor(
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(required = false) Long lastId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String pnl,
            @RequestParam(required = false) String settlement,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        return tradeOrderService.listTradeOrdersCursor(limit, lastId, mode, pnl, settlement, status, q);
    }

    @Operation(summary = "市场类型盈亏订单归因",
            description = "按 30d/7d/all 汇总当前盈利与亏损订单数，并分别输出市场类别维度和钱包地址主要市场类别维度。")
    @GetMapping("/trade-orders/pnl-category-attribution")
    public SmartWalletOrderPnlCategoryAttributionView orderPnlCategoryAttribution(
            @RequestParam(required = false) String mode,
            @RequestParam(defaultValue = "12") Integer limit) {
        return tradeOrderService.orderPnlCategoryAttribution(mode, limit);
    }

    // [MIGRATED] POST /trade-orders/backfill 与 POST /trade-orders/repair-stale 已下沉到
    //            coin-strategy-web 的 SmartWalletTradeOrdersWriteController，通过 strategy_manual_trigger
    //            命令队列同步派发到 SmartWalletTradeOrderService。job 端只保留 service，不再暴露 HTTP。
    // [MIGRATED] POST /markets/recategorize 已下沉到 coin-strategy-web 的
    //            SmartWalletTradeOrdersWriteController，通过 strategy_manual_trigger 命令队列同步派发到
    //            SmartWalletMarketStateService。job 端只保留 service，不再暴露 HTTP。

    // [MIGRATED] POST /profiles/sync-all-positions + GET /profiles/sync-all-positions/{id}
    // 已迁到 coin-strategy-web 的 SmartWalletProfileWriteController；
    // 执行逻辑封装为 SmartWalletProfileAsyncOpsService.syncAllPositions(...)，
    // 通过 strategy_manual_trigger 命令队列驱动，状态/结果存 DB。

    @SuppressWarnings("unused") // 兼容旧引用；新代码请用 SmartWalletProfileAsyncOpsService。
    private Map<String, Object> dispatchTierSync(String tier, int batchSize) {
        long tierStart = System.currentTimeMillis();
        ClosedPositionSyncDispatcher.DispatchResult result =
                closedPositionSyncDispatcher.dispatchIncremental(java.util.List.of(tier), batchSize);
        long elapsedMs = System.currentTimeMillis() - tierStart;
        return Map.of(
                "tier", tier,
                "total", result.total(),
                "ok", result.ok(),
                "failed", result.failed(),
                "inserted", result.inserted(),
                "elapsedMs", elapsedMs);
    }

    @Operation(summary = "导出订单 CSV", description = "导出 PAPER 或 LIVE 的订单记录，包含总手续费、交易手续费、链上手续费和 POL。")
    @GetMapping(value = "/orders/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportOrders(@RequestParam(defaultValue = "5000") Integer limit,
                                               @RequestParam(required = false) String mode) {
        List<SmartWalletOrderView> rows = accountingService.listOrders(limit, mode);
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "side", "orderType", "status",
                        "notionalUsdc", "costBasisUsdc", "feeUsdc", "tradingFeeUsdc", "gasFeeUsdc", "feePol",
                        "realizedPnlUsdc", "exitReason", "placedAt", "filledAt"),
                rows.stream().map(row -> java.util.Arrays.asList(
                        row.getId(), row.getExecutionAddress(), row.getMarketId(), row.getQuestion(), row.getOutcome(),
                        row.getSide(), row.getOrderType(), row.getStatus(), row.getNotionalUsdc(), row.getCostBasisUsdc(),
                        row.getFeeUsdc(), row.getTradingFeeUsdc(), row.getGasFeeUsdc(), row.getFeePol(),
                        row.getRealizedPnlUsdc(), row.getExitReason(), row.getPlacedAt(), row.getFilledAt()
                )).toList(),
                "smart_wallet_orders", mode);
    }

    @Operation(summary = "成交列表", description = "查看 PAPER 或 LIVE 的成交明细。")
    @GetMapping("/fills")
    public List<SmartWalletFillView> fills(@RequestParam(defaultValue = "100") Integer limit,
                                           @RequestParam(required = false) String mode) {
        return accountingService.listFills(limit, mode);
    }

    @Operation(summary = "导出成交 CSV", description = "导出 PAPER 或 LIVE 的成交明细，包含总手续费、交易手续费、链上手续费和 POL。")
    @GetMapping(value = "/fills/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportFills(@RequestParam(defaultValue = "5000") Integer limit,
                                              @RequestParam(required = false) String mode) {
        List<SmartWalletFillView> rows = accountingService.listFills(limit, mode);
        return csvResponse(
                List.of("id", "orderId", "executionAddress", "marketId", "question", "outcome", "side", "price", "size",
                        "notionalUsdc", "feeUsdc", "tradingFeeUsdc", "gasFeeUsdc", "feePol", "realizedPnlUsdc", "filledAt"),
                rows.stream().map(row -> java.util.Arrays.asList(
                        row.getId(), row.getOrderId(), row.getExecutionAddress(), row.getMarketId(), row.getQuestion(),
                        row.getOutcome(), row.getSide(), row.getPrice(), row.getSize(), row.getNotionalUsdc(),
                        row.getFeeUsdc(), row.getTradingFeeUsdc(), row.getGasFeeUsdc(), row.getFeePol(),
                        row.getRealizedPnlUsdc(), row.getFilledAt()
                )).toList(),
                "smart_wallet_fills", mode);
    }

    @Operation(summary = "容量摘要", description = "查看当前组合剩余仓位槽位、资金预算和基础风控限制。")
    @GetMapping("/capacity/summary")
    public SmartWalletCapacitySummaryView capacitySummary() {
        return strategyAnalyticsApplicationService.capacitySummary();
    }

    @Operation(summary = "当前持仓", description = "查看当前仍活跃的持仓记录，不包含 CLOSED 或 CLAIMED 仓位。")
    @GetMapping("/positions/live")
    public List<SmartWalletPositionView> livePositions(@RequestParam(required = false) String mode) {
        return accountingService.listActivePositions(mode);
    }

    @Operation(summary = "导出当前持仓 CSV", description = "导出当前活跃持仓，包含干净 cost basis 与累计 POL gas。")
    @GetMapping(value = "/positions/live/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportLivePositions(@RequestParam(required = false) String mode) {
        List<SmartWalletPositionView> rows = accountingService.listActivePositions(mode);
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "status", "quantity",
                        "averageEntryPrice", "costBasisUsdc", "originalCostBasisUsdc", "feePolTotal",
                        "realizedPnlUsdc", "unrealizedPnlUsdc", "pendingSettlementNetPnlUsdc",
                        "claimableProceedsUsdc", "marketEndAtBjt", "openedAt", "lastFilledAt", "closedAt"),
                rows.stream().map(row -> java.util.Arrays.asList(
                        row.getId(), row.getExecutionAddress(), row.getMarketId(), row.getQuestion(), row.getOutcome(),
                        row.getStatus(), row.getQuantity(), row.getAverageEntryPrice(), row.getCostBasisUsdc(),
                        row.getOriginalCostBasisUsdc(), row.getFeePolTotal(), row.getRealizedPnlUsdc(),
                        row.getUnrealizedPnlUsdc(), row.getPendingSettlementNetPnlUsdc(), row.getClaimableProceedsUsdc(),
                        row.getMarketEndAtBjt(), row.getOpenedAt(), row.getLastFilledAt(), row.getClosedAt()
                )).toList(),
                "smart_wallet_live_positions", mode);
    }

    @Operation(summary = "待结算处理持仓", description = "查看已到结算窗口或待领取的仓位，时间已带北京时间字段。")
    @GetMapping("/positions/next-settlements")
    public List<SmartWalletPositionView> nextSettlements(@RequestParam(defaultValue = "10") Integer limit,
                                                         @RequestParam(required = false) String mode) {
        return accountingService.listNextSettlements(limit, mode);
    }

    @Operation(summary = "导出待结算持仓 CSV", description = "导出待结算或待领取持仓，包含干净 cost basis 与累计 POL gas。")
    @GetMapping(value = "/positions/next-settlements/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportNextSettlements(@RequestParam(defaultValue = "5000") Integer limit,
                                                        @RequestParam(required = false) String mode) {
        List<SmartWalletPositionView> rows = accountingService.listNextSettlements(limit, mode);
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "status", "quantity",
                        "costBasisUsdc", "feePolTotal", "pendingSettlementNetPnlUsdc", "claimableProceedsUsdc",
                        "marketEndAtBjt", "openedAt", "lastFilledAt", "closedAt"),
                rows.stream().map(row -> java.util.Arrays.asList(
                        row.getId(), row.getExecutionAddress(), row.getMarketId(), row.getQuestion(), row.getOutcome(),
                        row.getStatus(), row.getQuantity(), row.getCostBasisUsdc(), row.getFeePolTotal(),
                        row.getPendingSettlementNetPnlUsdc(), row.getClaimableProceedsUsdc(),
                        row.getMarketEndAtBjt(), row.getOpenedAt(), row.getLastFilledAt(), row.getClosedAt()
                )).toList(),
                "smart_wallet_next_settlements", mode);
    }

    @Operation(summary = "历史持仓（分页）", description = "查看历史持仓，可选只返回 closed/resolved 记录。强制分页，默认 page=1, size=50。")
    @GetMapping("/positions/history")
    public OffsetPageView<SmartWalletPositionView> positionHistory(
            @RequestParam(defaultValue = "false") boolean closedOnly,
            @RequestParam(required = false) String mode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(1, page);
        List<SmartWalletPositionView> all = accountingService.listPositions(closedOnly, mode);
        int totalItems = all.size();
        int fromIndex = Math.min((safePage - 1) * safeSize, totalItems);
        int toIndex = Math.min(fromIndex + safeSize, totalItems);
        List<SmartWalletPositionView> pageItems = all.subList(fromIndex, toIndex);
        return OffsetPageView.of(pageItems, safePage, safeSize, totalItems);
    }

    @Operation(summary = "导出历史持仓 CSV", description = "导出历史持仓，可选只导出 closed/resolved 记录。")
    @GetMapping(value = "/positions/history/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> exportPositionHistory(@RequestParam(defaultValue = "false") boolean closedOnly,
                                                        @RequestParam(required = false) String mode) {
        List<SmartWalletPositionView> rows = accountingService.listPositions(closedOnly, mode);
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "status", "quantity",
                        "averageEntryPrice", "costBasisUsdc", "originalCostBasisUsdc", "feePolTotal",
                        "realizedPnlUsdc", "unrealizedPnlUsdc", "pendingSettlementNetPnlUsdc",
                        "claimableProceedsUsdc", "marketEndAtBjt", "openedAt", "lastFilledAt", "closedAt"),
                rows.stream().map(row -> java.util.Arrays.asList(
                        row.getId(), row.getExecutionAddress(), row.getMarketId(), row.getQuestion(), row.getOutcome(),
                        row.getStatus(), row.getQuantity(), row.getAverageEntryPrice(), row.getCostBasisUsdc(),
                        row.getOriginalCostBasisUsdc(), row.getFeePolTotal(), row.getRealizedPnlUsdc(),
                        row.getUnrealizedPnlUsdc(), row.getPendingSettlementNetPnlUsdc(), row.getClaimableProceedsUsdc(),
                        row.getMarketEndAtBjt(), row.getOpenedAt(), row.getLastFilledAt(), row.getClosedAt()
                )).toList(),
                closedOnly ? "smart_wallet_closed_positions" : "smart_wallet_position_history", mode);
    }

    @Operation(summary = "资金流水", description = "查看开单、结算、已实现盈亏等资金明细流水。")
    @GetMapping("/cash-ledger")
    public List<SmartWalletCashLedgerView> cashLedger(@RequestParam(defaultValue = "100") Integer limit) {
        return accountingService.listLedger(limit);
    }

    @Operation(summary = "盈亏汇总", description = "查看总订单、胜率、已实现与未实现盈亏等总览。")
    @GetMapping("/pnl/summary")
    public SmartWalletPnlSummaryView pnlSummary(@RequestParam(required = false) String mode) {
        return accountingService.summary(mode);
    }

    @Operation(summary = "绩效报表", description = "查看胜率、盈亏比、回撤、冲突市场等综合绩效指标。")
    @GetMapping("/reports/performance")
    public SmartWalletPerformanceView performanceReport() {
        return strategyAnalyticsApplicationService.performance();
    }

    @Operation(summary = "盈亏时间线", description = "按可选时间桶查看收益变化曲线。")
    @GetMapping("/pnl/timeline")
    public List<SmartWalletPnlTimelinePoint> pnlTimeline(@RequestParam(defaultValue = "30") Integer days,
                                                         @RequestParam(defaultValue = "24") Integer bucketHours,
                                                         @RequestParam(required = false) String mode) {
        return strategyAnalyticsApplicationService.pnlTimeline(days, bucketHours, mode);
    }

    @Operation(summary = "日报列表", description = "查看按日汇总的收益、胜率与绩效数据。")
    @GetMapping("/reports/daily")
    public List<SmartWalletDailyReportView> dailyReports(@RequestParam(defaultValue = "30") Integer limit,
                                                         @RequestParam(required = false) String mode) {
        return accountingService.listDailyReports(limit, mode);
    }

    @Operation(summary = "重建日报", description = "按最近天数重建 daily report 聚合数据。")
    @PostMapping("/reports/daily/rebuild")
    public Map<String, Object> rebuildDailyReports(@RequestParam(defaultValue = "7") Integer days) {
        return Map.of("rebuilt", accountingService.rebuildRecentReports(days));
    }

    // [MIGRATED] /accounting/* (6 POST), /settlement/* (4 GET + 4 POST), /claim/* (4 GET + 4 POST,
    // 其中 2 个 GET 为 status/runs，另外两个为 retries/report；4 POST 为 process / retries/process /
    // sync / review-paper-history / retries/process / manual-confirm-local) 以及
    // POST /positions/{positionId}/claim、POST /positions/{positionId}/exit 已下沉到 coin-strategy-web：
    //   - SmartWalletAccountingController（6 个 accounting POST）
    //   - SmartWalletSettlementController（4 个 settlement POST，GET 已在 SmartWalletReadController）
    //   - SmartWalletClaimController（2 个 claim POST，GET 已在 SmartWalletReadController）
    //   - SmartWalletPositionWriteController（claim / exit 单笔 POST）
    // 写操作通过 strategy_manual_trigger 命令队列异步派发到 job 端的：
    //   SmartWalletAccountingService / SmartWalletMarketRepairService /
    //   SettlementApplicationService / SmartWalletSettlementSyncService /
    //   SmartWalletClaimService。
    // job 端只保留 service，不再暴露 HTTP。

    @Operation(summary = "钱包归因报表", description = "按执行钱包 executionAddress 查看收益归因报表。")
    @GetMapping("/reports/wallets")
    public List<SmartWalletReportAttributionItem> walletReports(@RequestParam(defaultValue = "20") Integer limit,
                                                                @RequestParam(required = false) String mode) {
        return accountingService.walletAttribution(limit, mode);
    }

    @Operation(summary = "跟单地址日报", description = "按被跟单的钱包地址输出每日投资分析报表，包含盈利/亏损笔数、胜率、净盈利、手续费、成交额与平均盈亏。")
    @GetMapping("/reports/follow-addresses/daily")
    public List<SmartWalletFollowAddressDailyReportView> followAddressDailyReports(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate,
            @RequestParam(defaultValue = "500") Integer limit,
            @RequestParam(required = false) String mode) {
        return accountingService.followAddressDailyReport(reportDate, limit, mode);
    }

    @Operation(summary = "市场归因报表", description = "按市场维度汇总成交量、账本收益与当前未落袋暴露。")
    @GetMapping("/reports/markets")
    public List<SmartWalletReportAttributionItem> marketReports(@RequestParam(defaultValue = "20") Integer limit,
                                                                @RequestParam(required = false) String mode) {
        return accountingService.marketAttribution(limit, mode);
    }

    @Operation(summary = "市场监控总览", description = "面向前端市场页的聚合接口，汇总当前监控市场、有效市场、高集中/极端集中市场、趋势和市场表格。")
    @GetMapping("/reports/market-monitoring")
    public SmartWalletMarketMonitoringSummaryView marketMonitoringSummary(
            @RequestParam(defaultValue = "40") Integer limit,
            @RequestParam(defaultValue = "14") Integer lookbackDays,
            @RequestParam(defaultValue = "7") Integer trendDays) {
        return smartWalletMarketMonitoringService.summary(limit, lookbackDays, trendDays);
    }

    @Operation(summary = "市场状态表分页", description = "仅供市场状态表使用的分页接口，支持按关键词、分类、集中度、是否有持仓和持仓方向筛选。")
    @GetMapping("/reports/market-monitoring/page")
    public OffsetPageView<SmartWalletMarketMonitorItemView> marketMonitoringTablePage(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(defaultValue = "14") Integer lookbackDays,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String concentrationBand,
            @RequestParam(required = false) Boolean hasPosition,
            @RequestParam(required = false) String positionDirection) {
        return smartWalletMarketMonitoringService.tablePage(
                page,
                pageSize,
                lookbackDays,
                keyword,
                category,
                concentrationBand,
                hasPosition,
                positionDirection);
    }

    @Operation(summary = "派生 CLOB API 凭证", description = "基于执行钱包派生 Polymarket CLOB API key、secret 与 passphrase。")
    @PostMapping("/execution-wallet/derive-api-creds")
    public Map<String, Object> deriveExecutionWalletApiCreds() {
        return executionApplicationService.deriveApiCreds();
    }

    @Operation(summary = "查看实时执行钱包状态", description = "直接从 Polymarket 查询执行钱包余额与远端持仓，便于核对真实 LIVE 账户状态。")
    @GetMapping("/execution-wallet/live-state")
    public Map<String, Object> executionWalletLiveState(@RequestParam(required = false) String conditionId) {
        return clobExecutionService.liveWalletState(conditionId);
    }

    @Operation(summary = "CLOB 通用预演", description = "按 market/outcome/stake 预演订单参数，不会真实下单。")
    @PostMapping("/clob/dry-run")
    public Map<String, Object> clobDryRun(@RequestBody PolymarketOrderDryRunRequest request) {
        return clobExecutionService.dryRunOrder(request);
    }

    // [MIGRATED] POST /follow-tasks/{followTaskId}/dry-run → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_DRY_RUN)
    // [MIGRATED] POST /follow-tasks/{followTaskId}/execute-live → coin-strategy-web SmartWalletFollowTasksController
    //            (action: SMART_WALLET_FOLLOW_TASKS_EXECUTE_LIVE)

    @Operation(summary = "查看市场元数据", description = "按 conditionId 查看 Polymarket 市场元数据、tokenId 与价格相关信息。")
    @GetMapping("/clob/markets/{conditionId}")
    public Object clobMarketMetadata(@PathVariable String conditionId) {
        return clobExecutionService.marketMetadata(conditionId);
    }

    @Operation(summary = "解析 Polymarket 市场链接", description = "按 conditionId 精确解析外部 Polymarket URL；解析失败时返回内部详情页 URL。")
    @GetMapping("/polymarket/markets/{conditionId}/link")
    public SmartWalletMarketLinkView polymarketMarketLink(@PathVariable String conditionId,
                                                          @RequestParam(required = false) String question,
                                                          @RequestParam(required = false) String outcome) {
        return marketLinkService.resolve(conditionId, question, outcome);
    }

    @Operation(summary = "异常市场人工核验", description = "显式确认权威 conditionId、标记元数据无效，或保持等待 oracle。不会按相似市场自动修复。")
    @PostMapping("/markets/manual-review")
    public AbnormalMarketManualReviewResult manualReviewMarket(@RequestBody AbnormalMarketManualReviewRequest request) {
        return abnormalMarketReviewService.review(request);
    }

    @Operation(summary = "查看实时价格参考", description = "按 conditionId/outcome 查看当前 token 的 best bid/ask、midpoint 与回退价格参考。")
    @GetMapping("/clob/markets/{conditionId}/price-reference")
    public Map<String, Object> clobMarketPriceReference(@PathVariable String conditionId,
                                                        @RequestParam(defaultValue = "YES") String outcome,
                                                        @RequestParam(required = false) String tokenId,
                                                        @RequestParam(required = false) BigDecimal fallbackPrice,
                                                        @RequestParam(required = false) String question) {
        return clobExecutionService.marketPriceReference(conditionId, outcome, tokenId, fallbackPrice, question);
    }

    // [MIGRATED] POST /clob/markets/{conditionId}/snapshot 与 POST /clob/markets/snapshots/backfill
    //            已下沉到 coin-strategy-web 的 SmartWalletClobWriteController，通过 strategy_manual_trigger
    //            命令队列同步派发到 PredictionMarketDataApplicationService。
    //            (actions: SMART_WALLET_CLOB_MARKET_SNAPSHOT / SMART_WALLET_CLOB_MARKETS_SNAPSHOTS_BACKFILL)

    // [MIGRATED] POST /trade-events/{sourceEventId}/effect-label 与
    //            POST /trade-events/effect-labels/backfill 已下沉到 coin-strategy-web 的
    //            SmartWalletTradeEventsWriteController，通过 strategy_manual_trigger 命令队列
    //            同步派发到 PredictionMarketDataApplicationService。job 端只保留 service，不再暴露 HTTP。

    @Operation(summary = "钱包日特征列表", description = "查看最近生成的 wallet_feature_daily 结果，用于 leader admission 和因果分析前置特征。")
    @GetMapping("/wallet-features/daily")
    public List<SmartWalletWalletFeatureDailyEntity> walletFeatureDaily(@RequestParam(defaultValue = "100") Integer limit) {
        return predictionMarketDataApplicationService.listWalletFeatureDaily(limit);
    }

    // [MIGRATED] POST /wallet-features/daily 与 POST /wallet-features/daily/backfill 已下沉到
    //            coin-strategy-web 的 SmartWalletWalletFeaturesWriteController，通过 strategy_manual_trigger
    //            命令队列同步派发到 PredictionMarketDataApplicationService。
    //            (actions: SMART_WALLET_WALLET_FEATURES_DAILY / SMART_WALLET_WALLET_FEATURES_DAILY_BACKFILL)

    @Operation(summary = "查询订单", description = "按 orderId 查询 CLOB 订单详情。")
    @GetMapping("/clob/orders/{orderId}")
    public Map<String, Object> clobOrder(@PathVariable String orderId) {
        return clobExecutionService.getOrder(orderId);
    }

    @Operation(summary = "撤销订单", description = "按 orderId 撤销 CLOB 订单。")
    @PostMapping("/clob/orders/{orderId}/cancel")
    public Map<String, Object> cancelClobOrder(@PathVariable String orderId) {
        return clobExecutionService.cancelOrder(orderId);
    }

    // [MIGRATED] applySingleWalletOverridePreset → coin-strategy-job SmartWalletFollowTasksOrchestrator
    //            (used by SMART_WALLET_FOLLOW_TASKS_SINGLE_WALLET_OVERRIDE command branch)

    // ──────────────────────────────────────────────────────────────────────────
    // 持仓集中度查询（防控盘分析）
    // ──────────────────────────────────────────────────────────────────────────

    @Operation(
            summary = "信号健康诊断",
            description = "汇总过去 N 小时内的信号漏斗、拒绝原因分布、资金状态与共识配置，" +
                    "用于快速定位策略长时间未开仓的根本原因。lookbackHours 默认 24，最大 72。")
    @GetMapping("/diagnostics/signal-health")
    public SignalHealthSummaryView signalHealth(@RequestParam(defaultValue = "24") int lookbackHours) {
        return followTaskService.getSignalHealthSummary(lookbackHours);
    }

    @Operation(
            summary = "查询市场持仓集中度",
            description = "返回指定市场的 CR_1/CR_3/CR_5 和 HHI 集中度指标，以及前5持仓者地址。" +
                    "用于判断是否存在控盘风险。结果缓存 60s。outcome 参数可选，不传则分析双向合并数据。")
    @GetMapping("/concentration/{conditionId}")
    public ConcentrationSnapshot marketConcentration(
            @PathVariable String conditionId,
            @RequestParam(required = false) String outcome) {
        return marketConcentrationService.analyze(conditionId, outcome);
    }

    @Operation(
            summary = "清除市场集中度缓存",
            description = "强制淘汰指定市场的集中度缓存，下次查询时重新拉取最新数据。")
    @PostMapping("/concentration/{conditionId}/invalidate")
    public String invalidateConcentrationCache(@PathVariable String conditionId) {
        marketConcentrationService.invalidate(conditionId);
        return "Concentration cache invalidated for market=" + conditionId;
    }

    private ResponseEntity<StreamingResponseBody> csvResponse(List<String> headers,
                                                               List<? extends List<?>> rows,
                                                               String prefix, String mode) {
        String suffix = mode == null || mode.isBlank() ? "all" : mode.trim().toLowerCase();
        String filename = prefix + "_" + suffix + "_" + LocalDateTime.now().format(EXPORT_TS) + ".csv";
        StreamingResponseBody body = outputStream -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            writer.write('\uFEFF');
            writer.write(String.join(",", headers));
            writer.newLine();
            for (List<?> row : rows) {
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        writer.write(',');
                    }
                    writer.write(csvCell(row.get(i)));
                }
                writer.newLine();
            }
            writer.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    private String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }

}
