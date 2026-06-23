package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

public record SmartWalletTradeView(
        Long tradeId,
        String executionAddress,
        String marketId,
        String marketSlug,
        String tokenId,
        String question,
        String outcome,
        String status,
        String netSide,
        BigDecimal entryQuantity,
        BigDecimal entryAvgPrice,
        BigDecimal entryCostUsdc,
        BigDecimal exitQuantity,
        BigDecimal exitAvgPrice,
        BigDecimal exitProceedsUsdc,
        BigDecimal remainingQuantity,
        BigDecimal realizedPnlUsdc,
        BigDecimal unrealizedPnlUsdc,
        BigDecimal totalPnlUsdc,
        BigDecimal roiRate,
        BigDecimal roiBaseUsdc,
        String roiAnomalyReason,
        BigDecimal lastMarkPrice,
        String exitReason,
        Integer entryOrderCount,
        Integer exitOrderCount,
        List<Long> entryOrderIds,
        List<Long> exitOrderIds,
        Long marketEndAt,
        String marketEndAtBjt,
        Date openedAt,
        Date lastFilledAt,
        Date closedAt
) {
}
