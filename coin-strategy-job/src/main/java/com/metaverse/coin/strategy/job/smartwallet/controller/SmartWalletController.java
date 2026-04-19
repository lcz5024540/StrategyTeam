package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.analytics.application.StrategyAnalyticsApplicationService;
import com.metaverse.coin.strategy.job.execution.application.ExecutionApplicationService;
import com.metaverse.coin.strategy.job.follow.application.FollowTaskApplicationService;
import com.metaverse.coin.strategy.job.marketdata.application.PredictionMarketDataApplicationService;
import com.metaverse.coin.strategy.job.risk.application.BaselineModelConfigApplicationService;
import com.metaverse.coin.strategy.job.risk.application.RiskPolicyApplicationService;
import com.metaverse.coin.strategy.job.settlement.application.SettlementApplicationService;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionWalletStatus;
import com.metaverse.coin.strategy.job.smartwallet.model.HistoricalPaperSettlementReviewView;
import com.metaverse.coin.strategy.job.smartwallet.model.LiveValidationRunSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.LiveValidationStatusView;
import com.metaverse.coin.strategy.job.smartwallet.model.LocalSettlementConfirmRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.BaselineModelConfigView;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketMarketDataDiagnosticsView;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketMockWsEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketOrderDryRunRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketProductOverview;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskPresetCatalogView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderView;
import com.metaverse.coin.strategy.job.strategy.model.CursorPageView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFillView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowAddressDailyReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCashLedgerView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalHealthSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletBlockerStatView;
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
import com.metaverse.coin.strategy.job.smartwallet.model.UpdateBaselineModelConfigRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.UpsertWalletRequest;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketSmartWalletMonitorService;
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
import com.metaverse.coin.strategy.job.smartwallet.service.MarketSnapshotCollectorService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletDataResetService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletRecoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletRegistryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSelectionService;
import com.metaverse.coin.strategy.job.smartwallet.service.MarketConcentrationService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSettlementSyncService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/smart-wallet")
@RequiredArgsConstructor
@Slf4j
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
    private final PolymarketMarketDataStreamService marketDataStreamService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final PolymarketProductOverviewService productOverviewService;
    private final PolymarketWalletDiscoveryService polymarketWalletDiscoveryService;
    private final SmartWalletSelectionService selectionService;
    private final SmartWalletFollowTaskService followTaskService;
    private final SmartWalletClaimService claimService;
    private final SmartWalletLiveValidationService liveValidationService;
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
    private final StrategyAnalyticsApplicationService strategyAnalyticsApplicationService;
    private final MarketConcentrationService marketConcentrationService;

    @Operation(summary = "健康检查", description = "查看 smart-wallet 服务是否启动，以及当前画像、观察名单、钱包与市场数量。")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", "smart-wallet",
                "profiles", polymarketMonitorService.listProfiles().size(),
                "watchlist", polymarketMonitorService.listWatchlist().size(),
                "wallets", followService.listWalletsForScan().size(),
                "markets", opportunityService.list().size(),
                "status", "UP"
        );
    }

    @Operation(summary = "产品总览", description = "查看 Polymarket smart-wallet 模块的总览信息，包括 profiles、watchlist、tasks 与执行钱包状态。")
    @GetMapping("/overview")
    public PolymarketProductOverview overview() {
        return productOverviewService.overview();
    }

    @Operation(summary = "重置 smart-wallet 数据", description = "清空 smart-wallet 运行数据与可选风险特征数据，用于从头重新计算。需要 confirm=true 才会执行。")
    @PostMapping("/admin/reset-data")
    public Map<String, Object> resetData(@RequestParam(defaultValue = "false") boolean confirm,
                                         @RequestParam(defaultValue = "true") boolean includeProfiles,
                                         @RequestParam(defaultValue = "true") boolean includeRiskArtifacts,
                                         @RequestParam(defaultValue = "true") boolean includeRuntimeSeeds,
                                         @RequestParam(defaultValue = "true") boolean includeMetadataCache) {
        if (!confirm) {
            return Map.of(
                    "executed", false,
                    "message", "Pass confirm=true to execute the smart-wallet data reset.",
                    "includeProfiles", includeProfiles,
                    "includeRiskArtifacts", includeRiskArtifacts,
                    "includeRuntimeSeeds", includeRuntimeSeeds,
                    "includeMetadataCache", includeMetadataCache
            );
        }
        Map<String, Object> result = dataResetService.reset(
                includeProfiles,
                includeRiskArtifacts,
                includeRuntimeSeeds,
                includeMetadataCache
        );
        return Map.of(
                "executed", true,
                "result", result
        );
    }

    @Operation(summary = "恢复 smart-wallet 状态", description = "在网络/RPC/缓存异常后，触发一次全链路重梳理，尽量避免通过重启服务恢复。")
    @PostMapping("/admin/recover-state")
    public Map<String, Object> recoverState(@RequestParam(required = false) String reason) {
        return recoveryService.recoverState(reason);
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

    @Operation(summary = "手动采集市场快照", description = "基于当前缓存或 REST 降级，对指定市场/outcome 执行一次 snapshot 采集。")
    @PostMapping("/polymarket/market-data/collect-snapshot")
    public SmartWalletMarketSnapshotEntity collectDiagnosticMarketSnapshot(@RequestParam String marketId,
                                                                           @RequestParam(defaultValue = "YES") String outcome) {
        return marketSnapshotCollectorService.collectSnapshot(marketId, outcome);
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

    @Operation(summary = "准入单个钱包到观察名单", description = "传入单个钱包地址，按钱包画像风控逻辑进行分析；满足条件则落库并加入 watchlist，否则跳过落库并返回失败。")
    @PostMapping("/polymarket/watchlist/admit")
    public Map<String, Object> admitWalletToWatchlist(@RequestParam String walletAddress) {
        boolean admitted = polymarketWalletDiscoveryService.analyzeWalletAndAdmitWatchlist(walletAddress);
        return Map.of(
                "walletAddress", walletAddress,
                "admitted", admitted,
                "message", admitted
                        ? "Wallet admitted to watchlist and persisted successfully."
                        : "Wallet did not pass admission checks, skipped persistence."
        );
    }

    @Operation(summary = "同步观察名单事件", description = "同步 watchlist 钱包的最新 Polymarket 开单事件。")
    @PostMapping("/polymarket/sync-events")
    public Map<String, Object> syncWatchlistEvents() {
        return Map.of("imported", predictionMarketDataApplicationService.syncWatchlistEvents());
    }

    @Operation(summary = "跟单任务列表", description = "查看生成的跟单任务，可选带实时风控评估结果。")
    @GetMapping("/follow-tasks")
    public List<SmartWalletFollowTaskView> followTasks(@RequestParam(defaultValue = "100") Integer limit,
                                                       @RequestParam(defaultValue = "true") boolean includeRiskEvaluation) {
        return followTaskApplicationService.listTasks(limit, includeRiskEvaluation);
    }

    @Operation(summary = "可执行任务列表", description = "查看当前通过风控、可直接执行的跟单任务。")
    @GetMapping("/follow-tasks/executable")
    public List<SmartWalletFollowTaskView> executableFollowTasks(@RequestParam(defaultValue = "50") Integer limit) {
        try {
            return followTaskApplicationService.listExecutableTasks(limit);
        } catch (Exception e) {
            log.error("Failed to load executable follow tasks limit={}", limit, e);
            throw e;
        }
    }

    @Operation(summary = "重评延迟候选", description = "手动重评 enrichment_status=PENDING/LOW_CONFIDENCE_LINK 的候选，并尝试恢复此前被 intelligence 暂时拦截的跟单任务。")
    @PostMapping("/follow-tasks/intelligence/reevaluate-deferred")
    public Map<String, Object> reevaluateDeferredCandidates(@RequestParam(defaultValue = "50") Integer limit) {
        return Map.of(
                "limit", limit,
                "updated", followTaskService.reevaluateDeferredCandidates(limit)
        );
    }

    @Operation(summary = "回放最近智能候选", description = "回放最近的 trade event，对历史上漏掉 candidate/task 的事件补建 intelligence 记录和跟单任务。")
    @PostMapping("/follow-tasks/intelligence/replay-recent")
    public Map<String, Object> replayRecentIntelligenceCandidates(@RequestParam(defaultValue = "100") Integer limit,
                                                                  @RequestParam(defaultValue = "false") boolean includeProcessed) {
        SmartWalletFollowTaskService.IntelligenceReplaySummary summary =
                followTaskService.replayRecentIntelligenceCandidates(limit, includeProcessed);
        return Map.of(
                "limit", limit,
                "includeProcessed", includeProcessed,
                "scanned", summary.getScanned(),
                "changed", summary.getChanged(),
                "skipped", summary.getSkipped()
        );
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
        try {
            return followTaskService.listOpportunityTasks(limit, maxBlockers, includeExecutable);
        } catch (Exception e) {
            log.error("Failed to load opportunity follow tasks limit={} maxBlockers={} includeExecutable={}",
                    limit, maxBlockers, includeExecutable, e);
            throw e;
        }
    }

    @Operation(summary = "阻塞项统计", description = "按 blockerCategory 聚合最近任务，观察当前主要风控瓶颈。")
    @GetMapping("/follow-tasks/blocker-stats")
    public List<SmartWalletBlockerStatView> followTaskBlockerStats(@RequestParam(defaultValue = "200") Integer taskLimit) {
        try {
            return followTaskApplicationService.blockerStats(taskLimit);
        } catch (Exception e) {
            log.error("Failed to load follow-task blocker stats taskLimit={}", taskLimit, e);
            throw e;
        }
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

    @Operation(summary = "Baseline 模型配置", description = "查看当前 P1 baseline 风险/滑点模型和校准参数的运行时配置。")
    @GetMapping("/config/baseline-model")
    public BaselineModelConfigView baselineModelConfig() {
        return baselineModelConfigApplicationService.current();
    }

    @Operation(summary = "更新 Baseline 模型配置", description = "热更新当前进程中的 baseline 模型和校准参数，不修改 application.yml。")
    @PostMapping("/config/baseline-model")
    public BaselineModelConfigView updateBaselineModelConfig(@RequestBody(required = false) UpdateBaselineModelConfigRequest request) {
        return baselineModelConfigApplicationService.update(request);
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
            @RequestParam(required = false) String mode) {
        return accountingService.listOrdersCursor(limit, lastId, mode);
    }

    @Operation(summary = "成交列表", description = "查看 PAPER 或 LIVE 的成交明细。")
    @GetMapping("/fills")
    public List<SmartWalletFillView> fills(@RequestParam(defaultValue = "100") Integer limit,
                                           @RequestParam(required = false) String mode) {
        return accountingService.listFills(limit, mode);
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

    @Operation(summary = "待结算处理持仓", description = "查看已到结算窗口或待领取的仓位，时间已带北京时间字段。")
    @GetMapping("/positions/next-settlements")
    public List<SmartWalletPositionView> nextSettlements(@RequestParam(defaultValue = "10") Integer limit,
                                                         @RequestParam(required = false) String mode) {
        return accountingService.listNextSettlements(limit, mode);
    }

    @Operation(summary = "历史持仓", description = "查看历史持仓，可选只返回 closed/resolved 记录。")
    @GetMapping("/positions/history")
    public List<SmartWalletPositionView> positionHistory(@RequestParam(defaultValue = "false") boolean closedOnly,
                                                         @RequestParam(required = false) String mode) {
        return accountingService.listPositions(closedOnly, mode);
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
    public List<SmartWalletDailyReportView> dailyReports(@RequestParam(defaultValue = "30") Integer limit,
                                                         @RequestParam(required = false) String mode) {
        return accountingService.listDailyReports(limit, mode);
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

    @Operation(summary = "回填 token 与仓位关联", description = "批量回填缺失的 tokenId，以及能从现有成交/持仓确定的 order.positionId / fill.positionId。")
    @PostMapping("/accounting/backfill-token-links")
    public Map<String, Object> backfillTokenLinks(@RequestParam(defaultValue = "500") Integer limit,
                                                  @RequestParam(defaultValue = "false") boolean refreshCache) {
        return accountingService.backfillMissingTokenAndPositionLinks(limit, refreshCache);
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

    @Operation(summary = "人工确认 LOCAL 结算信号", description = "针对 resolutionSource=LOCAL 的挂起市场，重新到 Polymarket 侧拉取数据；若问题精确匹配且已闭市 outcomePrices 呈现终局 1/0 形态，则按该信号推进结算。marketIds 为空时默认处理当前 LOCAL retry 队列。")
    @PostMapping("/settlement/manual-confirm-local")
    public SettlementSyncSummary confirmLocalSettlements(@RequestBody(required = false) LocalSettlementConfirmRequest request,
                                                         @RequestParam(defaultValue = "true") boolean refreshCache) {
        List<String> marketIds = request == null ? List.of() : request.getMarketIds();
        return settlementApplicationService.confirmLocalMarkets(marketIds, refreshCache);
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

    @Operation(summary = "提前卖出持仓", description = "对 PAPER 持仓生成一笔显式 SELL 平仓成交，按卖出价实现盈亏。若不调用该接口，则持仓继续走 expiry -> oracle -> resolve -> claim 流程。")
    @PostMapping("/positions/{positionId}/exit")
    public SmartWalletPositionExitResultView exitPosition(@PathVariable Long positionId,
                                                          @RequestBody(required = false) SmartWalletPositionExitRequest request) {
        return accountingService.exitPosition(positionId, request);
    }

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
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(required = false) String mode) {
        return accountingService.followAddressDailyReport(reportDate, limit, mode);
    }

    @Operation(summary = "市场归因报表", description = "按市场维度汇总成交量、账本收益与当前未落袋暴露。")
    @GetMapping("/reports/markets")
    public List<SmartWalletReportAttributionItem> marketReports(@RequestParam(defaultValue = "20") Integer limit,
                                                                @RequestParam(required = false) String mode) {
        return accountingService.marketAttribution(limit, mode);
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

    @Operation(summary = "查看实时价格参考", description = "按 conditionId/outcome 查看当前 token 的 best bid/ask、midpoint 与回退价格参考。")
    @GetMapping("/clob/markets/{conditionId}/price-reference")
    public Map<String, Object> clobMarketPriceReference(@PathVariable String conditionId,
                                                        @RequestParam(defaultValue = "YES") String outcome,
                                                        @RequestParam(required = false) String tokenId,
                                                        @RequestParam(required = false) BigDecimal fallbackPrice,
                                                        @RequestParam(required = false) String question) {
        return clobExecutionService.marketPriceReference(conditionId, outcome, tokenId, fallbackPrice, question);
    }

    @Operation(summary = "采集市场快照", description = "按 conditionId/outcome 主动抓取一次订单簿与最近成交，生成市场微观结构快照。")
    @PostMapping("/clob/markets/{conditionId}/snapshot")
    public SmartWalletMarketSnapshotEntity collectMarketSnapshot(@PathVariable String conditionId,
                                                                 @RequestParam(defaultValue = "YES") String outcome) {
        return predictionMarketDataApplicationService.collectMarketSnapshot(conditionId, outcome);
    }

    @Operation(summary = "批量采集市场快照", description = "按最近活跃 trade event 批量抓取市场快照，用于后续 effect label 回填。")
    @PostMapping("/clob/markets/snapshots/backfill")
    public List<SmartWalletMarketSnapshotEntity> collectRecentMarketSnapshots(@RequestParam(defaultValue = "20") Integer limit) {
        return predictionMarketDataApplicationService.collectRecentMarketSnapshots(limit);
    }

    @Operation(summary = "回填交易效果标签", description = "按 sourceEventId 从事件前后快照回填交易效果标签，生成 future mid 和基础纸面PnL。")
    @PostMapping("/trade-events/{sourceEventId}/effect-label")
    public SmartWalletTradeEffectLabelEntity buildTradeEffectLabel(@PathVariable Long sourceEventId) {
        return predictionMarketDataApplicationService.buildTradeEffectLabel(sourceEventId);
    }

    @Operation(summary = "批量回填交易效果标签", description = "按最近 trade event 批量回填 effect label，可选择只处理缺失标签的事件。")
    @PostMapping("/trade-events/effect-labels/backfill")
    public List<SmartWalletTradeEffectLabelEntity> buildRecentTradeEffectLabels(@RequestParam(defaultValue = "50") Integer limit,
                                                                                @RequestParam(defaultValue = "true") boolean missingOnly) {
        return predictionMarketDataApplicationService.buildRecentTradeEffectLabels(limit, missingOnly);
    }

    @Operation(summary = "钱包日特征列表", description = "查看最近生成的 wallet_feature_daily 结果，用于 leader admission 和因果分析前置特征。")
    @GetMapping("/wallet-features/daily")
    public List<SmartWalletWalletFeatureDailyEntity> walletFeatureDaily(@RequestParam(defaultValue = "100") Integer limit) {
        return predictionMarketDataApplicationService.listWalletFeatureDaily(limit);
    }

    @Operation(summary = "回填单个钱包日特征", description = "按钱包地址和日期重建单个 wallet_feature_daily 特征快照。")
    @PostMapping("/wallet-features/daily")
    public SmartWalletWalletFeatureDailyEntity buildWalletFeatureDaily(@RequestParam String walletAddress,
                                                                       @RequestParam(required = false) String featureDate) {
        return predictionMarketDataApplicationService.buildWalletFeatureDaily(
                walletAddress,
                featureDate == null || featureDate.isBlank() ? null : LocalDate.parse(featureDate)
        );
    }

    @Operation(summary = "批量回填钱包日特征", description = "按最近高分钱包批量重建 wallet_feature_daily，可只补缺失数据。")
    @PostMapping("/wallet-features/daily/backfill")
    public List<SmartWalletWalletFeatureDailyEntity> buildRecentWalletFeatureDaily(@RequestParam(defaultValue = "30") Integer walletLimit,
                                                                                   @RequestParam(defaultValue = "1") Integer days,
                                                                                   @RequestParam(defaultValue = "true") boolean missingOnly) {
        return predictionMarketDataApplicationService.buildRecentWalletFeatureDaily(walletLimit, days, missingOnly);
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
    public MarketConcentrationService.ConcentrationSnapshot marketConcentration(
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

}
