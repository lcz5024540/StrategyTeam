package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * 第十八批 W2-T2 灰度自动化: leader 滑动窗口胜率灰度日级 trend 视图.
 *
 * <p>由 {@code LeaderRollingMonitorJob} 每日 09:30 (Asia/Shanghai) upsert 写入
 * {@code leader_rolling_aggregation_daily}, 本视图把多日聚合按 asOfDate desc 串成
 * 一张趋势表, 让 ops 看 coolingRatio / overallWinRate 是否稳定、是否触发健康告警.</p>
 *
 * <p>详见 docs/polymarket/audit-observability/w2-t2-grayscale-sop.md 与
 * docs/polymarket/copy-trading-improvement-decision-2026-05-17.md 第三节.</p>
 */
@Data
@Builder
public class LeaderRollingAggregationHistoryView {

    private Date generatedAt;
    /** 当前请求约束: lookbackDays 过滤值 (null 表示全部档位); 行级各自带自己的 lookbackDays. */
    private Integer requestedLookbackDays;
    /** 当前请求约束: 历史回看天数 (asOfDate >= today - days). */
    private int historyDays;
    /** 已落表行数. */
    private int rowCount;
    /** 最近一次健康状态 (用于前端 banner 概览, null 表示无数据). */
    private String latestHealthStatus;
    private LocalDate latestAsOfDate;
    /** 行集合, 按 asOfDate desc, 再按 lookbackDays asc. */
    private List<Row> rows;

    @Data
    @Builder
    public static class Row {
        private LocalDate asOfDate;
        private int lookbackDays;
        private int windowSize;
        private int minSamples;
        private BigDecimal minWinRate;
        private int activeLeaders;
        private int evaluatedCount;
        private int activeCount;
        private int coolingCount;
        private int coldCount;
        private BigDecimal coolingRatio;
        private BigDecimal overallWinRate;
        private String healthStatus;
        /** 序列化好的 top-N 低胜率 leader JSON; 前端可直接 JSON.parse 渲染. */
        private String topCoolingLeadersJson;
        private Date computedAt;
    }
}
