package com.metaverse.coin.strategy.job.marketdata.application;

import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketWalletDiscoveryService;
import org.springframework.stereotype.Service;

@Service
public class PredictionMarketDataApplicationService {

    private final PolymarketWalletDiscoveryService polymarketWalletDiscoveryService;

    public PredictionMarketDataApplicationService(PolymarketWalletDiscoveryService polymarketWalletDiscoveryService) {
        this.polymarketWalletDiscoveryService = polymarketWalletDiscoveryService;
    }

    public PolymarketDiscoverySummary analyzeSourceWallets() {
        return polymarketWalletDiscoveryService.analyzeTopWallets();
    }

    public int syncWatchlistEvents() {
        return polymarketWalletDiscoveryService.syncWatchlistTrades();
    }
}
