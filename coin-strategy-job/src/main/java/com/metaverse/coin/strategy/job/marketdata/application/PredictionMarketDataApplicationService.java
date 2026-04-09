package com.metaverse.coin.strategy.job.marketdata.application;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.service.MarketSnapshotCollectorService;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import com.metaverse.coin.strategy.job.smartwallet.service.TradeEffectLabelService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PredictionMarketDataApplicationService {

    private final PolymarketWalletDiscoveryService polymarketWalletDiscoveryService;
    private final MarketSnapshotCollectorService marketSnapshotCollectorService;
    private final TradeEffectLabelService tradeEffectLabelService;

    public PredictionMarketDataApplicationService(PolymarketWalletDiscoveryService polymarketWalletDiscoveryService,
                                                  MarketSnapshotCollectorService marketSnapshotCollectorService,
                                                  TradeEffectLabelService tradeEffectLabelService) {
        this.polymarketWalletDiscoveryService = polymarketWalletDiscoveryService;
        this.marketSnapshotCollectorService = marketSnapshotCollectorService;
        this.tradeEffectLabelService = tradeEffectLabelService;
    }

    public PolymarketDiscoverySummary analyzeSourceWallets() {
        return polymarketWalletDiscoveryService.analyzeTopWallets();
    }

    public int syncWatchlistEvents() {
        return polymarketWalletDiscoveryService.syncWatchlistTrades();
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
}
