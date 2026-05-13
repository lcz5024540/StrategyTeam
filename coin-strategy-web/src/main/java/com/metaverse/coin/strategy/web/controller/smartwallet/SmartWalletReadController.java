package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.model.CursorPageView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.OffsetPageView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderTradeOutcomeCoverageView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderTradeOutcomeSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletCashLedgerView;
import com.metaverse.coin.strategy.web.model.smartwallet.PolymarketProductOverviewView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletBlockerStatView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletCapacitySummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletFillView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletFollowAddressDailyReportView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletMarketLinkView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletOrderPnlCategoryAttributionView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletOrderView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPositionView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPnlTimelinePoint;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletReportAttributionItem;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletTradeOrderView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletTradeView;
import com.metaverse.coin.strategy.web.model.smartwallet.WalletScreenerView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletDailyReportView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletConflictMarketView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletProfileOverrideSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletProfileOverrideView;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementSyncStatusView;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementRunView;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementRetryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementRetryReportView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletClaimStatusView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletClaimRunView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletClaimRetryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletClaimRetryReportView;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.model.DiscoverySessionCoverageView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletMarketMonitorItemView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletMarketMonitoringSummaryView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfileStaleDiagnosticView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfileTierDiagnosticsSummaryView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletBehaviorProfileView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletDiagnosisView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderAnalysisView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletOrderStatsView;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletBehaviorProfileReadService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletDiscoveryReadService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketMonitoringReadService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletProfileDiagnosisReadService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletProfileOrderAnalysisReadService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletProfileOrderStatsReadService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletProfileStoreReadService;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletProfileTierDiagnosticReadService;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.AttributionRow;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.ConflictMarketRow;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.DailyReportRow;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.FollowAddressDailyReportRow;
import com.metaverse.coin.strategy.common.service.smartwallet.report.SmartWalletReportDtos.TimelinePoint;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletLeaderOutcomeReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletProfileReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletReportReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletSettlementReadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletReadController {

    private final SmartWalletReadService smartWalletReadService;
    private final SmartWalletReportReadService smartWalletReportReadService;
    private final SmartWalletProfileReadService smartWalletProfileReadService;
    private final SmartWalletSettlementReadService smartWalletSettlementReadService;
    private final SmartWalletLeaderOutcomeReadService smartWalletLeaderOutcomeReadService;
    private final SmartWalletDiscoveryReadService smartWalletDiscoveryReadService;
    private final SmartWalletMarketMonitoringReadService smartWalletMarketMonitoringReadService;
    private final SmartWalletProfileStoreReadService smartWalletProfileStoreReadService;
    private final SmartWalletBehaviorProfileReadService smartWalletBehaviorProfileReadService;
    private final SmartWalletProfileTierDiagnosticReadService smartWalletProfileTierDiagnosticReadService;
    private final SmartWalletProfileOrderAnalysisReadService smartWalletProfileOrderAnalysisReadService;
    private final SmartWalletProfileOrderStatsReadService smartWalletProfileOrderStatsReadService;
    private final SmartWalletProfileDiagnosisReadService smartWalletProfileDiagnosisReadService;

    public SmartWalletReadController(SmartWalletReadService smartWalletReadService,
                                     SmartWalletReportReadService smartWalletReportReadService,
                                     SmartWalletProfileReadService smartWalletProfileReadService,
                                     SmartWalletSettlementReadService smartWalletSettlementReadService,
                                     SmartWalletLeaderOutcomeReadService smartWalletLeaderOutcomeReadService,
                                     SmartWalletDiscoveryReadService smartWalletDiscoveryReadService,
                                     SmartWalletMarketMonitoringReadService smartWalletMarketMonitoringReadService,
                                     SmartWalletProfileStoreReadService smartWalletProfileStoreReadService,
                                     SmartWalletBehaviorProfileReadService smartWalletBehaviorProfileReadService,
                                     SmartWalletProfileTierDiagnosticReadService smartWalletProfileTierDiagnosticReadService,
                                     SmartWalletProfileOrderAnalysisReadService smartWalletProfileOrderAnalysisReadService,
                                     SmartWalletProfileOrderStatsReadService smartWalletProfileOrderStatsReadService,
                                     SmartWalletProfileDiagnosisReadService smartWalletProfileDiagnosisReadService) {
        this.smartWalletReadService = smartWalletReadService;
        this.smartWalletReportReadService = smartWalletReportReadService;
        this.smartWalletProfileReadService = smartWalletProfileReadService;
        this.smartWalletSettlementReadService = smartWalletSettlementReadService;
        this.smartWalletLeaderOutcomeReadService = smartWalletLeaderOutcomeReadService;
        this.smartWalletDiscoveryReadService = smartWalletDiscoveryReadService;
        this.smartWalletMarketMonitoringReadService = smartWalletMarketMonitoringReadService;
        this.smartWalletProfileStoreReadService = smartWalletProfileStoreReadService;
        this.smartWalletBehaviorProfileReadService = smartWalletBehaviorProfileReadService;
        this.smartWalletProfileTierDiagnosticReadService = smartWalletProfileTierDiagnosticReadService;
        this.smartWalletProfileOrderAnalysisReadService = smartWalletProfileOrderAnalysisReadService;
        this.smartWalletProfileOrderStatsReadService = smartWalletProfileOrderStatsReadService;
        this.smartWalletProfileDiagnosisReadService = smartWalletProfileDiagnosisReadService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return smartWalletReadService.health();
    }

    @GetMapping("/overview")
    public PolymarketProductOverviewView overview() {
        return smartWalletReadService.overview();
    }

    @GetMapping("/leader-outcomes/summary")
    public List<LeaderTradeOutcomeSummaryView> leaderOutcomeSummary(@RequestParam(required = false) String walletAddress,
                                                                    @RequestParam(defaultValue = "ALL") String window) {
        return smartWalletLeaderOutcomeReadService.summarize(walletAddress, window);
    }

    @GetMapping("/leader-outcomes/follow-coverage")
    public List<LeaderTradeOutcomeCoverageView> leaderOutcomeCoverage(@RequestParam(required = false) String walletAddress) {
        return smartWalletLeaderOutcomeReadService.coverage(walletAddress);
    }

    @GetMapping("/wallet-features/daily")
    public List<SmartWalletWalletFeatureDailyEntity> walletFeaturesDaily(@RequestParam(defaultValue = "100") Integer limit) {
        return smartWalletReadService.walletFeaturesDaily(limit);
    }

    @GetMapping("/discovery/session-coverage")
    public DiscoverySessionCoverageView discoverySessionCoverage() {
        return smartWalletDiscoveryReadService.sessionCoverage();
    }

    @GetMapping("/reports/market-monitoring")
    public SmartWalletMarketMonitoringSummaryView marketMonitoringSummary(
            @RequestParam(defaultValue = "40") Integer limit,
            @RequestParam(defaultValue = "14") Integer lookbackDays,
            @RequestParam(defaultValue = "7") Integer trendDays) {
        return smartWalletMarketMonitoringReadService.summary(limit, lookbackDays, trendDays);
    }

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
        return smartWalletMarketMonitoringReadService.tablePage(
                page, pageSize, lookbackDays, keyword, category,
                concentrationBand, hasPosition, positionDirection);
    }

    // ════════════════════════════════════════════════════════════════
    // E 批 watchlist 系列（Phase 4.1 基础读路径）
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/watchlist")
    public List<SmartWalletProfileEntity> watchlist() {
        return smartWalletProfileStoreReadService.listExecutableWatchlist();
    }

    @GetMapping("/watchlist/candidates")
    public List<SmartWalletProfileEntity> candidateWatchlist() {
        return smartWalletProfileStoreReadService.listQualified();
    }

    @GetMapping("/watchlist/executable")
    public List<SmartWalletProfileEntity> executableWatchlist() {
        return smartWalletProfileStoreReadService.listExecutableWatchlist();
    }

    @GetMapping("/watchlist/warm")
    public List<SmartWalletProfileEntity> warmWatchlist() {
        return smartWalletProfileStoreReadService.listWarmWatchlist();
    }

    @GetMapping("/watchlist/archetypes")
    public List<WalletArchetypeView> watchlistArchetypes(@RequestParam(defaultValue = "50") Integer limit,
                                                          @RequestParam(defaultValue = "candidates") String scope) {
        return smartWalletBehaviorProfileReadService.listArchetypes(scope, limit);
    }

    @GetMapping(value = "/watchlist/archetypes/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportWatchlistArchetypes(@RequestParam(defaultValue = "200") Integer limit,
                                                            @RequestParam(defaultValue = "candidates") String scope) {
        List<WalletArchetypeView> rows = smartWalletBehaviorProfileReadService.listArchetypes(scope, limit);
        return csvResponse(
                List.of("walletAddress", "archetype", "followLane", "recommendedExitLane",
                        "highFrequencyTradingAddress", "trades7d", "trades30d", "orderWinRate",
                        "averageOrderRoiRate", "medianOrderRoiRate", "settlementCompletionRate",
                        "earlyExitRate", "profitEarlyExitRate", "lossEarlyExitRate",
                        "winSourceType", "settlementWinRate", "stabilityScore", "qualityScore",
                        "replicabilityScore", "summary"),
                rows.stream().map(row -> List.of(
                        nullSafe(row.walletAddress()), nullSafe(row.archetype()), nullSafe(row.followLane()),
                        nullSafe(row.recommendedExitLane()),
                        nullSafe(row.highFrequencyTradingAddress()), nullSafe(row.trades7d()), nullSafe(row.trades30d()),
                        nullSafe(row.orderWinRate()), nullSafe(row.averageOrderRoiRate()), nullSafe(row.medianOrderRoiRate()),
                        nullSafe(row.settlementCompletionRate()), nullSafe(row.earlyExitRate()),
                        nullSafe(row.profitEarlyExitRate()), nullSafe(row.lossEarlyExitRate()),
                        nullSafe(row.winSourceType()), nullSafe(row.settlementWinRate()),
                        nullSafe(row.stabilityScore()), nullSafe(row.qualityScore()),
                        nullSafe(row.replicabilityScore()), nullSafe(row.summary())
                )).toList(),
                "smart_wallet_watchlist_archetypes",
                scope
        );
    }

    @GetMapping("/watchlist/behavior-profiles")
    public List<WalletBehaviorProfileView> watchlistBehaviorProfiles(@RequestParam(defaultValue = "30") Integer limit,
                                                                     @RequestParam(defaultValue = "candidates") String scope) {
        return smartWalletBehaviorProfileReadService.listBehaviorProfiles(scope, limit);
    }

    @GetMapping("/watchlist/tier-diagnostics")
    public List<SmartWalletProfileStaleDiagnosticView> watchlistTierDiagnostics(
            @RequestParam(defaultValue = "200") Integer limit) {
        return smartWalletProfileTierDiagnosticReadService.listProfileTierDiagnostics(limit);
    }

    @GetMapping("/watchlist/tier-diagnostics/summary")
    public List<SmartWalletProfileTierDiagnosticsSummaryView> watchlistTierDiagnosticsSummary() {
        return smartWalletProfileTierDiagnosticReadService.summarizeProfileTierDiagnostics();
    }

    // ════════════════════════════════════════════════════════════════
    // B 批 profiles 深度查询（Phase 4.4 第一步：order-analysis）
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/profiles/{walletAddress}/order-analysis")
    public CursorPageView<WalletOrderAnalysisView> walletOrderAnalysis(@org.springframework.web.bind.annotation.PathVariable String walletAddress,
                                                                       @RequestParam(defaultValue = "20") Integer limit,
                                                                       @RequestParam(required = false) String cursor,
                                                                       @RequestParam(required = false) String exitStyle,
                                                                       @RequestParam(required = false) Boolean isWin) {
        List<WalletOrderAnalysisView> rows = smartWalletProfileOrderAnalysisReadService
                .listByWalletAddress(walletAddress, exitStyle, isWin);
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        int start = parseOrderAnalysisCursor(cursor);
        if (start >= rows.size()) {
            return CursorPageView.empty();
        }
        int end = Math.min(rows.size(), start + safeLimit);
        String nextCursor = end < rows.size() ? String.valueOf(end) : null;
        return CursorPageView.of(rows.subList(start, end), nextCursor);
    }

    @GetMapping("/profiles/{walletAddress}/order-stats")
    public WalletOrderStatsView walletOrderStats(@org.springframework.web.bind.annotation.PathVariable String walletAddress,
                                                 @RequestParam(defaultValue = "30d") String window) {
        return smartWalletProfileOrderStatsReadService.getStats(walletAddress, window);
    }

    @GetMapping("/profiles/{walletAddress}/diagnosis")
    public WalletDiagnosisView walletDiagnosis(@org.springframework.web.bind.annotation.PathVariable String walletAddress) {
        return smartWalletProfileDiagnosisReadService.getDiagnosis(walletAddress);
    }

    private int parseOrderAnalysisCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Object nullSafe(Object value) {
        return value == null ? "" : value;
    }

    @GetMapping("/follow-tasks")
    public List<SmartWalletFollowTaskView> followTasks(@RequestParam(defaultValue = "50") Integer limit,
                                                       @RequestParam(defaultValue = "false") boolean includeRiskEvaluation) {
        return smartWalletReadService.followTasks(limit, includeRiskEvaluation);
    }

    @GetMapping("/follow-tasks/blocker-stats")
    public List<SmartWalletBlockerStatView> blockerStats(@RequestParam(defaultValue = "200") Integer taskLimit) {
        return smartWalletReadService.blockerStats(taskLimit);
    }

    @GetMapping("/positions/live")
    public List<SmartWalletPositionView> livePositions(@RequestParam(required = false) String mode) {
        return smartWalletReadService.livePositions(mode);
    }

    @GetMapping("/positions/next-settlements")
    public List<SmartWalletPositionView> nextSettlements(@RequestParam(defaultValue = "10") Integer limit,
                                                         @RequestParam(required = false) String mode) {
        return smartWalletReadService.nextSettlements(limit, mode);
    }

    @GetMapping("/fills")
    public List<SmartWalletFillView> fills(@RequestParam(defaultValue = "100") Integer limit,
                                           @RequestParam(required = false) String mode) {
        return smartWalletReadService.fills(limit, mode);
    }

    @GetMapping("/orders")
    public List<SmartWalletOrderView> orders(@RequestParam(defaultValue = "100") Integer limit,
                                             @RequestParam(required = false) String mode) {
        return smartWalletReadService.orders(limit, mode);
    }

    @GetMapping("/orders/page")
    public CursorPageView<SmartWalletOrderView> ordersPage(@RequestParam(defaultValue = "50") Integer limit,
                                                           @RequestParam(required = false) Long lastId,
                                                           @RequestParam(required = false) String mode,
                                                           @RequestParam(required = false) String pnl,
                                                           @RequestParam(required = false) String settlement,
                                                           @RequestParam(required = false) String side,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String q) {
        return smartWalletReadService.ordersCursor(limit, lastId, mode, pnl, settlement, side, status, q);
    }

    @GetMapping("/trade-orders/page")
    public CursorPageView<SmartWalletTradeOrderView> tradeOrdersPage(@RequestParam(defaultValue = "50") Integer limit,
                                                                     @RequestParam(required = false) Long lastId,
                                                                     @RequestParam(required = false) String mode,
                                                                     @RequestParam(required = false) String pnl,
                                                                     @RequestParam(required = false) String settlement,
                                                                     @RequestParam(required = false) String status,
                                                                     @RequestParam(required = false) String q) {
        return smartWalletReadService.tradeOrdersCursor(limit, lastId, mode, pnl, settlement, status, q);
    }

    @GetMapping("/trades/page")
    public CursorPageView<SmartWalletTradeView> tradesPage(@RequestParam(defaultValue = "50") Integer limit,
                                                           @RequestParam(required = false) Long lastId,
                                                           @RequestParam(required = false) String mode,
                                                           @RequestParam(required = false) String pnl,
                                                           @RequestParam(required = false) String settlement,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String q) {
        return smartWalletReadService.tradesCursor(limit, lastId, mode, pnl, settlement, status, q);
    }

    @GetMapping("/trade-orders/pnl-category-attribution")
    public SmartWalletOrderPnlCategoryAttributionView orderPnlCategoryAttribution(@RequestParam(required = false) String mode,
                                                                                  @RequestParam(defaultValue = "12") Integer limit) {
        return smartWalletReadService.orderPnlCategoryAttribution(limit, mode);
    }

    @GetMapping("/polymarket/markets/{conditionId}/link")
    public SmartWalletMarketLinkView marketLink(@org.springframework.web.bind.annotation.PathVariable String conditionId,
                                                @RequestParam(required = false) String question,
                                                @RequestParam(required = false) String outcome) {
        return smartWalletReadService.marketLink(conditionId, question, outcome);
    }

    @GetMapping("/reports/wallets")
    public List<AttributionRow> walletReports(@RequestParam(defaultValue = "20") Integer limit,
                                              @RequestParam(required = false) String mode) {
        return smartWalletReadService.walletReports(limit, mode);
    }

    @GetMapping("/reports/markets")
    public List<AttributionRow> marketReports(@RequestParam(defaultValue = "20") Integer limit,
                                              @RequestParam(required = false) String mode) {
        return smartWalletReadService.marketReports(limit, mode);
    }

    @GetMapping("/reports/daily")
    public List<DailyReportRow> dailyReports(@RequestParam(defaultValue = "30") Integer limit,
                                             @RequestParam(required = false) String mode) {
        return smartWalletReadService.dailyReports(limit, mode);
    }

    @GetMapping("/pnl/timeline")
    public List<TimelinePoint> pnlTimeline(@RequestParam(defaultValue = "30") Integer days,
                                           @RequestParam(defaultValue = "24") Integer bucketHours,
                                           @RequestParam(required = false) String mode) {
        return smartWalletReadService.pnlTimeline(days, bucketHours, mode);
    }

    @GetMapping("/reports/conflicting-markets")
    public List<ConflictMarketRow> conflictingMarkets(@RequestParam(defaultValue = "50") Integer limit,
                                                      @RequestParam(defaultValue = "false") boolean openOnly,
                                                      @RequestParam(required = false) String mode) {
        return smartWalletReadService.conflictingMarkets(limit, openOnly, mode);
    }

    // ════════════════════════════════════════════════════════════════
    // P1 Report endpoints (migrated from job)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/pnl/summary")
    public SmartWalletPnlSummaryView pnlSummary(@RequestParam(required = false) String mode) {
        return smartWalletReportReadService.pnlSummary(mode);
    }

    @GetMapping("/reports/performance")
    public SmartWalletPerformanceView performanceReport(@RequestParam(required = false) String mode) {
        return smartWalletReportReadService.performance(mode);
    }

    @GetMapping("/reports/follow-addresses/daily")
    public List<FollowAddressDailyReportRow> followAddressesDailyReports(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(required = false) String mode) {
        return smartWalletReadService.followAddressDailyReport(reportDate, limit, mode);
    }

    @GetMapping("/capacity/summary")
    public SmartWalletCapacitySummaryView capacitySummary(@RequestParam(required = false) String mode) {
        return smartWalletReportReadService.capacitySummary(mode);
    }

    @GetMapping("/cash-ledger")
    public List<SmartWalletCashLedgerView> cashLedger(@RequestParam(defaultValue = "100") Integer limit) {
        return smartWalletReadService.cashLedger(limit);
    }

    @GetMapping("/positions/history")
    public OffsetPageView<SmartWalletPositionView> positionHistory(
            @RequestParam(defaultValue = "false") boolean closedOnly,
            @RequestParam(required = false) String mode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return smartWalletReadService.positionHistory(closedOnly, mode, page, size);
    }

    @GetMapping(value = "/orders/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportOrders(@RequestParam(defaultValue = "5000") Integer limit,
                                               @RequestParam(required = false) String mode) {
        return csvResponse(
                List.of("id", "walletAddress", "marketId", "question", "outcome", "side", "orderType", "status",
                        "notionalUsdc", "costBasisUsdc", "feeUsdc", "tradingFeeUsdc", "gasFeeUsdc",
                        "realizedPnlUsdc", "exitReason", "placedAt", "filledAt"),
                smartWalletReadService.orders(limit, mode).stream().map(row -> List.of(
                        row.id(), row.walletAddress(), row.marketId(), row.question(), row.outcome(), row.side(),
                        row.orderType(), row.status(), row.notionalUsdc(), row.costBasisUsdc(), row.feeUsdc(),
                        row.tradingFeeUsdc(), row.gasFeeUsdc(), row.realizedPnlUsdc(), row.exitReason(),
                        row.placedAt(), row.filledAt()
                )).toList(),
                "smart_wallet_orders",
                mode
        );
    }

    @GetMapping(value = "/fills/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportFills(@RequestParam(defaultValue = "5000") Integer limit,
                                              @RequestParam(required = false) String mode) {
        return csvResponse(
                List.of("id", "orderId", "executionAddress", "marketId", "question", "outcome", "side",
                        "price", "size", "notionalUsdc", "feeUsdc", "tradingFeeUsdc", "feePol",
                        "gasFeeUsdc", "realizedPnlUsdc", "filledAt"),
                smartWalletReadService.fills(limit, mode).stream().map(row -> List.of(
                        row.id(), row.orderId(), row.executionAddress(), row.marketId(), row.question(), row.outcome(),
                        row.side(), row.price(), row.size(), row.notionalUsdc(), row.feeUsdc(),
                        row.tradingFeeUsdc(), row.feePol(), row.gasFeeUsdc(), row.realizedPnlUsdc(), row.filledAt()
                )).toList(),
                "smart_wallet_fills",
                mode
        );
    }

    @GetMapping(value = "/positions/live/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportLivePositions(@RequestParam(required = false) String mode) {
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "status", "quantity",
                        "averageEntryPrice", "costBasisUsdc", "originalCostBasisUsdc", "feePolTotal",
                        "realizedPnlUsdc", "unrealizedPnlUsdc", "pendingSettlementNetPnlUsdc",
                        "claimableProceedsUsdc", "marketEndAtBjt", "openedAt", "lastFilledAt", "closedAt"),
                smartWalletReadService.livePositions(mode).stream().map(row -> List.of(
                        row.id(), row.executionAddress(), row.marketId(), row.question(), row.outcome(), row.status(),
                        row.quantity(), row.averageEntryPrice(), row.costBasisUsdc(), row.originalCostBasisUsdc(),
                        row.feePolTotal(), row.realizedPnlUsdc(), row.unrealizedPnlUsdc(),
                        row.pendingSettlementNetPnlUsdc(), row.claimableProceedsUsdc(),
                        row.marketEndAtBjt(), row.openedAt(), row.lastFilledAt(), row.closedAt()
                )).toList(),
                "smart_wallet_live_positions",
                mode
        );
    }

    @GetMapping(value = "/positions/next-settlements/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportNextSettlements(@RequestParam(defaultValue = "5000") Integer limit,
                                                        @RequestParam(required = false) String mode) {
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "status", "quantity",
                        "averageEntryPrice", "costBasisUsdc", "originalCostBasisUsdc", "feePolTotal",
                        "realizedPnlUsdc", "unrealizedPnlUsdc", "pendingSettlementNetPnlUsdc",
                        "claimableProceedsUsdc", "marketEndAtBjt", "openedAt", "lastFilledAt", "closedAt"),
                smartWalletReadService.nextSettlements(limit, mode).stream().map(row -> List.of(
                        row.id(), row.executionAddress(), row.marketId(), row.question(), row.outcome(), row.status(),
                        row.quantity(), row.averageEntryPrice(), row.costBasisUsdc(), row.originalCostBasisUsdc(),
                        row.feePolTotal(), row.realizedPnlUsdc(), row.unrealizedPnlUsdc(),
                        row.pendingSettlementNetPnlUsdc(), row.claimableProceedsUsdc(),
                        row.marketEndAtBjt(), row.openedAt(), row.lastFilledAt(), row.closedAt()
                )).toList(),
                "smart_wallet_next_settlements",
                mode
        );
    }

    @GetMapping(value = "/positions/history/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportPositionHistory(@RequestParam(defaultValue = "false") boolean closedOnly,
                                                        @RequestParam(required = false) String mode,
                                                        @RequestParam(defaultValue = "1000") Integer size) {
        List<SmartWalletPositionView> rows = smartWalletReadService.allPositions(closedOnly, mode);
        return csvResponse(
                List.of("id", "executionAddress", "marketId", "question", "outcome", "status", "quantity",
                        "averageEntryPrice", "costBasisUsdc", "originalCostBasisUsdc", "feePolTotal",
                        "realizedPnlUsdc", "unrealizedPnlUsdc", "pendingSettlementNetPnlUsdc",
                        "claimableProceedsUsdc", "marketEndAtBjt", "openedAt", "lastFilledAt", "closedAt"),
                rows.stream().limit(Math.max(size == null ? 1000 : size, 1)).map(row -> List.of(
                        row.id(), row.executionAddress(), row.marketId(), row.question(), row.outcome(), row.status(),
                        row.quantity(), row.averageEntryPrice(), row.costBasisUsdc(), row.originalCostBasisUsdc(),
                        row.feePolTotal(), row.realizedPnlUsdc(), row.unrealizedPnlUsdc(),
                        row.pendingSettlementNetPnlUsdc(), row.claimableProceedsUsdc(),
                        row.marketEndAtBjt(), row.openedAt(), row.lastFilledAt(), row.closedAt()
                )).toList(),
                "smart_wallet_position_history",
                mode
        );
    }

    @GetMapping(value = "/reports/follow-addresses/daily/export", produces = "text/csv")
    public ResponseEntity<byte[]> exportFollowAddressDaily(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate,
                                                           @RequestParam(defaultValue = "500") Integer limit,
                                                           @RequestParam(required = false) String mode) {
        List<FollowAddressDailyReportRow> rows = smartWalletReadService.followAddressDailyReport(reportDate, limit, mode);
        return csvResponse(
                List.of("reportDate", "walletAddress", "totalOrders", "filledOrders", "openPositions", "closedPositions",
                        "winningPositions", "losingPositions", "breakevenPositions", "distinctMarkets", "tradedVolumeUsdc",
                        "grossExposureUsdc", "feeUsdc", "realizedPnlUsdc", "unrealizedPnlUsdc", "netPnlUsdc", "winRate",
                        "averageWinUsdc", "averageLossUsdc", "largestWinUsdc", "largestLossUsdc"),
                rows.stream().map(row -> List.of(
                        row.reportDate(), row.walletAddress(), row.totalOrders(), row.filledOrders(), row.openPositions(),
                        row.closedPositions(), row.winningPositions(), row.losingPositions(), row.breakevenPositions(),
                        row.distinctMarkets(), row.tradedVolumeUsdc(), row.grossExposureUsdc(), row.feeUsdc(),
                        row.realizedPnlUsdc(), row.unrealizedPnlUsdc(), row.netPnlUsdc(), row.winRate(),
                        row.averageWinUsdc(), row.averageLossUsdc(), row.largestWinUsdc(), row.largestLossUsdc()
                )).toList(),
                "smart_wallet_follow_address_daily",
                reportDate == null ? null : reportDate.toString()
        );
    }

    private ResponseEntity<byte[]> csvResponse(List<String> headers,
                                               List<? extends List<?>> rows,
                                               String prefix,
                                               String mode) {
        String suffix = mode == null || mode.isBlank() ? "all" : mode.trim().toLowerCase();
        String filename = prefix + "_" + suffix + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
        StringBuilder builder = new StringBuilder();
        builder.append('\uFEFF');
        builder.append(String.join(",", headers)).append('\n');
        for (List<?> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(csvCell(row.get(i)));
            }
            builder.append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }

    // ════════════════════════════════════════════════════════════════
    // P2: Profiles, Overrides, Allowlist, Blocklist
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/profiles")
    public List<SmartWalletProfileEntity> profiles() {
        return smartWalletProfileReadService.profiles();
    }

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
            @RequestParam(required = false) java.math.BigDecimal minQualityScore,
            @RequestParam(required = false) java.math.BigDecimal minReplicabilityScore) {
        return smartWalletProfileReadService.screener(
                scope, limit, cursor, sortBy, sortDirection, screenerTier,
                highQualityAddress, highFrequencyTradingAddress, winSourceType,
                recommendedExitLane, minQualityScore, minReplicabilityScore
        );
    }

    @GetMapping("/profiles/overrides")
    public List<SmartWalletProfileOverrideView> profileOverrides(@RequestParam(defaultValue = "100") Integer limit) {
        return smartWalletProfileReadService.listManualOverrides(limit);
    }

    @GetMapping("/profiles/overrides/summary")
    public SmartWalletProfileOverrideSummaryView profileOverridesSummary() {
        return smartWalletProfileReadService.summarizeManualOverrides();
    }

    @GetMapping("/allowlist")
    public List<SmartWalletManualAllowlistView> manualAllowlist(@RequestParam(defaultValue = "100") Integer limit) {
        return smartWalletProfileReadService.allowlist(limit);
    }

    @GetMapping("/allowlist/summary")
    public SmartWalletManualAllowlistSummaryView manualAllowlistSummary() {
        return smartWalletProfileReadService.allowlistSummary();
    }

    @GetMapping("/blocklist")
    public List<SmartWalletManualBlocklistView> manualBlocklist(@RequestParam(defaultValue = "100") Integer limit) {
        return smartWalletProfileReadService.blocklist(limit);
    }

    @GetMapping("/blocklist/summary")
    public SmartWalletManualBlocklistSummaryView manualBlocklistSummary() {
        return smartWalletProfileReadService.blocklistSummary();
    }

    // ════════════════════════════════════════════════════════════════
    // P3: Settlement & Claim
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/settlement/status")
    public SettlementSyncStatusView settlementStatus() {
        return smartWalletSettlementReadService.settlementStatus();
    }

    @GetMapping("/settlement/runs")
    public List<SettlementRunView> settlementRuns(@RequestParam(defaultValue = "20") Integer limit) {
        return smartWalletSettlementReadService.settlementRuns(limit);
    }

    @GetMapping("/settlement/retries")
    public List<SettlementRetryView> settlementRetries(@RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "50") Integer limit) {
        return smartWalletSettlementReadService.settlementRetries(status, limit);
    }

    @GetMapping("/settlement/retries/report")
    public SettlementRetryReportView settlementRetryReport() {
        return smartWalletSettlementReadService.settlementRetryReport();
    }

    @GetMapping("/claim/status")
    public SmartWalletClaimStatusView claimStatus() {
        return smartWalletSettlementReadService.claimStatus();
    }

    @GetMapping("/claim/runs")
    public List<SmartWalletClaimRunView> claimRuns(@RequestParam(defaultValue = "20") Integer limit) {
        return smartWalletSettlementReadService.claimRuns(limit);
    }

    @GetMapping("/claim/retries")
    public List<SmartWalletClaimRetryView> claimRetries(@RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "50") Integer limit) {
        return smartWalletSettlementReadService.claimRetries(status, limit);
    }

    @GetMapping("/claim/retries/report")
    public SmartWalletClaimRetryReportView claimRetryReport() {
        return smartWalletSettlementReadService.claimRetryReport();
    }
}
