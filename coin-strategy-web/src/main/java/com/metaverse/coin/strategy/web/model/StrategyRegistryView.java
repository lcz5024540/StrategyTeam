package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;
import java.util.List;

public record StrategyRegistryView(
        String code,
        String name,
        String category,
        String status,
        String description,
        List<String> supportedModes,
        List<String> capabilities,
        String modeBehavior,
        Integer openPositions,
        BigDecimal netPnlUsdc,
        BigDecimal winRate,
        Integer totalOrders
) {
}
