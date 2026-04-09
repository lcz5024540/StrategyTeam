package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletConflictMarketView;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Getter
class SmartWalletConflictMarketAccumulator {

    private final String marketId;
    private final String question;
    private final Set<String> outcomes = new LinkedHashSet<>();
    private int positionCount;
    private int openPositionCount;
    private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
    private BigDecimal unrealizedPnlUsdc = BigDecimal.ZERO;
    private Date latestClosedAt;

    SmartWalletConflictMarketAccumulator(String marketId, String question) {
        this.marketId = marketId;
        this.question = question;
    }

    void add(SmartWalletPositionEntity position, BigDecimal realizedPnl) {
        positionCount++;
        if (position.getOutcome() != null && !position.getOutcome().isBlank()) {
            outcomes.add(position.getOutcome());
        }
        if ("OPEN".equalsIgnoreCase(position.getStatus()) || "PARTIALLY_CLOSED".equalsIgnoreCase(position.getStatus())) {
            openPositionCount++;
        }
        realizedPnlUsdc = realizedPnlUsdc.add(safe(realizedPnl));
        unrealizedPnlUsdc = unrealizedPnlUsdc.add(safe(position.getUnrealizedPnlUsdc()));
        if (position.getClosedAt() != null && (latestClosedAt == null || position.getClosedAt().after(latestClosedAt))) {
            latestClosedAt = position.getClosedAt();
        }
    }

    SmartWalletConflictMarketView toView(Long marketEndAt, String marketEndAtBjt) {
        List<String> sortedOutcomes = new ArrayList<>(outcomes);
        sortedOutcomes.sort(Comparator.naturalOrder());
        return SmartWalletConflictMarketView.builder()
                .marketId(marketId)
                .question(question)
                .outcomeCount(outcomes.size())
                .positionCount(positionCount)
                .openPositionCount(openPositionCount)
                .outcomes(sortedOutcomes)
                .realizedPnlUsdc(realizedPnlUsdc)
                .unrealizedPnlUsdc(unrealizedPnlUsdc)
                .netPnlUsdc(realizedPnlUsdc.add(unrealizedPnlUsdc))
                .marketEndAt(marketEndAt)
                .marketEndAtBjt(marketEndAtBjt)
                .latestClosedAt(latestClosedAt)
                .build();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
