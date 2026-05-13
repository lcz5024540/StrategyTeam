package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletPositionView(
        Long id,
        String executionAddress,
        String marketId,
        String tokenId,
        String question,
        String outcome,
        Long marketEndAt,
        String marketEndAtBjt,
        String status,
        BigDecimal quantity,
        BigDecimal averageEntryPrice,
        BigDecimal costBasisUsdc,
        BigDecimal originalCostBasisUsdc,
        BigDecimal feePolTotal,
        BigDecimal realizedPnlUsdc,
        BigDecimal unrealizedPnlUsdc,
        BigDecimal pendingSettlementNetPnlUsdc,
        BigDecimal claimableProceedsUsdc,
        BigDecimal lastMarkPrice,
        String netSide,
        Date openedAt,
        Date lastFilledAt,
        Date closedAt
) {

    public Long getId() { return id; }
    public String getMarketId() { return marketId; }
    public String getQuestion() { return question; }
    public String getOutcome() { return outcome; }
    public String getStatus() { return status; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getAverageEntryPrice() { return averageEntryPrice; }
    public BigDecimal getCostBasisUsdc() { return costBasisUsdc; }
    public BigDecimal getOriginalCostBasisUsdc() { return originalCostBasisUsdc; }
    public BigDecimal getRealizedPnlUsdc() { return realizedPnlUsdc; }
    public BigDecimal getUnrealizedPnlUsdc() { return unrealizedPnlUsdc; }
    public BigDecimal getPendingSettlementNetPnlUsdc() { return pendingSettlementNetPnlUsdc; }
    public BigDecimal getClaimableProceedsUsdc() { return claimableProceedsUsdc; }
    public String getMarketEndAtBjt() { return marketEndAtBjt; }
}
