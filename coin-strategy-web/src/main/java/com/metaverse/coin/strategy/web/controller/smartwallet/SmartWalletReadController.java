package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.model.CursorPageView;
import com.metaverse.coin.strategy.web.model.OffsetPageView;
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
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletProfileReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletReportReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletSettlementReadService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletReadController {

    private final SmartWalletReadService smartWalletReadService;
    private final SmartWalletReportReadService smartWalletReportReadService;
    private final SmartWalletProfileReadService smartWalletProfileReadService;
    private final SmartWalletSettlementReadService smartWalletSettlementReadService;

    public SmartWalletReadController(SmartWalletReadService smartWalletReadService,
                                     SmartWalletReportReadService smartWalletReportReadService,
                                     SmartWalletProfileReadService smartWalletProfileReadService,
                                     SmartWalletSettlementReadService smartWalletSettlementReadService) {
        this.smartWalletReadService = smartWalletReadService;
        this.smartWalletReportReadService = smartWalletReportReadService;
        this.smartWalletProfileReadService = smartWalletProfileReadService;
        this.smartWalletSettlementReadService = smartWalletSettlementReadService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return smartWalletReadService.health();
    }

    @GetMapping("/overview")
    public PolymarketProductOverviewView overview() {
        return smartWalletReadService.overview();
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
    public List<SmartWalletReportAttributionItem> walletReports(@RequestParam(defaultValue = "20") Integer limit,
                                                                @RequestParam(required = false) String mode) {
        return smartWalletReadService.walletReports(limit, mode);
    }

    @GetMapping("/reports/markets")
    public List<SmartWalletReportAttributionItem> marketReports(@RequestParam(defaultValue = "20") Integer limit,
                                                                @RequestParam(required = false) String mode) {
        return smartWalletReadService.marketReports(limit, mode);
    }

    @GetMapping("/reports/daily")
    public List<SmartWalletDailyReportView> dailyReports(@RequestParam(defaultValue = "30") Integer limit,
                                                         @RequestParam(required = false) String mode) {
        return smartWalletReadService.dailyReports(limit, mode);
    }

    @GetMapping("/pnl/timeline")
    public List<SmartWalletPnlTimelinePoint> pnlTimeline(@RequestParam(defaultValue = "30") Integer days,
                                                         @RequestParam(defaultValue = "24") Integer bucketHours,
                                                         @RequestParam(required = false) String mode) {
        return smartWalletReadService.pnlTimeline(days, bucketHours, mode);
    }

    @GetMapping("/reports/conflicting-markets")
    public List<SmartWalletConflictMarketView> conflictingMarkets(@RequestParam(defaultValue = "50") Integer limit,
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
    public List<SmartWalletFollowAddressDailyReportView> followAddressesDailyReports(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportDate,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(required = false) String mode) {
        return smartWalletReportReadService.followAddressDailyReport(reportDate, limit, mode);
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

    // ════════════════════════════════════════════════════════════════
    // P2: Profiles, Overrides, Allowlist, Blocklist
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/profiles")
    public List<SmartWalletProfileEntity> profiles() {
        return smartWalletProfileReadService.profiles();
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
