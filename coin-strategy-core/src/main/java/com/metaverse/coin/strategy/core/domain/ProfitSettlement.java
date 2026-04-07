package com.metaverse.coin.strategy.core.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record ProfitSettlement(
        String settlementId,
        String userId,
        String strategyCode,
        String settlementBatchId,
        BigDecimal realizedPnl,
        BigDecimal feeBasePnl,
        BigDecimal hwmBefore,
        BigDecimal hwmAfter,
        BigDecimal shareRate,
        BigDecimal shareAmount,
        String status,
        Instant settledAt
) {
}
