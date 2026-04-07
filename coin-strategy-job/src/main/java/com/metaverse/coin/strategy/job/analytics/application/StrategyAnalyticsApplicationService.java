package com.metaverse.coin.strategy.job.analytics.application;

import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCapacitySummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletConflictMarketView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlTimelinePoint;
import com.metaverse.coin.strategy.job.smartwallet.service.SmartWalletAccountingService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StrategyAnalyticsApplicationService {

    private final SmartWalletAccountingService smartWalletAccountingService;

    public StrategyAnalyticsApplicationService(SmartWalletAccountingService smartWalletAccountingService) {
        this.smartWalletAccountingService = smartWalletAccountingService;
    }

    public SmartWalletPnlSummaryView pnlSummary() {
        return smartWalletAccountingService.summary();
    }

    public SmartWalletPerformanceView performance() {
        return smartWalletAccountingService.performance();
    }

    public SmartWalletCapacitySummaryView capacitySummary() {
        return smartWalletAccountingService.capacitySummary();
    }

    public List<SmartWalletPnlTimelinePoint> pnlTimeline(int days) {
        return smartWalletAccountingService.pnlTimeline(days);
    }

    public List<SmartWalletConflictMarketView> conflictingMarkets(int limit, boolean openOnly) {
        return smartWalletAccountingService.conflictingMarkets(limit, openOnly);
    }
}
