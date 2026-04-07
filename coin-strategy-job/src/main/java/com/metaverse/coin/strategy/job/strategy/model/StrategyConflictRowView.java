package com.metaverse.coin.strategy.job.strategy.model;

import java.math.BigDecimal;
import java.util.List;

public record StrategyConflictRowView(
        String marketId,
        String question,
        List<String> outcomes,
        Integer openPositionCount,
        BigDecimal netPnlUsdc,
        String marketEndAtBjt
) {
}
