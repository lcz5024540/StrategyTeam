package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.analytics.application.StrategyAnalyticsApplicationService;
import com.metaverse.coin.strategy.job.execution.application.ExecutionApplicationService;
import com.metaverse.coin.strategy.job.follow.application.FollowTaskApplicationService;
import com.metaverse.coin.strategy.job.marketdata.application.PredictionMarketDataApplicationService;
import com.metaverse.coin.strategy.job.risk.application.RiskPolicyApplicationService;
import com.metaverse.coin.strategy.job.settlement.application.SettlementApplicationService;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionWalletStatus;
import com.metaverse.coin.strategy.job.smartwallet.model.HistoricalPaperSettlementReviewView;
import com.metaverse.coin.strategy.job.smartwallet.model.LiveValidationRunSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.LiveValidationStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketOrderDryRunRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketProductOverview;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PortfolioAttributionItem;
import com.metaverse.coin.strategy.job.smartwallet.model.PortfolioPositionView;
import com.metaverse.coin.strategy.job.smartwallet.model.PortfolioSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskPresetCatalogView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFillView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCashLedgerView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletBlockerStatView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCapacitySummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletConflictMarketView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlTimelinePoint;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletDailyReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReportAttributionItem;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRunView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfile;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimRetryReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimRetryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimRunView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletRiskSimulationResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletScanResponse;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSelectionSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.UpsertMarketRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.UpsertWalletRequest;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketSmartWalletMonitorService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketConsensusSignalService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketClobExecutionService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketPortfolioAnalyticsService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketProductOverviewService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.PredictionMarketOpportunityService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletAccountingService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletClaimService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletLiveValidationService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletRegistryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSelectionService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSettlementSyncService;
import lombok.RequiredArgsConstructor;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/smart-wallet")
@RequiredArgsConstructor
@Tag(name = "Smart Wallet", description = "Polymarket 聪明钱包跟踪、模拟跟单、风控分析、结算与报表接口")
public class SmartWalletController {

    private final SmartWalletProperties properties;
    private final SmartWalletRegistryService walletRegistryService;
    private final PredictionMarketOpportunityService opportunityService;
    private final SmartWalletFollowService followService;
    private final SmartWalletAccountingService accountingService;
    private final PolymarketSmartWalletMonitorService polymarketMonitorService;
    private final PolymarketConsensusSignalService consensusSignalService;
    private final PolymarketClobExecutionService clobExecutionService;
    private final PolymarketPortfolioAnalyticsService portfolioAnalyticsService;
    private final PolymarketProductOverviewService productOverviewService;
    private final PolymarketWalletDiscoveryService polymarketWalletDiscoveryService;
    private final SmartWalletSelectionService selectionService;
    private final SmartWalletFollowTaskService followTaskService;
    private final SmartWalletClaimService claimService;
    private final SmartWalletLiveValidationService liveValidationService;
    private final SmartWalletSettlementSyncService settlementSyncService;
    private final Environment environment;
    private final PredictionMarketDataApplicationService predictionMarketDataApplicationService;
    private final FollowTaskApplicationService followTaskApplicationService;
    private final ExecutionApplicationService executionApplicationService;
    private final SettlementApplicationService settlementApplicationService;
    private final RiskPolicyApplicationService riskPolicyApplicationService;
    private final StrategyAnalyticsApplicationService strategyAnalyticsApplicationService;

    @Operation(summary = "健康检查", description = "查看 smart-wallet 服务是否启动，以及当前画像、观察名单、钱包与市场数量。")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", "smart-wallet",
                "profiles", polymarketMonitorService.listProfiles().size(),
                "watchlist", polymarketMonitorService.listWatchlist().size(),
                "wallets", walletRegistryService.list().size(),
                "markets", opportunityService.list().size(),
                "status", "UP"
        );
    }

    @Operation(summary = "产品总览", description = "查看 Polymarket smart-wallet 模块的总览信息，包括 profiles、watchlist、tasks 与执行钱包状态。")
    @GetMapping("/overview")
    public PolymarketProductOverview overview() {
        return productOverviewService.overview();
    }

    @Operation(summary = "组合摘要", description = "按时间窗口汇总当前组合暴露、任务数量与资金使用情况。")
    @GetMapping("/portfolio/summary")
    public PortfolioSummaryView portfolioSummary(@RequestParam(required = false) Integer windowSeconds,
                                                 @RequestParam(defaultValue = "5000") Integer limit) {
        return portfolioAnalyticsService.summary(windowSeconds, limit);
    }

    @Operation(summary = "组合持仓视图", description = "按 marketId、outcome、side 聚合组合持仓，适合看当前暴露。")
    @GetMapping("/portfolio/positions")
    public List<PortfolioPositionView> portfolioPositions(@RequestParam(required = false) Integer windowSeconds,
                                                          @RequestParam(defaultValue = "2000") Integer limit,
                                                          @RequestParam(defaultValue = "0") Integer offset) {
        return portfolioAnalyticsService.listPositions(windowSeconds, limit, offset);
    }

    @Operation(summary = "钱包归因分析", description = "按 smart wallet 来源聚合任务与收益，查看跟哪个钱包最赚钱或最亏。")
    @GetMapping("/portfolio/attribution/wallets")
    public List<PortfolioAttributionItem> portfolioWalletAttribution(@RequestParam(required = false) Integer windowSeconds,
                                                                     @RequestParam(defaultValue = "5000") Integer taskLimit,
                                                                     @RequestParam(defaultValue = "20") Integer limit) {
        return portfolioAnalyticsService.walletAttribution(windowSeconds, taskLimit, limit);
    }

    @Operation(summary = "市场归因分析", description = "按市场维度聚合任务与收益，查看哪些市场类型表现最好或最差。")
    @GetMapping("/portfolio/attribution/markets")
    public List<PortfolioAttributionItem> portfolioMarketAttribution(@RequestParam(required = false) Integer windowSeconds,
                                                                     @RequestParam(defaultValue = "5000") Integer taskLimit,
                                                                     @RequestParam(defaultValue = "20") Integer limit) {
        return portfolioAnalyticsService.marketAttribution(windowSeconds, taskLimit, limit);
    }

    @Operation(summary = "钱包画像列表", description = "查看已入库的 smart wallet Polymarket 画像信息。")
    @GetMapping("/profiles")
    public List<SmartWalletProfileEntity> profiles() {
        return polymarketMonitorService.listProfiles();
    }

    @Operation(summary = "观察名单", description = "查看当前被筛选进入 watchlist 的聪明钱包。")
    @GetMapping("/watchlist")
    public List<SmartWalletProfileEntity> watchlist() {
        return polymarketMonitorService.listWatchlist();
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

    @Operation(summary = "刷新观察名单", description = "根据当前阈值重新筛选符合要求的聪明钱包并更新 watchlist。")
    @PostMapping("/polymarket/select")
    public SmartWalletSelectionSummary refreshSelection() {
        return selectionService.refreshQualifiedWallets();
    }

    @Operation(summary = "分析聪明钱包", description = "从 Polymarket 拉取排行榜、画像与交易记录，分析并入库 smart wallet。")
    @PostMapping("/polymarket/analyze")
    public PolymarketDiscoverySummary analyzeTopWallets() {
        return predictionMarketDataApplicationService.analyzeSourceWallets();
    }

    @Operation(summary = "同步观察名单事件", description = "同步 watchlist 钱包的最新 Polymarket 开单事件。")
    @PostMapping("/polymarket/sync-events")
    public Map<String, Object> syncWatchlistEvents() {
        return Map.of("imported", predictionMarketDataApplicationService.syncWatchlistEvents());
    }

    @Operation(summary = "跟单任务列表", description = "查看生成的跟单任务，可选带实时风控评估结果。")
    @GetMapping("/follow-tasks")
    public List<SmartWalletFollowTaskView> followTasks(@RequestParam(defaultValue = "100") Integer limit,
                                                       @RequestParam(defaultValue = "false") boolean includeRiskEvaluation) {
        return followTaskApplicationService.listTasks(limit, includeRiskEvaluation);
    }

    @Operation(summary = "可执行任务列表", description = "查看当前通过风控、可直接执行的跟单任务。")
    @GetMapping("/follow-tasks/executable")
    public List<SmartWalletFollowTaskView> executableFollowTasks(@RequestParam(defaultValue = "50") Integer limit) {
        return followTaskApplicationService.listExecutableTasks(limit);
    }

    @Operation(summary = "单强钱包候选", description = "基于单钱包 override 策略查看候选任务，适合 followable 预设。")
    @GetMapping("/follow-tasks/single-wallet-override")
    public List<SmartWalletFollowTaskView> singleWalletOverrideFollowTasks(@RequestParam(defaultValue = "20") Integer limit,
                                                                           @RequestParam(required = false) String preset,
                                                                           @RequestParam(required = false) Integer taskLimit,
                                                                           @RequestParam(required = false) Integer maxSignalAgeSeconds,
                                                                           @RequestParam(required = false) Integer minDistinctWallets,
                                                                           @RequestParam(required = false) BigDecimal minConsensusScore,
                                                                           @RequestParam(required = false) BigDecimal singleWalletOverrideMinWalletScore,
                                                                           @RequestParam(required = false) BigDecimal singleWalletOverrideMinWinRate,
                                                                           @RequestParam(required = false) Integer singleWalletOverrideMinTrades,
                                                                           @RequestParam(required = false) BigDecimal singleWalletOverrideMinTriggerSizeUsdc,
                                                                           @RequestParam(required = false) BigDecimal singleWalletOverrideMinRecommendedStakeUsdc) {
        SmartWalletRiskSimulationRequest request = new SmartWalletRiskSimulationRequest();
        request.setTaskLimit(taskLimit);
        request.setMaxSignalAgeSeconds(maxSignalAgeSeconds);
        request.setMinDistinctWallets(minDistinctWallets);
        request.setMinConsensusScore(minConsensusScore);
        request.setSingleWalletOverrideMinWalletScore(singleWalletOverrideMinWalletScore);
        request.setSingleWalletOverrideMinWinRate(singleWalletOverrideMinWinRate);
        request.setSingleWalletOverrideMinTrades(singleWalletOverrideMinTrades);
        request.setSingleWalletOverrideMinTriggerSizeUsdc(singleWalletOverrideMinTriggerSizeUsdc);
        request.setSingleWalletOverrideMinRecommendedStakeUsdc(singleWalletOverrideMinRecommendedStakeUsdc);
        applySingleWalletOverridePreset(request, preset);
        return followTaskService.listSingleWalletOverrideTasks(limit, followTaskService.buildSimulationRisk(request), taskLimit, preset);
    }

    @Operation(summary = "机会看板", description = "查看最接近可执行但仍被少量风控项拦住的任务。")
    @GetMapping("/follow-tasks/opportunities")
    public List<SmartWalletFollowTaskView> opportunityFollowTasks(@RequestParam(defaultValue = "20") Integer limit,
                                                                  @RequestParam(defaultValue = "3") Integer maxBlockers,
                                                                  @RequestParam(defaultValue = "false") boolean includeExecutable) {
        return followTaskService.listOpportunityTasks(limit, maxBlockers, includeExecutable);
    }

    @Operation(summary = "阻塞项统计", description = "按 blockerCategory 聚合最近任务，观察当前主要风控瓶颈。")
    @GetMapping("/follow-tasks/blocker-stats")
    public List<SmartWalletBlockerStatView> followTaskBlockerStats(@RequestParam(defaultValue = "200") Integer taskLimit) {
        return followTaskApplicationService.blockerStats(taskLimit);
    }

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

    @Operation(summary = "风控模拟", description = "使用临时参数模拟风控筛选结果，不修改线上配置。")
    @PostMapping("/follow-tasks/simulate-risk")
    public SmartWalletRiskSimulationResult simulateRisk(@RequestBody(required = false) SmartWalletRiskSimulationRequest request) {
        return followTaskApplicationService.simulateRisk(request);
    }

    @Operation(summary = "处理跟单任务", description = "手动触发一轮待执行 follow task 处理。")
    @PostMapping("/follow-tasks/process")
    public Map<String, Object> processFollowTasks() {
        return Map.of("processed", followTaskApplicationService.processPendingTasksNow());
    }

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
        return walletRegistryService.list();
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

    @Operation(summary = "订单列表", description = "查看 PAPER 或 LIVE 的订单记录。")
    @GetMapping("/orders")
    public List<SmartWalletOrderView> orders(@RequestParam(defaultValue = "100") Integer limit) {
        return accountingService.listOrders(limit);
    }

    @Operation(summary = "成交列表", description = "查看 PAPER 或 LIVE 的成交明细。")
    @GetMapping("/fills")
    public List<SmartWalletFillView> fills(@RequestParam(defaultValue = "100") Integer limit) {
        return accountingService.listFills(limit);
    }

    @Operation(summary = "容量摘要", description = "查看当前组合剩余仓位槽位、资金预算和基础风控限制。")
    @GetMapping("/capacity/summary")
    public SmartWalletCapacitySummaryView capacitySummary() {
        return strategyAnalyticsApplicationService.capacitySummary();
    }

    @Operation(summary = "当前持仓", description = "查看当前仍活跃的持仓记录，不包含 CLOSED 或 CLAIMED 仓位。")
    @GetMapping("/positions/live")
    public List<SmartWalletPositionView> livePositions() {
        return accountingService.listPositions();
    }

    @Operation(summary = "待结算处理持仓", description = "查看已到结算窗口或待领取的仓位，时间已带北京时间字段。")
    @GetMapping("/positions/next-settlements")
    public List<SmartWalletPositionView> nextSettlements(@RequestParam(defaultValue = "10") Integer limit) {
        return accountingService.listNextSettlements(limit);
    }

    @Operation(summary = "历史持仓", description = "查看历史持仓，可选只返回 closed/resolved 记录。")
    @GetMapping("/positions/history")
    public List<SmartWalletPositionView> positionHistory(@RequestParam(defaultValue = "false") boolean closedOnly) {
        return accountingService.listPositions(closedOnly);
    }

    @Operation(summary = "资金流水", description = "查看开单、结算、已实现盈亏等资金明细流水。")
    @GetMapping("/cash-ledger")
    public List<SmartWalletCashLedgerView> cashLedger(@RequestParam(defaultValue = "100") Integer limit) {
        return accountingService.listLedger(limit);
    }

    @Operation(summary = "盈亏汇总", description = "查看总订单、胜率、已实现与未实现盈亏等总览。")
    @GetMapping("/pnl/summary")
    public SmartWalletPnlSummaryView pnlSummary() {
        return strategyAnalyticsApplicationService.pnlSummary();
    }

    @Operation(summary = "绩效报表", description = "查看胜率、盈亏比、回撤、冲突市场等综合绩效指标。")
    @GetMapping("/reports/performance")
    public SmartWalletPerformanceView performanceReport() {
        return strategyAnalyticsApplicationService.performance();
    }

    @Operation(summary = "盈亏时间线", description = "按天查看收益变化曲线。")
    @GetMapping("/pnl/timeline")
    public List<SmartWalletPnlTimelinePoint> pnlTimeline(@RequestParam(defaultValue = "30") Integer days) {
        return strategyAnalyticsApplicationService.pnlTimeline(days);
    }

    @Operation(summary = "日报列表", description = "查看按日汇总的收益、胜率与绩效数据。")
    @GetMapping("/reports/daily")
    public List<SmartWalletDailyReportView> dailyReports(@RequestParam(defaultValue = "30") Integer limit) {
        return accountingService.listDailyReports(limit);
    }

    @Operation(summary = "重建日报", description = "按最近天数重建 daily report 聚合数据。")
    @PostMapping("/reports/daily/rebuild")
    public Map<String, Object> rebuildDailyReports(@RequestParam(defaultValue = "7") Integer days) {
        return Map.of("rebuilt", accountingService.rebuildRecentReports(days));
    }

    @Operation(summary = "回填账本", description = "从 follow_task 回填 order、fill、position 等账本数据。")
    @PostMapping("/accounting/backfill")
    public Map<String, Object> backfillAccounting(@RequestParam(defaultValue = "2000") Integer limit) {
        return Map.of("imported", accountingService.backfillFromFollowTasks(limit));
    }

    @Operation(summary = "回填结算时间", description = "批量回填缺失的 marketEndAt，覆盖 position、order 与 followTask。")
    @PostMapping("/accounting/backfill-market-end-at")
    public Map<String, Object> backfillMarketEndAt(@RequestParam(defaultValue = "500") Integer limit,
                                                   @RequestParam(defaultValue = "false") boolean refreshCache) {
        return accountingService.backfillMissingMarketEndAt(limit, refreshCache);
    }

    @Operation(summary = "修复提前结算", description = "扫描并修复被错误提前推进为 RESOLVED 的 PAPER 持仓。")
    @PostMapping("/accounting/repair-premature-settlements")
    public PrematureSettlementRepairSummary repairPrematureSettlements(@RequestParam(defaultValue = "500") Integer limit,
                                                                       @RequestParam(defaultValue = "true") boolean refreshCache) {
        return accountingService.repairPrematureResolvedPaperPositions(limit, refreshCache);
    }

    @Operation(summary = "按 ID 修复提前结算", description = "按 positionId 或 repair item 列表精准修复提前结算的持仓。")
    @PostMapping("/accounting/repair-premature-settlements/by-ids")
    public PrematureSettlementRepairSummary repairPrematureSettlementsByIds(@RequestBody PrematureSettlementRepairRequest request) {
        if (request == null) {
            return accountingService.repairPrematureResolvedPaperPositionsByIds(List.of());
        }
        if (request.getPositionIds() != null && !request.getPositionIds().isEmpty()) {
            return accountingService.repairPrematureResolvedPaperPositionsByIds(request.getPositionIds());
        }
        return accountingService.repairPrematureResolvedPaperPositionsByItems(request.getItems());
    }

    @Operation(summary = "手动同步结算", description = "手动触发一轮 settlement sync，检查市场是否已经真正结算。")
    @PostMapping("/settlement/sync")
    public SettlementSyncSummary syncSettlements(@RequestParam(defaultValue = "500") Integer positionLimit,
                                                 @RequestParam(defaultValue = "100") Integer marketLimit,
                                                 @RequestParam(defaultValue = "false") boolean refreshCache) {
        return settlementApplicationService.runNow(positionLimit, marketLimit, refreshCache);
    }

    @Operation(summary = "复核历史模拟结算", description = "重扫 paper-book 历史未结算仓位，并自动收口旧的未领取模拟仓位。")
    @PostMapping("/settlement/review-paper-history")
    public HistoricalPaperSettlementReviewView reviewHistoricalPaperSettlements(@RequestParam(defaultValue = "1000") Integer limit,
                                                                                @RequestParam(defaultValue = "true") boolean refreshCache) {
        return accountingService.reviewHistoricalPaperSettlements(limit, refreshCache);
    }

    @Operation(summary = "结算状态", description = "查看 settlement sync 的最近状态和运行情况。")
    @GetMapping("/settlement/status")
    public SettlementSyncStatusView settlementStatus() {
        return settlementApplicationService.status();
    }

    @Operation(summary = "结算运行历史", description = "查看 settlement sync 的历史运行记录。")
    @GetMapping("/settlement/runs")
    public List<SettlementRunView> settlementRuns(@RequestParam(defaultValue = "20") Integer limit) {
        return settlementApplicationService.listRuns(limit);
    }

    @Operation(summary = "结算重试队列", description = "查看结算失败市场的重试队列。")
    @GetMapping("/settlement/retries")
    public List<SettlementRetryView> settlementRetries(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "20") Integer limit) {
        return settlementApplicationService.listRetries(status, limit);
    }

    @Operation(summary = "结算重试报表", description = "查看结算重试成功、失败、待重试的统计结果。")
    @GetMapping("/settlement/retries/report")
    public SettlementRetryReportView settlementRetryReport() {
        return settlementApplicationService.retryReport();
    }

    @Operation(summary = "处理结算重试", description = "手动处理一轮结算失败市场的重试任务。")
    @PostMapping("/settlement/retries/process")
    public SettlementSyncSummary processSettlementRetries(@RequestParam(required = false) Integer limit,
                                                          @RequestParam(defaultValue = "false") boolean refreshCache) {
        return settlementSyncService.processRetriesNow(limit, refreshCache);
    }

    @Operation(summary = "Claim 状态", description = "查看 claim 自动处理的启停、最近一轮执行与运行状态。")
    @GetMapping("/claim/status")
    public SmartWalletClaimStatusView claimStatus() {
        return claimService.status();
    }

    @Operation(summary = "Claim 运行历史", description = "查看 claim 自动处理的历史运行记录。")
    @GetMapping("/claim/runs")
    public List<SmartWalletClaimRunView> claimRuns(@RequestParam(defaultValue = "20") Integer limit) {
        return claimService.listRuns(limit);
    }

    @Operation(summary = "Claim 重试队列", description = "查看 live claim 失败、待确认或待重试的队列。")
    @GetMapping("/claim/retries")
    public List<SmartWalletClaimRetryView> claimRetries(@RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "20") Integer limit) {
        return claimService.listRetries(status, limit);
    }

    @Operation(summary = "Claim 重试报表", description = "查看 claim retry 的待处理、待确认、已解决与放弃统计。")
    @GetMapping("/claim/retries/report")
    public SmartWalletClaimRetryReportView claimRetryReport() {
        return claimService.retryReport();
    }

    @Operation(summary = "处理 Claim", description = "扫描并处理一批 RESOLVED_UNCLAIMED 仓位。paper 会自动模拟领取；live 按 claim gateway 模式推进。")
    @PostMapping("/claim/process")
    public SmartWalletClaimSummary processClaims(@RequestParam(required = false) Integer limit) {
        return claimService.processNow(limit);
    }

    @Operation(summary = "处理 Claim 重试", description = "处理一轮到期的 live claim retry。")
    @PostMapping("/claim/retries/process")
    public SmartWalletClaimSummary processClaimRetries(@RequestParam(required = false) Integer limit) {
        return claimService.processRetriesNow(limit);
    }

    @Operation(summary = "确认领取结算收益", description = "将 RESOLVED_UNCLAIMED 仓位推进到 CLAIMED。LIVE 仓位需要先完成链上领取，再传 confirmExternalClaim=true。")
    @PostMapping("/positions/{positionId}/claim")
    public SmartWalletClaimResultView claimPosition(@PathVariable Long positionId,
                                                    @RequestParam(defaultValue = "false") boolean confirmExternalClaim) {
        return claimService.claimPosition(positionId, confirmExternalClaim);
    }

    @Operation(summary = "钱包归因报表", description = "按 smart wallet 来源查看收益归因报表。")
    @GetMapping("/reports/wallets")
    public List<SmartWalletReportAttributionItem> walletReports(@RequestParam(defaultValue = "20") Integer limit) {
        return accountingService.walletAttribution(limit);
    }

    @Operation(summary = "市场归因报表", description = "按市场维度查看收益归因报表。")
    @GetMapping("/reports/markets")
    public List<SmartWalletReportAttributionItem> marketReports(@RequestParam(defaultValue = "20") Integer limit) {
        return accountingService.marketAttribution(limit);
    }

    @Operation(summary = "派生 CLOB API 凭证", description = "基于执行钱包派生 Polymarket CLOB API key、secret 与 passphrase。")
    @PostMapping("/execution-wallet/derive-api-creds")
    public Map<String, Object> deriveExecutionWalletApiCreds() {
        return executionApplicationService.deriveApiCreds();
    }

    @Operation(summary = "CLOB 通用预演", description = "按 market/outcome/stake 预演订单参数，不会真实下单。")
    @PostMapping("/clob/dry-run")
    public Map<String, Object> clobDryRun(@RequestBody PolymarketOrderDryRunRequest request) {
        return clobExecutionService.dryRunOrder(request);
    }

    @Operation(summary = "按任务预演", description = "按 followTaskId 预演真实下单参数，不会真实提交订单。")
    @PostMapping("/follow-tasks/{followTaskId}/dry-run")
    public Map<String, Object> followTaskDryRun(@PathVariable Long followTaskId) {
        return clobExecutionService.dryRunFollowTask(followTaskId);
    }

    @Operation(summary = "按任务真实下单", description = "按 followTaskId 触发真实 LIVE 下单，仅在运行环境允许交易时使用。")
    @PostMapping("/follow-tasks/{followTaskId}/execute-live")
    public SmartWalletExecutionResult executeFollowTaskLive(@PathVariable Long followTaskId) {
        return executionApplicationService.executeFollowTask(followTaskId);
    }

    @Operation(summary = "查看市场元数据", description = "按 conditionId 查看 Polymarket 市场元数据、tokenId 与价格相关信息。")
    @GetMapping("/clob/markets/{conditionId}")
    public Object clobMarketMetadata(@PathVariable String conditionId) {
        return clobExecutionService.marketMetadata(conditionId);
    }

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

    private void applySingleWalletOverridePreset(SmartWalletRiskSimulationRequest request, String preset) {
        if (preset == null || preset.isBlank()) {
            return;
        }
        SmartWalletProperties.SignalRisk presetRisk = properties.getSignalRiskPresets().get(preset);
        if (presetRisk == null) {
            return;
        }
        if (request.getMaxSignalAgeSeconds() == null) {
            request.setMaxSignalAgeSeconds(presetRisk.getMaxSignalAgeSeconds());
        }
        if (request.getMinDistinctWallets() == null) {
            request.setMinDistinctWallets(presetRisk.getMinDistinctWallets());
        }
        if (request.getMinConsensusScore() == null) {
            request.setMinConsensusScore(presetRisk.getMinConsensusScore());
        }
        if (request.getSingleWalletOverrideMinWalletScore() == null) {
            request.setSingleWalletOverrideMinWalletScore(presetRisk.getSingleWalletOverrideMinWalletScore());
        }
        if (request.getSingleWalletOverrideMinWinRate() == null) {
            request.setSingleWalletOverrideMinWinRate(presetRisk.getSingleWalletOverrideMinWinRate());
        }
        if (request.getSingleWalletOverrideMinTrades() == null) {
            request.setSingleWalletOverrideMinTrades(presetRisk.getSingleWalletOverrideMinTrades());
        }
        if (request.getSingleWalletOverrideMinTriggerSizeUsdc() == null) {
            request.setSingleWalletOverrideMinTriggerSizeUsdc(presetRisk.getSingleWalletOverrideMinTriggerSizeUsdc());
        }
        if (request.getSingleWalletOverrideMinRecommendedStakeUsdc() == null) {
            request.setSingleWalletOverrideMinRecommendedStakeUsdc(presetRisk.getSingleWalletOverrideMinRecommendedStakeUsdc());
        }
        if (request.getTaskLimit() == null) {
            request.setTaskLimit(120);
        }
    }

}
