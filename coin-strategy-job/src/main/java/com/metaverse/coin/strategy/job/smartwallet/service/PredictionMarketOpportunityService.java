package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.UpsertMarketRequest;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PredictionMarketOpportunityService {

    private final SmartWalletProperties properties;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final Map<String, PredictionMarketOpportunity> markets = new ConcurrentHashMap<>();

    public PredictionMarketOpportunityService(SmartWalletProperties properties,
                                              PolymarketMarketMetadataService marketMetadataService) {
        this.properties = properties;
        this.marketMetadataService = marketMetadataService;
    }

    @PostConstruct
    public void loadSeeds() {
        for (SmartWalletProperties.MarketSeed seed : properties.getMarkets()) {
            PredictionMarketOpportunity market = PredictionMarketOpportunity.builder()
                    .marketId(seed.getMarketId())
                    .question(seed.getQuestion())
                    .outcome(seed.getOutcome())
                    .price(seed.getPrice())
                    .conviction(seed.getConviction())
                    .liquidityUsdc(seed.getLiquidityUsdc())
                    .category(seed.getCategory())
                    .tags(seed.getTags())
                    .build();
            storeMarket(market);
        }
    }

    public List<PredictionMarketOpportunity> list() {
        List<PredictionMarketOpportunity> result = new ArrayList<>(markets.values());
        result.sort(Comparator.comparing(PredictionMarketOpportunity::getMarketId));
        return result;
    }

    public PredictionMarketOpportunity upsert(UpsertMarketRequest request) {
        PredictionMarketOpportunity market = PredictionMarketOpportunity.builder()
                .marketId(request.getMarketId())
                .question(request.getQuestion())
                .outcome(request.getOutcome())
                .price(request.getPrice())
                .conviction(request.getConviction())
                .liquidityUsdc(request.getLiquidityUsdc())
                .category(request.getCategory())
                .tags(request.getTags())
                .build();
        storeMarket(market);
        return market;
    }

    private void storeMarket(PredictionMarketOpportunity market) {
        if (market == null || market.getMarketId() == null) {
            return;
        }
        markets.put(market.getMarketId(), market);
        marketMetadataService.registerLocalMetadata(
                market.getMarketId(),
                market.getQuestion(),
                market.getCategory(),
                market.getOutcome() == null ? List.of() : List.of(market.getOutcome()));
    }
}
