package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * W2-T2 Leader 滑动窗口胜率 web 视图.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Data
@Builder
public class LeaderRollingView {

    private String leaderAddress;
    private String status;                  // ACTIVE / COOLING / SUSPENDED / COLD / DISABLED
    private int windowSize;
    private int sampleCount;
    private int minSamples;
    private int wins;
    private int losses;
    private BigDecimal winRate;
    private BigDecimal totalPnlUsdc;
    private BigDecimal avgPnlUsdc;
    private Date earliestClosedAt;
    private Date latestClosedAt;
    private String reason;
    private List<RecentClose> recentCloses;

    @Data
    @Builder
    public static class RecentClose {
        private Long positionId;
        private Long followTaskId;
        private String marketId;
        private String outcome;
        private BigDecimal realizedPnlUsdc;
        private boolean win;
        private Date closedAt;
    }
}
