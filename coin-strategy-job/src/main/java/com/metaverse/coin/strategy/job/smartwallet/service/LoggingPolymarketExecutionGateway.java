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
    private final PolymarketMarketMetadataService marketMetadataService;
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
            Integer liveIndexSet = resolveLiveIndexSet(order.getMarketId(), order.getTokenId(), order.getQuestion(), order.getOutcome());
            return SmartWalletExecutionResult.builder()
                    .signalId(signal.getSignalId())
                    .mode(mode)
                    .success(success)
                    .message(stringify(firstNonNull(response.get("message"), rawStatus, "LIVE order submitted to Polymarket CLOB.")))
                    .externalOrderId(orderId)
                    .executionAddress(wallet.getAddress())
                    .marketId(order.getMarketId())
                    .tokenId(order.getTokenId())
                    .indexSet(liveIndexSet)
                    .outcome(order.getOutcome())
                    .side(order.getSide())
                    .orderType(wallet.getOrderType())
                    .status(normalizedStatus)
                    .price(order.getLimitPrice())
                    .size(order.getSize())
                    .notionalUsdc(liveNotional)
                    .feeUsdc(liveFee)
                    .tradingFeeUsdc(liveFee)
                    .build();
        }
        java.math.BigDecimal paperNotional = signal.getRecommendedStakeUsdc();
        java.math.BigDecimal paperPrice    = signal.getMarket() == null ? null : signal.getMarket().getPrice();
        java.math.BigDecimal paperFee = estimateFee(paperNotional, properties.getExecutionWallet().getEstimatedFeeRate());
        String marketId = signal.getMarket() == null ? null : signal.getMarket().getMarketId();
        String question = signal.getMarket() == null ? null : signal.getMarket().getQuestion();
        String outcome = signal.getFollowOutcome();
        PaperTokenInfo paperTokenInfo = resolvePaperTokenInfo(marketId, question, outcome);
        return SmartWalletExecutionResult.builder()
                .signalId(signal.getSignalId())
                .mode(ExecutionMode.PAPER)
                .success(true)
                .message("Paper order created from smart-wallet signal.")
                .externalOrderId("paper-" + signal.getSignalId())
                .marketId(marketId)
                .tokenId(paperTokenInfo.tokenId())
                .indexSet(paperTokenInfo.indexSet())
                .outcome(outcome)
                .side("BUY")
                .orderType("GTC")
                .status("FILLED")
                .price(paperPrice)
                .notionalUsdc(paperNotional)
                .feeUsdc(paperFee)
                .tradingFeeUsdc(paperFee)
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

    /** Best-effort resolve indexSet for a live order from cached/fetched metadata. */
    private Integer resolveLiveIndexSet(String marketId, String tokenId, String questionHint, String outcome) {
        if (!org.springframework.util.StringUtils.hasText(marketId)
                || !org.springframework.util.StringUtils.hasText(tokenId)) {
            return null;
        }
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                    marketId, false, questionHint,
                    outcome == null ? java.util.List.of() : java.util.List.of(outcome));
            return metadata == null ? null : metadata.resolveIndexSet(tokenId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Resolved paper token info: tokenId + indexSet.
     */
    private record PaperTokenInfo(String tokenId, Integer indexSet) {}

    private PaperTokenInfo resolvePaperTokenInfo(String marketId, String questionHint, String outcome) {
        if (!org.springframework.util.StringUtils.hasText(marketId)
                || !org.springframework.util.StringUtils.hasText(outcome)) {
            return new PaperTokenInfo(null, null);
        }
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                    marketId,
                    false,
                    questionHint,
                    java.util.List.of(outcome)
            );
            if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
                return new PaperTokenInfo(null, null);
            }
            String tokenId = metadata.getOutcomeTokenIds().entrySet().stream()
                    .filter(entry -> org.springframework.util.StringUtils.hasText(entry.getKey())
                            && org.springframework.util.StringUtils.hasText(entry.getValue()))
                    .filter(entry -> outcome.trim().equalsIgnoreCase(entry.getKey().trim()))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            Integer indexSet = tokenId != null ? metadata.resolveIndexSet(tokenId) : null;
            return new PaperTokenInfo(tokenId, indexSet);
        } catch (RuntimeException ignored) {
            return new PaperTokenInfo(null, null);
        }
    }
}
