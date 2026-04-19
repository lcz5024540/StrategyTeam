package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketOrderDryRunRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PolymarketClobExecutionService {

    private final ExecutionWalletContextLoader walletContextLoader;
    private final PolymarketClobClientRouter clobClientRouter;
    private final PolymarketApiClient polymarketApiClient;
    private final PolymarketMarketDataStreamService marketDataStreamService;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final PolymarketOrderRequestFactory orderRequestFactory;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletFollowTaskService followTaskService;

    public Map<String, Object> deriveApiCreds() {
        return clobClientRouter.deriveApiCreds(walletContextLoader.requireLiveContext());
    }

    public Map<String, Object> getOrder(String orderId) {
        return clobClientRouter.getOrder(walletContextLoader.requireLiveContext(), orderId);
    }

    public Map<String, Object> cancelOrder(String orderId) {
        return clobClientRouter.cancelOrder(walletContextLoader.requireLiveContext(), orderId);
    }

    public PolymarketApiClient.MarketMetadata marketMetadata(String conditionId) {
        return marketMetadataService.getByConditionId(conditionId);
    }

    public Map<String, Object> marketPriceReference(String conditionId,
                                                    String outcome,
                                                    String tokenId,
                                                    BigDecimal fallbackPrice,
                                                    String questionHint) {
        String normalizedOutcome = normalizeOutcome(outcome);
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                conditionId,
                false,
                questionHint,
                List.of(normalizedOutcome)
        );
        String resolvedTokenId = firstNonBlank(tokenId, resolveTokenId(metadata, normalizedOutcome));
        return buildPriceReference(conditionId, normalizedOutcome, resolvedTokenId, fallbackPrice);
    }

    public Map<String, Object> liveWalletState(String conditionId) {
        ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.load();
        if (!StringUtils.hasText(wallet.getAddress())) {
            throw new IllegalStateException("Execution wallet address is missing.");
        }
        return Map.of(
                "walletAddress", wallet.getAddress(),
                "balanceUsdc", polymarketApiClient.fetchBalance(wallet.getAddress()),
                "positions", polymarketApiClient.fetchPositions(wallet.getAddress(), conditionId)
        );
    }

    public Map<String, Object> dryRunOrder(PolymarketOrderDryRunRequest request) {
        if (request == null || !StringUtils.hasText(request.getConditionId())) {
            throw new IllegalArgumentException("conditionId must be provided for dry-run.");
        }
        ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.load();
        String outcome = normalizeOutcome(request.getOutcome());
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                request.getConditionId(),
                false,
                request.getQuestion(),
                List.of(outcome)
        );
        String resolvedTokenId = firstNonBlank(request.getTokenId(), resolveTokenId(metadata, outcome));
        Map<String, Object> priceReference = buildPriceReference(
                request.getConditionId(),
                outcome,
                resolvedTokenId,
                request.getPrice()
        );
        BigDecimal entryPrice = resolveEntryPrice(request.getPrice(), priceReference);
        if (entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("price must be provided, or a usable real-time price reference must be available.");
        }
        String question = firstNonBlank(request.getQuestion(), metadata == null ? null : metadata.getQuestion(), request.getConditionId());
        BigDecimal tickSize = metadata == null ? null : metadata.getMinimumTickSize();
        PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared = orderRequestFactory.prepareLocalDraft(
                "dry-run-" + request.getConditionId() + "-" + outcome,
                request.getConditionId(),
                question,
                outcome,
                request.getStakeUsdc(),
                entryPrice,
                resolvedTokenId,
                tickSize,
                metadata != null && metadata.isNegRisk(),
                wallet
        );
        LinkedHashMap<String, Object> response = baseDryRunResponse(wallet, prepared, metadata, priceReference);
        response.put("marketSelection", buildMarketSelection(request.getConditionId(), question, outcome, metadata, resolvedTokenId));
        response.put("localPositionDraft", buildLocalPositionDraft(prepared, entryPrice));
        response.put("settlementSimulation", buildSettlementSimulation(prepared));
        return response;
    }

    public Map<String, Object> dryRunFollowTask(Long followTaskId) {
        if (followTaskId == null) {
            throw new IllegalArgumentException("followTaskId must be provided.");
        }
        SmartWalletFollowTaskEntity task = followTaskMapper.selectById(followTaskId);
        if (task == null) {
            throw new IllegalArgumentException("Smart-wallet follow task not found: " + followTaskId);
        }
        if (!StringUtils.hasText(task.getMarketId())) {
            throw new IllegalStateException("Follow task is missing marketId.");
        }
        if (task.getTriggerPrice() == null || task.getTriggerPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Follow task is missing triggerPrice.");
        }
        if (!StringUtils.hasText(task.getOutcome())) {
            throw new IllegalStateException("Follow task is missing outcome.");
        }

        ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.load();
        SignalRiskDecision decision = followTaskService.evaluateTask(followTaskId);
        String outcome = normalizeOutcome(task.getOutcome());
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                task.getMarketId(),
                false,
                task.getQuestion(),
                List.of(outcome)
        );
        String resolvedTokenId = resolveTokenId(metadata, outcome);
        Map<String, Object> priceReference = buildPriceReference(task.getMarketId(), outcome, resolvedTokenId, task.getTriggerPrice());
        PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared = orderRequestFactory.prepareLocalDraft(
                "follow-task-" + task.getId(),
                task.getMarketId(),
                firstNonBlank(task.getQuestion(), metadata == null ? null : metadata.getQuestion(), task.getMarketId()),
                outcome,
                resolveStake(task, wallet),
                resolveEntryPrice(task.getTriggerPrice(), priceReference),
                resolvedTokenId,
                metadata == null ? null : metadata.getMinimumTickSize(),
                metadata != null && metadata.isNegRisk(),
                wallet
        );
        Map<String, Object> followTask = new LinkedHashMap<>();
        followTask.put("id", task.getId());
        followTask.put("status", task.getStatus());
        followTask.put("executionMode", task.getExecutionMode());
        followTask.put("walletAddress", task.getWalletAddress());
        followTask.put("marketId", task.getMarketId());
        followTask.put("question", task.getQuestion());
        followTask.put("outcome", task.getOutcome());
        followTask.put("side", task.getSide());
        followTask.put("triggerPrice", task.getTriggerPrice());
        followTask.put("recommendedStakeUsdc", task.getRecommendedStakeUsdc());
        followTask.put("externalOrderId", task.getExternalOrderId());
        followTask.put("errorMessage", task.getErrorMessage());
        LinkedHashMap<String, Object> response = baseDryRunResponse(wallet, prepared, metadata, priceReference);
        response.put("taskExecutable", decision.isAccepted());
        response.put("riskDecision", decision);
        response.put("followTask", followTask);
        response.put("marketSelection", buildMarketSelection(task.getMarketId(), firstNonBlank(task.getQuestion(), metadata == null ? null : metadata.getQuestion(), task.getMarketId()), outcome, metadata, resolvedTokenId));
        response.put("localPositionDraft", buildLocalPositionDraft(prepared, prepared.getLimitPrice()));
        response.put("settlementSimulation", buildSettlementSimulation(prepared));
        return response;
    }

    public SmartWalletExecutionResult executeFollowTask(Long followTaskId) {
        return followTaskService.executeTaskNow(followTaskId, com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode.LIVE);
    }

    public SmartWalletExecutionResult executeLiveExit(SmartWalletPositionEntity position,
                                                      BigDecimal requestedPrice,
                                                      String exitReason) {
        if (position == null) {
            throw new IllegalArgumentException("position must be provided for live exit.");
        }
        ExecutionWalletContextLoader.ExecutionWalletContext wallet = walletContextLoader.requireLiveContext();
        ExitPriceReference priceReference = resolveLiveExitPriceReference(position, requestedPrice);
        PolymarketOrderRequestFactory.PreparedPolymarketOrder order = orderRequestFactory.prepareExit(position, priceReference.price(), wallet);
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
        BigDecimal liveNotional = order.getStakeUsdc();
        BigDecimal liveFee = estimateFee(liveNotional, wallet.getEstimatedFeeRate());
        return SmartWalletExecutionResult.builder()
                .signalId(order.getSignalId())
                .mode(com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode.LIVE)
                .success(success)
                .message(stringify(firstNonNull(
                        response.get("message"),
                        rawStatus,
                        success
                                ? "LIVE exit order submitted to Polymarket CLOB. reason=" + exitReason + ", pricingSource=" + priceReference.source()
                                : "LIVE exit order submission failed. reason=" + exitReason + ", pricingSource=" + priceReference.source()
                )))
                .externalOrderId(orderId)
                .executionAddress(wallet.getAddress())
                .marketId(order.getMarketId())
                .tokenId(order.getTokenId())
                .indexSet(position.getIndexSet())
                .outcome(order.getOutcome())
                .side(order.getSide())
                .orderType("EXIT")
                .status(normalizedStatus)
                .price(order.getLimitPrice())
                .size(order.getSize())
                .notionalUsdc(liveNotional)
                .feeUsdc(liveFee)
                .build();
    }

    private ExitPriceReference resolveLiveExitPriceReference(SmartWalletPositionEntity position,
                                                             BigDecimal requestedPrice) {
        BigDecimal fallbackPrice = firstPositive(requestedPrice, safe(position == null ? null : position.getLastMarkPrice()), safe(position == null ? null : position.getAverageEntryPrice()));
        if (position == null || !StringUtils.hasText(position.getMarketId()) || !StringUtils.hasText(position.getOutcome())) {
            return new ExitPriceReference(fallbackPrice, "POSITION_FALLBACK");
        }
        String outcome = normalizeOutcome(position.getOutcome());
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                position.getMarketId(),
                false,
                position.getQuestion(),
                List.of(outcome)
        );
        String tokenId = firstNonBlank(position.getTokenId(), resolveTokenId(metadata, outcome));
        if (!StringUtils.hasText(tokenId)) {
            return new ExitPriceReference(fallbackPrice, fallbackPrice.compareTo(BigDecimal.ZERO) > 0 ? "POSITION_FALLBACK_TOKEN_MISSING" : "UNAVAILABLE");
        }

        try {
            PolymarketMarketDataStreamService.MarketDataSnapshot wsSnapshot = marketDataStreamService.getSnapshot(tokenId);
            PolymarketApiClient.OrderBook orderBook;
            BigDecimal lastTradePrice = BigDecimal.ZERO;
            String source;
            if (wsSnapshot != null && wsSnapshot.getOrderBook() != null) {
                orderBook = wsSnapshot.getOrderBook();
                lastTradePrice = safe(wsSnapshot.getLastTradePrice());
                source = "WS_BEST_BID";
            } else {
                orderBook = polymarketApiClient.fetchOrderBook(tokenId);
                List<PolymarketApiClient.MarketTrade> trades = polymarketApiClient.fetchMarketTrades(position.getMarketId(), 20, null);
                if (!trades.isEmpty()) {
                    lastTradePrice = safe(trades.get(0).getPrice());
                }
                source = "REST_BEST_BID";
            }
            BigDecimal bestBid = topPrice(orderBook == null ? null : orderBook.getBids());
            BigDecimal midpoint = safe(orderBook == null ? null : orderBook.getMidpoint());
            BigDecimal referencePrice = firstPositive(bestBid, midpoint, lastTradePrice, fallbackPrice);
            if (referencePrice.compareTo(BigDecimal.ZERO) > 0) {
                return new ExitPriceReference(referencePrice, bestBid.compareTo(BigDecimal.ZERO) > 0 ? source : source + "_FALLBACK");
            }
            return new ExitPriceReference(fallbackPrice, fallbackPrice.compareTo(BigDecimal.ZERO) > 0 ? "POSITION_FALLBACK_NO_BOOK" : "UNAVAILABLE");
        } catch (Exception ex) {
            return new ExitPriceReference(fallbackPrice, fallbackPrice.compareTo(BigDecimal.ZERO) > 0 ? "POSITION_FALLBACK_BOOK_ERROR" : "BOOK_ERROR");
        }
    }

    private BigDecimal resolveStake(SmartWalletFollowTaskEntity task,
                                    ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        if (task.getRecommendedStakeUsdc() != null && task.getRecommendedStakeUsdc().compareTo(BigDecimal.ZERO) > 0) {
            return task.getRecommendedStakeUsdc();
        }
        if (task.getTriggerSizeUsdc() != null && task.getTriggerSizeUsdc().compareTo(BigDecimal.ZERO) > 0) {
            return task.getTriggerSizeUsdc();
        }
        return wallet.getMinOrderUsdc();
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

    private BigDecimal estimateFee(BigDecimal notional, BigDecimal feeRate) {
        if (notional == null || feeRate == null) {
            return BigDecimal.ZERO;
        }
        return notional.multiply(feeRate).setScale(8, RoundingMode.HALF_UP);
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

    private LinkedHashMap<String, Object> baseDryRunResponse(ExecutionWalletContextLoader.ExecutionWalletContext wallet,
                                                             PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared,
                                                             PolymarketApiClient.MarketMetadata metadata,
                                                             Map<String, Object> priceReference) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("dryRun", true);
        response.put("walletReady", wallet.isLiveReady());
        response.put("simulationOnly", !StringUtils.hasText(prepared.getTokenId()));
        response.put("metadataNotFoundIsNormalInSimulation", !StringUtils.hasText(prepared.getTokenId()));
        response.put("preparedOrder", prepared);
        response.put("marketMetadata", metadata);
        response.put("priceReference", priceReference);
        response.put("note", StringUtils.hasText(prepared.getTokenId())
                ? "metadata and tokenId are resolved, so this dry-run can be promoted to a live order path."
                : "metadata not found or tokenId missing is normal in simulation; maintain the local market mapping until you need live execution.");
        return response;
    }

    private Map<String, Object> buildMarketSelection(String conditionId,
                                                     String question,
                                                     String outcome,
                                                     PolymarketApiClient.MarketMetadata metadata,
                                                     String tokenId) {
        LinkedHashMap<String, Object> marketSelection = new LinkedHashMap<>();
        marketSelection.put("conditionId", conditionId);
        marketSelection.put("question", question);
        marketSelection.put("outcome", outcome);
        marketSelection.put("category", metadata == null ? null : metadata.getCategory());
        marketSelection.put("tokenId", tokenId);
        marketSelection.put("tokenIdResolved", StringUtils.hasText(tokenId));
        marketSelection.put("metadataSource", StringUtils.hasText(tokenId) ? "REMOTE_OR_CACHED" : "LOCAL_OR_SYNTHETIC");
        marketSelection.put("outcomes", metadata == null ? List.of(outcome) : metadata.getOutcomes());
        return marketSelection;
    }

    private Map<String, Object> buildLocalPositionDraft(PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared,
                                                        BigDecimal entryPrice) {
        LinkedHashMap<String, Object> draft = new LinkedHashMap<>();
        draft.put("marketId", prepared.getMarketId());
        draft.put("question", prepared.getQuestion());
        draft.put("outcome", prepared.getOutcome());
        draft.put("side", prepared.getSide());
        draft.put("entryPrice", scale(entryPrice, 4));
        draft.put("limitPrice", scale(prepared.getLimitPrice(), 4));
        draft.put("stakeUsdc", scale(prepared.getStakeUsdc(), 2));
        draft.put("size", scale(prepared.getSize(), 6));
        draft.put("tickSize", scale(prepared.getTickSize(), 4));
        draft.put("tokenId", prepared.getTokenId());
        return draft;
    }

    private Map<String, Object> buildSettlementSimulation(PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared) {
        BigDecimal payoutIfWin = scale(prepared.getSize(), 8);
        BigDecimal payoutIfLose = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        BigDecimal stake = scale(prepared.getStakeUsdc(), 8);
        BigDecimal pnlIfWin = payoutIfWin.subtract(stake).setScale(8, RoundingMode.HALF_UP);
        BigDecimal pnlIfLose = payoutIfLose.subtract(stake).setScale(8, RoundingMode.HALF_UP);
        LinkedHashMap<String, Object> simulation = new LinkedHashMap<>();
        simulation.put("payoutIfWinUsdc", payoutIfWin);
        simulation.put("payoutIfLoseUsdc", payoutIfLose);
        simulation.put("pnlIfWinUsdc", pnlIfWin);
        simulation.put("pnlIfLoseUsdc", pnlIfLose);
        simulation.put("maxProfitUsdc", pnlIfWin.max(BigDecimal.ZERO));
        simulation.put("maxLossUsdc", pnlIfLose.abs());
        simulation.put("breakEvenWinProbability", scale(prepared.getLimitPrice(), 6));
        return simulation;
    }

    private Map<String, Object> buildPriceReference(String conditionId,
                                                    String outcome,
                                                    String tokenId,
                                                    BigDecimal fallbackPrice) {
        LinkedHashMap<String, Object> reference = new LinkedHashMap<>();
        reference.put("conditionId", conditionId);
        reference.put("outcome", outcome);
        reference.put("tokenId", tokenId);
        reference.put("requestedPrice", fallbackPrice);
        if (!StringUtils.hasText(tokenId)) {
            reference.put("referenceSource", fallbackPrice != null ? "REQUEST_PRICE" : "UNAVAILABLE");
            reference.put("referencePrice", fallbackPrice);
            reference.put("note", "No tokenId was available, so the simulation uses only the locally provided price.");
            return reference;
        }

        try {
            PolymarketMarketDataStreamService.MarketDataSnapshot wsSnapshot = marketDataStreamService.getSnapshot(tokenId);
            PolymarketApiClient.OrderBook orderBook;
            String source;
            if (wsSnapshot != null && wsSnapshot.getOrderBook() != null) {
                orderBook = wsSnapshot.getOrderBook();
                source = "WS_CACHE";
                reference.put("lastTradePrice", scale(wsSnapshot.getLastTradePrice(), 8));
            } else {
                orderBook = polymarketApiClient.fetchOrderBook(tokenId);
                source = "REST_BOOK";
                List<PolymarketApiClient.MarketTrade> trades = polymarketApiClient.fetchMarketTrades(conditionId, 20, null);
                if (!trades.isEmpty()) {
                    reference.put("lastTradePrice", scale(trades.get(0).getPrice(), 8));
                }
            }
            BigDecimal bestBid = topPrice(orderBook == null ? null : orderBook.getBids());
            BigDecimal bestAsk = topPrice(orderBook == null ? null : orderBook.getAsks());
            BigDecimal midpoint = safe(orderBook == null ? null : orderBook.getMidpoint());
            BigDecimal referencePrice = midpoint.compareTo(BigDecimal.ZERO) > 0
                    ? midpoint
                    : firstPositive(bestAsk, bestBid, fallbackPrice);
            reference.put("referenceSource", source);
            reference.put("bestBid", scale(bestBid, 8));
            reference.put("bestAsk", scale(bestAsk, 8));
            reference.put("midpoint", scale(midpoint, 8));
            reference.put("referencePrice", scale(referencePrice, 8));
            return reference;
        } catch (Exception e) {
            reference.put("referenceSource", fallbackPrice != null ? "REQUEST_PRICE_FALLBACK" : "ERROR");
            reference.put("referencePrice", fallbackPrice);
            reference.put("lookupError", e.getMessage());
            return reference;
        }
    }

    private BigDecimal resolveEntryPrice(BigDecimal requestedPrice, Map<String, Object> priceReference) {
        BigDecimal requested = safe(requestedPrice);
        if (requested.compareTo(BigDecimal.ZERO) > 0) {
            return requested;
        }
        Object referenced = priceReference == null ? null : priceReference.get("referencePrice");
        return referenced instanceof BigDecimal ? (BigDecimal) referenced : safe(referenced);
    }

    private String resolveTokenId(PolymarketApiClient.MarketMetadata metadata, String outcome) {
        if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
            return null;
        }
        return metadata.getOutcomeTokenIds().entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getKey()) && StringUtils.hasText(entry.getValue()))
                .filter(entry -> outcome.equalsIgnoreCase(entry.getKey().trim()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String normalizeOutcome(String outcome) {
        if (!StringUtils.hasText(outcome)) {
            return "YES";
        }
        String normalized = outcome.trim().toUpperCase();
        if (normalized.startsWith("NO")) {
            return "NO";
        }
        if (normalized.startsWith("YES")) {
            return "YES";
        }
        return normalized;
    }

    private BigDecimal topPrice(List<PolymarketApiClient.OrderBookLevel> levels) {
        if (levels == null || levels.isEmpty() || levels.get(0) == null) {
            return BigDecimal.ZERO;
        }
        return safe(levels.get(0).getPrice());
    }

    private BigDecimal firstPositive(BigDecimal... values) {
        if (values == null) {
            return BigDecimal.ZERO;
        }
        for (BigDecimal value : values) {
            if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
                return value;
            }
        }
        return BigDecimal.ZERO;
    }

    private record ExitPriceReference(BigDecimal price, String source) {
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return safe(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
