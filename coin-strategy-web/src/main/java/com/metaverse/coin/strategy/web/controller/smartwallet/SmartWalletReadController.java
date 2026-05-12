package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.model.CursorPageView;
import com.metaverse.coin.strategy.web.model.smartwallet.PolymarketProductOverviewView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletBlockerStatView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletFillView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletMarketLinkView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletOrderPnlCategoryAttributionView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletOrderView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPositionView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletPnlTimelinePoint;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletReportAttributionItem;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletTradeOrderView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletTradeView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletDailyReportView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletConflictMarketView;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/smart-wallet")
public class SmartWalletReadController {

    private final SmartWalletReadService smartWalletReadService;

    public SmartWalletReadController(SmartWalletReadService smartWalletReadService) {
        this.smartWalletReadService = smartWalletReadService;
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
}
