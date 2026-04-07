package com.metaverse.coin.strategy.job.strategy.model;

import java.math.BigDecimal;

public record StrategyTimelinePointView(
        String bucketAt,
        BigDecimal netPnlUsdc,
        BigDecimal cumulativeNetPnlUsdc
) {
}
