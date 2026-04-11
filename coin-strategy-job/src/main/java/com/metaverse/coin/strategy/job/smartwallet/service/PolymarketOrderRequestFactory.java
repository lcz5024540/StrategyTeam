package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PolymarketOrderRequestFactory {

    private final PolymarketMarketMetadataService marketMetadataService;

    public PreparedPolymarketOrder prepare(SmartWalletSignal signal,
                                           ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        String outcome = signal.getFollowOutcome() == null ? "YES" : signal.getFollowOutcome().trim().toUpperCase();
        if ("YES".equals(outcome) && !wallet.isAllowYes()) {
            throw new IllegalStateException("Execution wallet is configured to block YES orders.");
        }
        if ("NO".equals(outcome) && !wallet.isAllowNo()) {
            throw new IllegalStateException("Execution wallet is configured to block NO orders.");
        }

        String conditionId = signal.getMarket() == null ? null : signal.getMarket().getMarketId();
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(conditionId);
        String tokenId = resolveTokenId(metadata, outcome);
        BigDecimal minimumTickSize = metadata.getMinimumTickSize() == null || metadata.getMinimumTickSize().compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.01")
                : metadata.getMinimumTickSize();
        return prepareLocalDraft(
                signal.getSignalId(),
                conditionId,
                metadata.getQuestion() == null && signal.getMarket() != null ? signal.getMarket().getQuestion() : metadata.getQuestion(),
                outcome,
                signal.getRecommendedStakeUsdc(),
                signal.getMarket() == null ? null : signal.getMarket().getPrice(),
                tokenId,
                minimumTickSize,
                metadata.isNegRisk(),
                wallet
        );
    }

    public PreparedPolymarketOrder prepareLocalDraft(String signalId,
                                                     String conditionId,
                                                     String question,
                                                     String outcome,
                                                     BigDecimal requestedStakeUsdc,
                                                     BigDecimal requestedPrice,
                                                     String tokenId,
                                                     BigDecimal tickSize,
                                                     boolean negRisk,
                                                     ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        if ("YES".equalsIgnoreCase(outcome) && !wallet.isAllowYes()) {
            throw new IllegalStateException("Execution wallet is configured to block YES orders.");
        }
        if ("NO".equalsIgnoreCase(outcome) && !wallet.isAllowNo()) {
            throw new IllegalStateException("Execution wallet is configured to block NO orders.");
        }
        BigDecimal recommendedStake = requestedStakeUsdc == null ? wallet.getMinOrderUsdc() : requestedStakeUsdc;
        BigDecimal cappedStake = recommendedStake
                .max(wallet.getMinOrderUsdc())
                .min(wallet.getMaxOrderUsdc())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal rawPrice = requestedPrice == null ? BigDecimal.ZERO : requestedPrice;
        BigDecimal slippage = rawPrice.multiply(wallet.getPriceSlippageRate());
        BigDecimal limitPrice = rawPrice.add(slippage).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        BigDecimal minimumTickSize = tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.01")
                : tickSize;
        BigDecimal normalizedLimitPrice = snapToTick(limitPrice, minimumTickSize);
        BigDecimal size = normalizedLimitPrice.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : cappedStake.divide(normalizedLimitPrice, 6, RoundingMode.DOWN);

        return PreparedPolymarketOrder.builder()
                .signalId(signalId)
                .marketId(conditionId)
                .question(question)
                .outcome(outcome)
                .side("BUY")
                .stakeUsdc(cappedStake)
                .limitPrice(normalizedLimitPrice)
                .size(size)
                .tokenId(StringUtils.hasText(tokenId) ? tokenId.trim() : null)
                .tickSize(minimumTickSize)
                .negRisk(negRisk)
                .executionAddress(wallet.getAddress())
                .funderAddress(wallet.getFunderAddress())
                .build();
    }

    private String resolveTokenId(PolymarketApiClient.MarketMetadata metadata, String outcome) {
        if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
            throw new IllegalStateException("Polymarket market metadata is missing outcome token ids.");
        }
        String tokenId = findTokenId(metadata.getOutcomeTokenIds(), outcome);
        if (!StringUtils.hasText(tokenId)) {
            String supportedOutcomes = metadata.getOutcomeTokenIds().keySet().stream()
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("Polymarket token id not found for outcome " + outcome
                    + " on market " + metadata.getConditionId()
                    + ". Supported outcomes: [" + supportedOutcomes + "].");
        }
        return tokenId.trim();
    }

    private String findTokenId(Map<String, String> outcomeTokenIds, String outcome) {
        if (!StringUtils.hasText(outcome) || outcomeTokenIds == null || outcomeTokenIds.isEmpty()) {
            return null;
        }
        String exact = outcomeTokenIds.get(outcome);
        if (StringUtils.hasText(exact)) {
            return exact;
        }
        String normalized = outcome.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : outcomeTokenIds.entrySet()) {
            if (entry.getKey() != null && normalized.equals(entry.getKey().trim().toUpperCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private BigDecimal snapToTick(BigDecimal price, BigDecimal tickSize) {
        if (price == null || tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return price;
        }
        return price.divide(tickSize, 0, RoundingMode.HALF_UP)
                .multiply(tickSize)
                .min(BigDecimal.ONE)
                .max(BigDecimal.ZERO)
                .setScale(Math.max(tickSize.scale(), 4), RoundingMode.HALF_UP);
    }

    @Data
    @Builder
    public static class PreparedPolymarketOrder {
        private String signalId;
        private String marketId;
        private String question;
        private String outcome;
        private String side;
        private BigDecimal stakeUsdc;
        private BigDecimal limitPrice;
        private BigDecimal size;
        private String tokenId;
        private BigDecimal tickSize;
        private boolean negRisk;
        private String executionAddress;
        private String funderAddress;
    }
}
