package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class PolymarketOrderRequestFactory {

    private final PolymarketMarketMetadataService marketMetadataService;

    public PreparedPolymarketOrder prepare(SmartWalletSignal signal,
                                           ExecutionWalletContextLoader.ExecutionWalletContext wallet) {
        BigDecimal cappedStake = signal.getRecommendedStakeUsdc()
                .max(wallet.getMinOrderUsdc())
                .min(wallet.getMaxOrderUsdc())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal rawPrice = signal.getMarket() == null || signal.getMarket().getPrice() == null
                ? BigDecimal.ZERO
                : signal.getMarket().getPrice();
        BigDecimal slippage = rawPrice.multiply(wallet.getPriceSlippageRate());
        BigDecimal limitPrice = rawPrice.add(slippage).min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);

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
        BigDecimal normalizedLimitPrice = snapToTick(limitPrice, minimumTickSize);
        BigDecimal size = normalizedLimitPrice.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : cappedStake.divide(normalizedLimitPrice, 6, RoundingMode.DOWN);

        return PreparedPolymarketOrder.builder()
                .signalId(signal.getSignalId())
                .marketId(conditionId)
                .question(metadata.getQuestion() == null && signal.getMarket() != null ? signal.getMarket().getQuestion() : metadata.getQuestion())
                .outcome(outcome)
                .side("BUY")
                .stakeUsdc(cappedStake)
                .limitPrice(normalizedLimitPrice)
                .size(size)
                .tokenId(tokenId)
                .tickSize(minimumTickSize)
                .negRisk(metadata.isNegRisk())
                .executionAddress(wallet.getAddress())
                .funderAddress(wallet.getFunderAddress())
                .build();
    }

    private String resolveTokenId(PolymarketApiClient.MarketMetadata metadata, String outcome) {
        if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
            throw new IllegalStateException("Polymarket market metadata is missing outcome token ids.");
        }
        String tokenId = metadata.getOutcomeTokenIds().get(outcome);
        if (!StringUtils.hasText(tokenId)) {
            throw new IllegalStateException("Polymarket token id not found for outcome " + outcome + " on market " + metadata.getConditionId() + ".");
        }
        return tokenId.trim();
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
