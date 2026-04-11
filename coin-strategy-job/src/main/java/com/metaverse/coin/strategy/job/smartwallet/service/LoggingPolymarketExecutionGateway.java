package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LoggingPolymarketExecutionGateway implements PolymarketExecutionGateway {

    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketOrderRequestFactory orderRequestFactory;
    private final PolymarketClobClientRouter clobClientRouter;
    private final SmartWalletProperties properties;

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
            java.math.BigDecimal liveNotional = order.getStakeUsdc();
            java.math.BigDecimal liveFee = estimateFee(liveNotional, properties.getExecutionWallet().getEstimatedFeeRate());
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
                    .notionalUsdc(liveNotional)
                    .feeUsdc(liveFee)
                    .build();
        }
        java.math.BigDecimal paperNotional = signal.getRecommendedStakeUsdc();
        java.math.BigDecimal paperPrice    = signal.getMarket() == null ? null : signal.getMarket().getPrice();
        java.math.BigDecimal paperFee = estimateFee(paperNotional, properties.getExecutionWallet().getEstimatedFeeRate());
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
                .price(paperPrice)
                .notionalUsdc(paperNotional)
                .feeUsdc(paperFee)
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

    /** Taker fee = notional * feeRate (single-leg, entry only). Returns ZERO when inputs are null. */
    private java.math.BigDecimal estimateFee(java.math.BigDecimal notional, java.math.BigDecimal feeRate) {
        if (notional == null || feeRate == null) {
            return java.math.BigDecimal.ZERO;
        }
        return notional.multiply(feeRate).setScale(8, java.math.RoundingMode.HALF_UP);
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
