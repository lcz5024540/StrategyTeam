package com.metaverse.coin.strategy.job.strategy.model;

import java.math.BigDecimal;

public record StrategyAttributionRowView(
        String key,
        String label,
        String address,
        String question,
        BigDecimal netPnlUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal pendingSettlementPnlUsdc,
        BigDecimal totalVolumeUsdc,
        Integer tradeCount,
        BigDecimal winRate
) {
}
