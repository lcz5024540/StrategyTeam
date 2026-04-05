package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionWalletStatus;
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
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskPresetCatalogView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskPresetView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFillView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCashLedgerView;
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
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletLiveValidationService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletRegistryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSelectionService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSettlementSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/smart-wallet")
@RequiredArgsConstructor
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
    private final SmartWalletLiveValidationService liveValidationService;
    private final SmartWalletSettlementSyncService settlementSyncService;

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

    @GetMapping("/overview")
    public PolymarketProductOverview overview() {
        return productOverviewService.overview();
    }

    @GetMapping("/portfolio/summary")
    public PortfolioSummaryView portfolioSummary(@RequestParam(required = false) Integer windowSeconds,
                                                 @RequestParam(defaultValue = "5000") Integer limit) {
        return portfolioAnalyticsService.summary(windowSeconds, limit);
    }

    @GetMapping("/portfolio/positions")
    public List<PortfolioPositionView> portfolioPositions(@RequestParam(required = false) Integer windowSeconds,
                                                          @RequestParam(defaultValue = "2000") Integer limit,
                                                          @RequestParam(defaultValue = "0") Integer offset) {
        return portfolioAnalyticsService.listPositions(windowSeconds, limit, offset);
    }

    @GetMapping("/portfolio/attribution/wallets")
    public List<PortfolioAttributionItem> portfolioWalletAttribution(@RequestParam(required = false) Integer windowSeconds,
                                                                     @RequestParam(defaultValue = "5000") Integer taskLimit,
                                                                     @RequestParam(defaultValue = "20") Integer limit) {
        return portfolioAnalyticsService.walletAttribution(windowSeconds, taskLimit, limit);
    }

    @GetMapping("/portfolio/attribution/markets")
    public List<PortfolioAttributionItem> portfolioMarketAttribution(@RequestParam(required = false) Integer windowSeconds,
                                                                     @RequestParam(defaultValue = "5000") Integer taskLimit,
                                                                     @RequestParam(defaultValue = "20") Integer limit) {
        return portfolioAnalyticsService.marketAttribution(windowSeconds, taskLimit, limit);
    }

    @GetMapping("/profiles")
    public List<SmartWalletProfileEntity> profiles() {
        return polymarketMonitorService.listProfiles();
    }

    @GetMapping("/watchlist")
    public List<SmartWalletProfileEntity> watchlist() {
        return polymarketMonitorService.listWatchlist();
    }

    @GetMapping("/consensus-signals")
    public List<ConsensusSignalView> consensusSignals(@RequestParam(required = false) Integer windowSeconds,
                                                      @RequestParam(required = false) String marketId,
                                                      @RequestParam(required = false) String outcome,
                                                      @RequestParam(required = false) String side,
                                                      @RequestParam(required = false) Integer limit) {
        return consensusSignalService.listConsensusSignals(windowSeconds, marketId, outcome, side, limit);
    }

    @PostMapping("/polymarket/metrics")
    public SmartWalletProfileEntity ingestWalletMetrics(@RequestBody PolymarketWalletMetricsRequest request) {
        return polymarketMonitorService.ingestWalletMetrics(request);
    }

    @PostMapping("/polymarket/events")
    public SmartWalletTradeEventEntity ingestTradeEvent(@RequestBody PolymarketTradeEventRequest request) {
        return polymarketMonitorService.ingestTradeEvent(request);
    }

    @PostMapping("/polymarket/select")
    public SmartWalletSelectionSummary refreshSelection() {
        return selectionService.refreshQualifiedWallets();
    }

    @PostMapping("/polymarket/analyze")
    public PolymarketDiscoverySummary analyzeTopWallets() {
        return polymarketWalletDiscoveryService.analyzeTopWallets();
    }

    @PostMapping("/polymarket/sync-events")
    public Map<String, Object> syncWatchlistEvents() {
        return Map.of("imported", polymarketWalletDiscoveryService.syncWatchlistTrades());
    }

    @GetMapping("/follow-tasks")
    public List<SmartWalletFollowTaskView> followTasks(@RequestParam(defaultValue = "100") Integer limit,
                                                       @RequestParam(defaultValue = "false") boolean includeRiskEvaluation) {
        return followTaskService.listFollowTasks(limit, includeRiskEvaluation);
    }

    @GetMapping("/follow-tasks/executable")
    public List<SmartWalletFollowTaskView> executableFollowTasks(@RequestParam(defaultValue = "50") Integer limit) {
        return followTaskService.listExecutableTasks(limit);
    }

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

    @GetMapping("/follow-tasks/opportunities")
    public List<SmartWalletFollowTaskView> opportunityFollowTasks(@RequestParam(defaultValue = "20") Integer limit,
                                                                  @RequestParam(defaultValue = "3") Integer maxBlockers,
                                                                  @RequestParam(defaultValue = "false") boolean includeExecutable) {
        return followTaskService.listOpportunityTasks(limit, maxBlockers, includeExecutable);
    }

    @GetMapping("/config/signal-risk-presets")
    public SignalRiskPresetCatalogView signalRiskPresets() {
        List<SignalRiskPresetView> presets = new ArrayList<>();
        properties.getSignalRiskPresets().forEach((name, risk) ->
                presets.add(toSignalRiskPresetView(name, risk, false)));
        return SignalRiskPresetCatalogView.builder()
                .activeDefault(toSignalRiskPresetView("default", properties.getSignalRisk(), true))
                .presets(presets)
                .build();
    }

    @PostMapping("/follow-tasks/simulate-risk")
    public SmartWalletRiskSimulationResult simulateRisk(@RequestBody(required = false) SmartWalletRiskSimulationRequest request) {
        return followTaskService.simulateRisk(request);
    }

    @PostMapping("/follow-tasks/process")
    public Map<String, Object> processFollowTasks() {
        return Map.of("processed", followTaskService.processPendingTasksNow());
    }

    @GetMapping("/live-validation/status")
    public LiveValidationStatusView liveValidationStatus() {
        return liveValidationService.status();
    }

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

    @GetMapping("/execution-wallet/status")
    public ExecutionWalletStatus executionWalletStatus() {
        return followService.executionWalletStatus();
    }

    @GetMapping("/orders")
    public List<SmartWalletOrderView> orders(@RequestParam(defaultValue = "100") Integer limit) {
        return accountingService.listOrders(limit);
    }

    @GetMapping("/fills")
    public List<SmartWalletFillView> fills(@RequestParam(defaultValue = "100") Integer limit) {
        return accountingService.listFills(limit);
    }

    @GetMapping("/positions/live")
    public List<SmartWalletPositionView> livePositions() {
        return accountingService.listPositions();
    }

    @GetMapping("/positions/history")
    public List<SmartWalletPositionView> positionHistory(@RequestParam(defaultValue = "false") boolean closedOnly) {
        return accountingService.listPositions(closedOnly);
    }

    @GetMapping("/cash-ledger")
    public List<SmartWalletCashLedgerView> cashLedger(@RequestParam(defaultValue = "100") Integer limit) {
        return accountingService.listLedger(limit);
    }

    @GetMapping("/pnl/summary")
    public SmartWalletPnlSummaryView pnlSummary() {
        return accountingService.summary();
    }

    @GetMapping("/reports/performance")
    public SmartWalletPerformanceView performanceReport() {
        return accountingService.performance();
    }

    @GetMapping("/pnl/timeline")
    public List<SmartWalletPnlTimelinePoint> pnlTimeline(@RequestParam(defaultValue = "30") Integer days) {
        return accountingService.pnlTimeline(days);
    }

    @GetMapping("/reports/daily")
    public List<SmartWalletDailyReportView> dailyReports(@RequestParam(defaultValue = "30") Integer limit) {
        return accountingService.listDailyReports(limit);
    }

    @PostMapping("/reports/daily/rebuild")
    public Map<String, Object> rebuildDailyReports(@RequestParam(defaultValue = "7") Integer days) {
        return Map.of("rebuilt", accountingService.rebuildRecentReports(days));
    }

    @PostMapping("/accounting/backfill")
    public Map<String, Object> backfillAccounting(@RequestParam(defaultValue = "2000") Integer limit) {
        return Map.of("imported", accountingService.backfillFromFollowTasks(limit));
    }

    @PostMapping("/settlement/sync")
    public SettlementSyncSummary syncSettlements(@RequestParam(defaultValue = "500") Integer positionLimit,
                                                 @RequestParam(defaultValue = "100") Integer marketLimit,
                                                 @RequestParam(defaultValue = "false") boolean refreshCache) {
        return settlementSyncService.runNow(positionLimit, marketLimit, refreshCache);
    }

    @GetMapping("/settlement/status")
    public SettlementSyncStatusView settlementStatus() {
        return settlementSyncService.status();
    }

    @GetMapping("/settlement/runs")
    public List<SettlementRunView> settlementRuns(@RequestParam(defaultValue = "20") Integer limit) {
        return settlementSyncService.listRuns(limit);
    }

    @GetMapping("/settlement/retries")
    public List<SettlementRetryView> settlementRetries(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "20") Integer limit) {
        return settlementSyncService.listRetries(status, limit);
    }

    @GetMapping("/settlement/retries/report")
    public SettlementRetryReportView settlementRetryReport() {
        return settlementSyncService.retryReport();
    }

    @PostMapping("/settlement/retries/process")
    public SettlementSyncSummary processSettlementRetries(@RequestParam(required = false) Integer limit,
                                                          @RequestParam(defaultValue = "false") boolean refreshCache) {
        return settlementSyncService.processRetriesNow(limit, refreshCache);
    }

    @GetMapping("/reports/wallets")
    public List<SmartWalletReportAttributionItem> walletReports(@RequestParam(defaultValue = "20") Integer limit) {
        return accountingService.walletAttribution(limit);
    }

    @GetMapping("/reports/markets")
    public List<SmartWalletReportAttributionItem> marketReports(@RequestParam(defaultValue = "20") Integer limit) {
        return accountingService.marketAttribution(limit);
    }

    @PostMapping("/execution-wallet/derive-api-creds")
    public Map<String, Object> deriveExecutionWalletApiCreds() {
        return clobExecutionService.deriveApiCreds();
    }

    @PostMapping("/clob/dry-run")
    public Map<String, Object> clobDryRun(@RequestBody PolymarketOrderDryRunRequest request) {
        return clobExecutionService.dryRunOrder(request);
    }

    @PostMapping("/follow-tasks/{followTaskId}/dry-run")
    public Map<String, Object> followTaskDryRun(@PathVariable Long followTaskId) {
        return clobExecutionService.dryRunFollowTask(followTaskId);
    }

    @PostMapping("/follow-tasks/{followTaskId}/execute-live")
    public SmartWalletExecutionResult executeFollowTaskLive(@PathVariable Long followTaskId) {
        return clobExecutionService.executeFollowTask(followTaskId);
    }

    @GetMapping("/clob/markets/{conditionId}")
    public Object clobMarketMetadata(@PathVariable String conditionId) {
        return clobExecutionService.marketMetadata(conditionId);
    }

    @GetMapping("/clob/orders/{orderId}")
    public Map<String, Object> clobOrder(@PathVariable String orderId) {
        return clobExecutionService.getOrder(orderId);
    }

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

    private SignalRiskPresetView toSignalRiskPresetView(String name,
                                                        SmartWalletProperties.SignalRisk risk,
                                                        boolean activeDefault) {
        return SignalRiskPresetView.builder()
                .name(name)
                .activeDefault(activeDefault)
                .maxSignalAgeSeconds(risk.getMaxSignalAgeSeconds())
                .minDistinctWallets(risk.getMinDistinctWallets())
                .minConsensusScore(risk.getMinConsensusScore())
                .enableConsensusGate(risk.isEnableConsensusGate())
                .enableSingleWalletOverride(risk.isEnableSingleWalletOverride())
                .singleWalletOverrideMinWalletScore(risk.getSingleWalletOverrideMinWalletScore())
                .singleWalletOverrideMinWinRate(risk.getSingleWalletOverrideMinWinRate())
                .singleWalletOverrideMinTrades(risk.getSingleWalletOverrideMinTrades())
                .singleWalletOverrideMinTriggerSizeUsdc(risk.getSingleWalletOverrideMinTriggerSizeUsdc())
                .singleWalletOverrideMinRecommendedStakeUsdc(risk.getSingleWalletOverrideMinRecommendedStakeUsdc())
                .build();
    }
}
