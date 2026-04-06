package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;

public record StrategyTimelinePointView(
        String bucketAt,
        BigDecimal netPnlUsdc,
        BigDecimal cumulativeNetPnlUsdc
) {
}
