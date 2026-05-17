package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 第十五批 #1: Shadow estimator source 分桶对比视图 (web).
 *
 * <p>对应 ShadowVsLiveWeeklyReportService.ShadowEstimatorSourceStats, 让 VWAP vs PROXY
 * 实证数据不必等周一周报, 任意 lookback 即查即用.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十二节.</p>
 */
@Data
@Builder
public class ShadowEstimatorSourceComparisonView {

    private Date generatedAt;
    private Date windowStart;
    private Date windowEnd;
    private int lookbackDays;
    private int totalSettledShadows;
    private List<SourceBucket> buckets;
    /** 简短结论字符串 — 例如 "VWAP rejected worse: -23.4 vs PROXY +18.6", null 表示样本不足. */
    private String verdict;

    @Data
    @Builder
    public static class SourceBucket {
        /** "VWAP(OK)" / "VWAP(DRY_FALLBACK)" / "PROXY" / "UNKNOWN" */
        private String source;
        private int count;
        private int winCount;
        private BigDecimal winRate;
        /** 影子记账 PnL (若放行) — 越负, 说明门控拒得越对. */
        private BigDecimal shadowPnlUsdc;
        private BigDecimal avgEntrySlippageBps;
    }
}
