package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public record SmartWalletConflictMarketView(
        String marketId,
        String question,
        Integer outcomeCount,
        Integer positionCount,
        Integer openPositionCount,
        List<String> outcomes,
        BigDecimal realizedPnlUsdc,
        BigDecimal unrealizedPnlUsdc,
        BigDecimal netPnlUsdc,
        Long marketEndAt,
        String marketEndAtBjt,
        Date latestClosedAt
) {

    public String getMarketId() { return marketId; }
    public String getQuestion() { return question; }
    public List<String> getOutcomes() { return outcomes; }
    public Integer getOpenPositionCount() { return openPositionCount; }
    public BigDecimal getNetPnlUsdc() { return netPnlUsdc; }
    public String getMarketEndAtBjt() { return marketEndAtBjt; }
}
