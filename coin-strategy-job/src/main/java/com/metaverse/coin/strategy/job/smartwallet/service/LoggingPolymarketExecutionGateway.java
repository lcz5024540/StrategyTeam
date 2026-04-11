package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class LoggingPolymarketExecutionGateway implements PolymarketExecutionGateway {

    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketOrderRequestFactory orderRequestFactory;
    private final PolymarketClobClientRouter clobClientRouter;

    @Override
    public SmartWalletExecutionResult execute(SmartWalletSignal signal, ExecutionMode mode) {
        if (mode == ExecutionMode.LIVE) {
            ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.requireLiveContext();
            PolymarketOrderRequestFactory.PreparedPolymarketOrder order = orderRequestFactory.prepare(signal, wallet);
            Map<String, Object> response = clobClientRouter.placeOrder(wallet, order);
            @SuppressWarnings("unchecked")
            Map<String, Object> orderResponse = response.get("response") instanceof Map<?, ?>
                    ? (Map<String, Object>) response.get("response")
                    : Map.of();
            String orderId = stringify(firstNonNull(response.get("orderID"), response.get("orderId"), response.get("id")));
            boolean success = truthy(firstNonNull(response.get("success"), response.get("ok"))) || orderId != null;
            String rawStatus = stringify(firstNonNull(
                    response.get("status"),
                    orderResponse.get("status"),
                    orderResponse.get("orderStatus"),
                    orderResponse.get("state")
            ));
            String normalizedStatus = normalizeLiveStatus(rawStatus, orderId, success);
            return SmartWalletExecutionResult.builder()
                    .signalId(signal.getSignalId())
                    .mode(mode)
                    .success(success)
                    .message(stringify(firstNonNull(response.get("message"), rawStatus, "LIVE order submitted to Polymarket CLOB.")))
                    .externalOrderId(orderId)
                    .executionAddress(wallet.getAddress())
                    .marketId(order.getMarketId())
                    .tokenId(order.getTokenId())
                    .outcome(order.getOutcome())
                    .side(order.getSide())
                    .orderType(wallet.getOrderType())
                    .status(normalizedStatus)
                    .price(order.getLimitPrice())
                    .size(order.getSize())
                    .notionalUsdc(order.getStakeUsdc())
                    .feeUsdc(java.math.BigDecimal.ZERO)
                    .build();
        }
        return SmartWalletExecutionResult.builder()
                .signalId(signal.getSignalId())
                .mode(ExecutionMode.PAPER)
                .success(true)
                .message("Paper order created from smart-wallet signal.")
                .externalOrderId("paper-" + signal.getSignalId())
                .marketId(signal.getMarket() == null ? null : signal.getMarket().getMarketId())
                .outcome(signal.getFollowOutcome())
                .side("BUY")
                .orderType("GTC")
                .status("FILLED")
                .price(signal.getMarket() == null ? null : signal.getMarket().getPrice())
                .notionalUsdc(signal.getRecommendedStakeUsdc())
                .feeUsdc(java.math.BigDecimal.ZERO)
                .build();
    }

    private Object firstNonNull(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private String normalizeLiveStatus(String rawStatus, String orderId, boolean success) {
        if (rawStatus != null) {
            String normalized = rawStatus.trim().toUpperCase();
            if ("OPEN".equals(normalized)
                    || "PLACED".equals(normalized)
                    || "PENDING".equals(normalized)
                    || "UNMATCHED".equals(normalized)) {
                return "LIVE";
            }
            if ("PARTIAL".equals(normalized)) {
                return "PARTIALLY_FILLED";
            }
            return normalized;
        }
        if (success && orderId != null) {
            return "LIVE";
        }
        return "FAILED";
    }
}
