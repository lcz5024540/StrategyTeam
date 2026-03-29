package com.metaverse.coin.strategy.job.smartwallet.controller;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfile;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletScanResponse;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSelectionSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.UpsertMarketRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.UpsertWalletRequest;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketSmartWalletMonitorService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.PredictionMarketOpportunityService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletFollowTaskService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletRegistryService;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/smart-wallet")
@RequiredArgsConstructor
public class SmartWalletController {

    private final SmartWalletRegistryService walletRegistryService;
    private final PredictionMarketOpportunityService opportunityService;
    private final SmartWalletFollowService followService;
    private final PolymarketSmartWalletMonitorService polymarketMonitorService;
    private final PolymarketWalletDiscoveryService polymarketWalletDiscoveryService;
    private final SmartWalletSelectionService selectionService;
    private final SmartWalletFollowTaskService followTaskService;

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

    @GetMapping("/profiles")
    public List<SmartWalletProfileEntity> profiles() {
        return polymarketMonitorService.listProfiles();
    }

    @GetMapping("/watchlist")
    public List<SmartWalletProfileEntity> watchlist() {
        return polymarketMonitorService.listWatchlist();
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
    public List<SmartWalletFollowTaskView> followTasks() {
        return followTaskService.listFollowTasks();
    }

    @PostMapping("/follow-tasks/process")
    public Map<String, Object> processFollowTasks() {
        return Map.of("processed", followTaskService.processPendingTasksNow());
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
}
