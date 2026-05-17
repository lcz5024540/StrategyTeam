package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 第十六批 #1: W2-T2 Leader 滑动窗口胜率门灰度观察视图.
 *
 * <p>聚合最近 N 天每个活跃 leader 的滑动窗口胜率, 输出 ACTIVE/COOLING/COLD 分布
 * + top-N 低胜率 leader, 让运维评估 W2-T2 灰度效果.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第二十四节.</p>
 */
@Data
@Builder
public class LeaderRollingAggregationView {

    private Date generatedAt;
    private int lookbackDays;
    private int activeLeaders;
    private int evaluated;
    /** ACTIVE: 样本 >= minSamples 且 winRate >= minWinRate */
    private int activeCount;
    /** COOLING: 样本 >= minSamples 但 winRate < minWinRate */
    private int coolingCount;
    /** COLD: 样本 < minSamples */
    private int coldCount;
    /** 全局平均 winRate (仅基于已关闭仓位计算; 无样本时 null) */
    private BigDecimal overallWinRate;
    /** 触发线 (与代码默认或灰度配置对齐, 仅用于前端展示) */
    private int minSamples;
    private BigDecimal minWinRate;
    private int windowSize;
    /** top-N 低胜率 leader, 帮助运维点击进 /leader-rolling/{wallet} 看详情. */
    private List<LowWinRateLeader> topCoolingLeaders;

    @Data
    @Builder
    public static class LowWinRateLeader {
        private String leaderAddress;
        private int sampleCount;
        private int wins;
        private int losses;
        private BigDecimal winRate;
        private BigDecimal totalPnlUsdc;
        private Date latestClosedAt;
        private String status;     // ACTIVE / COOLING / COLD
    }
}
