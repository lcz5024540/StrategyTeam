package com.metaverse.coin.strategy.htx.persistence.model;

import lombok.Data;

/** Latest cross-symbol trend breadth snapshot derived from persisted HTX trend signals. */
@Data
public class HtxTrendBreadthStats {
    private Long totalCount;
    private Long longContextCount;
    private Long shortContextCount;
    private Long longSignalCount;
    private Long shortSignalCount;

    public static HtxTrendBreadthStats empty() {
        HtxTrendBreadthStats stats = new HtxTrendBreadthStats();
        stats.setTotalCount(0L);
        stats.setLongContextCount(0L);
        stats.setShortContextCount(0L);
        stats.setLongSignalCount(0L);
        stats.setShortSignalCount(0L);
        return stats;
    }
}
