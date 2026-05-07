package com.metaverse.coin.strategy.job.marketdata.application;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.service.ClosedPositionIncrementalSyncService;
import com.metaverse.coin.strategy.job.smartwallet.service.MarketSnapshotCollectorService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.TradeEffectLabelService;
import com.metaverse.coin.strategy.job.smartwallet.service.WalletFeatureDailyService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class PredictionMarketDataApplicationService {

    private final PolymarketWalletDiscoveryService polymarketWalletDiscoveryService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final TradeEffectLabelService tradeEffectLabelService;
    private final WalletFeatureDailyService walletFeatureDailyService;
    private final ClosedPositionIncrementalSyncService closedPositionIncrementalSyncService;

    public PredictionMarketDataApplicationService(PolymarketWalletDiscoveryService polymarketWalletDiscoveryService,
                                                  MarketSnapshotCollectorService marketSnapshotCollectorService,
                                                  TradeEffectLabelService tradeEffectLabelService,
                                                  WalletFeatureDailyService walletFeatureDailyService,
                                                  ClosedPositionIncrementalSyncService closedPositionIncrementalSyncService) {
        this.polymarketWalletDiscoveryService = polymarketWalletDiscoveryService;
        this.marketSnapshotCollectorService = marketSnapshotCollectorService;
        this.tradeEffectLabelService = tradeEffectLabelService;
        this.walletFeatureDailyService = walletFeatureDailyService;
        this.closedPositionIncrementalSyncService = closedPositionIncrementalSyncService;
    }

    public PolymarketDiscoverySummary analyzeSourceWallets() {
        return polymarketWalletDiscoveryService.analyzeTopWallets();
    }

    public int syncWatchlistEvents() {
        return polymarketWalletDiscoveryService.syncWatchlistTrades();
    }

    public ClosedPositionIncrementalSyncService.LocalBackfillResult backfillLocalClosedPositions(String directory,
                                                                                                 Integer limit,
                                                                                                 boolean dryRun) {
        return closedPositionIncrementalSyncService.backfillLocalDirectory(directory, limit, dryRun);
    }

    public SmartWalletMarketSnapshotEntity collectMarketSnapshot(String conditionId, String outcome) {
        return marketSnapshotCollectorService.collectSnapshot(conditionId, outcome);
    }

    public List<SmartWalletMarketSnapshotEntity> collectRecentMarketSnapshots(Integer limit) {
        return marketSnapshotCollectorService.collectRecentActiveMarketSnapshots(limit);
    }

    public SmartWalletTradeEffectLabelEntity buildTradeEffectLabel(Long sourceEventId) {
        return tradeEffectLabelService.buildLabel(sourceEventId);
    }

    public List<SmartWalletTradeEffectLabelEntity> buildRecentTradeEffectLabels(Integer limit, boolean missingOnly) {
        return tradeEffectLabelService.buildRecentLabels(limit, missingOnly);
    }

    public SmartWalletWalletFeatureDailyEntity buildWalletFeatureDaily(String walletAddress, LocalDate featureDate) {
        return walletFeatureDailyService.buildFeature(walletAddress, featureDate);
    }

    public List<SmartWalletWalletFeatureDailyEntity> buildRecentWalletFeatureDaily(Integer walletLimit, Integer days, boolean missingOnly) {
        return walletFeatureDailyService.buildRecentFeatures(walletLimit, days, missingOnly);
    }

    public List<SmartWalletWalletFeatureDailyEntity> listWalletFeatureDaily(Integer limit) {
        return walletFeatureDailyService.listRecentFeatures(limit);
    }
}
