package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class PolymarketMarketMetadataService {

    private final PolymarketApiClient polymarketApiClient;
    private final Map<String, PolymarketApiClient.MarketMetadata> cache = new ConcurrentHashMap<>();

    public PolymarketApiClient.MarketMetadata getByConditionId(String conditionId) {
        return getByConditionId(conditionId, false);
    }

    public PolymarketApiClient.MarketMetadata getByConditionId(String conditionId, boolean refresh) {
        if (!StringUtils.hasText(conditionId)) {
            throw new IllegalArgumentException("conditionId is required");
        }
        String normalized = conditionId.trim();
        if (refresh) {
            cache.remove(normalized);
        }
        return cache.computeIfAbsent(normalized, polymarketApiClient::fetchMarketMetadata);
    }

    public void evict(String conditionId) {
        if (StringUtils.hasText(conditionId)) {
            cache.remove(conditionId.trim());
        }
    }
}
