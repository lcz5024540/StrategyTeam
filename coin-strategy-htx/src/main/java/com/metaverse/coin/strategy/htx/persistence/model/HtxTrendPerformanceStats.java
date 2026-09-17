package com.metaverse.coin.strategy.htx.persistence.model;

import lombok.Data;

import java.math.BigDecimal;

/** Closed-position quality snapshot used to size HTX trend entries without hard-pausing them. */
@Data
public class HtxTrendPerformanceStats {
    private Long closedCount;
    private Long winCount;
    private Long lossCount;
    private BigDecimal grossProfit;
    private BigDecimal grossLoss;
    private BigDecimal netProfit;

    public static HtxTrendPerformanceStats empty() {
        HtxTrendPerformanceStats stats = new HtxTrendPerformanceStats();
        stats.setClosedCount(0L);
        stats.setWinCount(0L);
        stats.setLossCount(0L);
        stats.setGrossProfit(BigDecimal.ZERO);
        stats.setGrossLoss(BigDecimal.ZERO);
        stats.setNetProfit(BigDecimal.ZERO);
        return stats;
    }
}
