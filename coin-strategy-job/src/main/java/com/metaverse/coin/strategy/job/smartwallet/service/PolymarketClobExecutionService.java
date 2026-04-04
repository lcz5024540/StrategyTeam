package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class PolymarketClobExecutionService {

    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketPythonSdkBridge pythonSdkBridge;
    private final PolymarketMarketMetadataService marketMetadataService;

    public Map<String, Object> deriveApiCreds() {
        return pythonSdkBridge.deriveApiCreds(walletContextLoader.requireLiveContext());
    }

    public Map<String, Object> getOrder(String orderId) {
        return pythonSdkBridge.getOrder(walletContextLoader.requireLiveContext(), orderId);
    }

    public Map<String, Object> cancelOrder(String orderId) {
        return pythonSdkBridge.cancelOrder(walletContextLoader.requireLiveContext(), orderId);
    }

    public PolymarketApiClient.MarketMetadata marketMetadata(String conditionId) {
        return marketMetadataService.getByConditionId(conditionId);
    }
}
