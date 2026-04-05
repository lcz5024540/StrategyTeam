package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketOrderDryRunRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PolymarketClobExecutionService {

    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketPythonSdkBridge pythonSdkBridge;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final PolymarketOrderRequestFactory orderRequestFactory;

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

    public Map<String, Object> dryRunOrder(PolymarketOrderDryRunRequest request) {
        ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.requireLiveContext();
        if (request == null || request.getPrice() == null || request.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("price must be provided and positive for dry-run.");
        }
        String outcome = request.getOutcome() == null ? "YES" : request.getOutcome().trim().toUpperCase();
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(request.getConditionId());
        SmartWalletSignal signal = SmartWalletSignal.builder()
                .signalId("dry-run-" + request.getConditionId() + "-" + outcome)
                .followOutcome(outcome)
                .recommendedStakeUsdc(request.getStakeUsdc() == null ? wallet.getMinOrderUsdc() : request.getStakeUsdc())
                .market(PredictionMarketOpportunity.builder()
                        .marketId(request.getConditionId())
                        .question(request.getQuestion() != null ? request.getQuestion() : metadata.getQuestion())
                        .outcome(outcome)
                        .price(request.getPrice())
                        .conviction(BigDecimal.ONE)
                        .liquidityUsdc(BigDecimal.ZERO)
                        .category(metadata.getCategory())
                        .build())
                .build();
        PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared = orderRequestFactory.prepare(signal, wallet);
        return Map.of(
                "success", true,
                "dryRun", true,
                "walletReady", wallet.isLiveReady(),
                "preparedOrder", prepared,
                "marketMetadata", metadata
        );
    }
}
