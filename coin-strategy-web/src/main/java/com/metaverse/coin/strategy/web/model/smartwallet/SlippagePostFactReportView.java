package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * W2-T4.5 滑点事后统计报表 web 视图.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Data
@Builder
public class SlippagePostFactReportView {

    private Date generatedAt;
    private Date windowStart;
    private Date windowEnd;
    private int lookbackDays;
    private int totalOrdersScanned;
    private boolean ordersTruncated;
    private int unmatchedTraceCount;
    private int zeroLeaderPriceCount;
    private int matchedOrders;
    private List<CategoryStats> categoryStats;
    private CategoryStats overall;
    private BigDecimal warnP95Bps;

    @Data
    @Builder
    public static class CategoryStats {
        private String category;
        private int count;
        private BigDecimal meanBps;
        private BigDecimal medianBps;
        private BigDecimal absP75Bps;
        private BigDecimal absP95Bps;
        private boolean p95Exceeded;
    }
}
