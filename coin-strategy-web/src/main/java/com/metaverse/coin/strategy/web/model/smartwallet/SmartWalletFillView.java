package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletFillView(
        Long id,
        Long orderId,
        String executionAddress,
        String marketId,
        String tokenId,
        String question,
        String outcome,
        String side,
        BigDecimal price,
        BigDecimal size,
        BigDecimal notionalUsdc,
        BigDecimal feeUsdc,
        BigDecimal tradingFeeUsdc,
        BigDecimal feePol,
        BigDecimal gasFeeUsdc,
        BigDecimal realizedPnlUsdc,
        Date filledAt
) {
}
