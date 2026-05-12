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
}
