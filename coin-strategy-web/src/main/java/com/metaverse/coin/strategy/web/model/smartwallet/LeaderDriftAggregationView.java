package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 第十二批 第五波 web 接口: LeaderDrift 聚合报表 web 视图.
 * 对应 job 端 LeaderDriftAggregationService.LeaderDriftAggregationReport.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十九节.
 */
@Data
@Builder
public class LeaderDriftAggregationView {

    private Date generatedAt;
    private int lookbackHours;
    private int activeLeaders;
    private int evaluated;
    private int activeOkCount;
    private int driftedCount;
    private int driftedSevereCount;
    private int coldStartCount;
    private int skippedCount;
    private boolean truncated;
    private List<DriftedLeader> topDrifted;

    @Data
    @Builder
    public static class DriftedLeader {
        private String leaderAddress;
        private String status;                  // DRIFTED / DRIFTED_SEVERE
        private BigDecimal cosineDistance;
        private int sampleCount;
        private Date latestEventAt;
        private String reason;
    }
}
