package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class PolymarketOrderRequestFactory {

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

        return PreparedPolymarketOrder.builder()
                .signalId(signal.getSignalId())
                .marketId(signal.getMarket() == null ? null : signal.getMarket().getMarketId())
                .question(signal.getMarket() == null ? null : signal.getMarket().getQuestion())
                .outcome(outcome)
                .side("BUY")
                .stakeUsdc(cappedStake)
                .limitPrice(limitPrice)
                .executionAddress(wallet.getAddress())
                .funderAddress(wallet.getFunderAddress())
                .build();
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
        private String executionAddress;
        private String funderAddress;
    }
}
