package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * 第十九批 #5: 统一 copy-trading-health 端点视图.
 *
 * <p>把第十八批 W2-T2 LeaderRolling trend + LeaderDriftAggregation 实时聚合 + LiveReplayDiff 最新一日
 * 整合为单一面板视图, 替代 "三个端点 curl 三次" 的运维 SOP.</p>
 *
 * <p>设计原则:</p>
 * <ul>
 *   <li><b>不重复算法</b>: 由 read service 调既有 3 个 read service 拼装, 不直查 DB.</li>
 *   <li><b>降级容错</b>: 任一子源失败 → 对应字段为 null + 在 errors 中记录原因, 不阻塞另外两源.</li>
 *   <li><b>overall 健康</b>: 由三个子状态汇总 (任一 WARN → overall WARN; 全部 OK 或缺失 → overall OK).</li>
 * </ul>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-17.md 第九节.</p>
 */
@Data
@Builder
public class CopyTradingHealthView {

    private Date generatedAt;

    /** OK / WARN: 三个子源任一为 WARN_* 时本字段为 WARN. */
    private String overallStatus;

    /** 错误降级清单 (子源调用异常时累加). */
    private List<String> errors;

    /** W2-T2 LeaderRolling 灰度健康概览 (来自 leader_rolling_aggregation_daily 最新一行). */
    private LeaderRollingSummary leaderRolling;

    /** W2-T6 LeaderDrift 实时聚合概览 (最近 N 小时活跃 leader). */
    private LeaderDriftSummary leaderDrift;

    /** W1-T1 Live↔Replay diff 最新一日概览. */
    private LiveReplayDiffSummary liveReplayDiff;

    @Data
    @Builder
    public static class LeaderRollingSummary {
        /** 最新一日 healthStatus (OK / WARN_HIGH_COOLING / WARN_LOW_WIN_RATE / WARN_BOTH). */
        private String latestHealthStatus;
        private LocalDate latestAsOfDate;
        /** 最近 historyDays 内 WARN_* 状态出现次数, 用于 ops 判定是否连续多日异常. */
        private int recentWarnDays;
        private int historyDays;
        private BigDecimal latestCoolingRatio;
        private BigDecimal latestOverallWinRate;
        private Integer latestEvaluatedCount;
        private Integer latestActiveCount;
        private Integer latestCoolingCount;
    }

    @Data
    @Builder
    public static class LeaderDriftSummary {
        /** OK / WARN: drifted+severe / evaluated 比例 >= 0.15 OR severe >= 3 时 WARN. */
        private String status;
        private int lookbackHours;
        private int activeLeaders;
        private int evaluated;
        private int driftedCount;
        private int driftedSevereCount;
        private int coldStartCount;
        private BigDecimal driftedRatio;
        private boolean truncated;
    }

    @Data
    @Builder
    public static class LiveReplayDiffSummary {
        /** OK / WARN: pnlDiffExceeded || signalMissExceeded 时 WARN. */
        private String status;
        private LocalDate asOfDate;
        private BigDecimal pnlDiffUsdc;
        private BigDecimal pnlDiffBps;
        private BigDecimal signalMissPct;
        private Boolean pnlDiffExceeded;
        private Boolean signalMissExceeded;
    }
}
