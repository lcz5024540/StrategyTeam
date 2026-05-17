package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * W2-T2 灰度自动化 (第十八批): leader 滑动窗口胜率日级聚合历史.
 *
 * <p>由 {@code LeaderRollingMonitorJob} 每日 09:30 (Asia/Shanghai) upsert 一行,
 * 让 W2-T2 灰度数据从"人工每日 SOP 查询"升级为"自动 trend 历史 + 异常告警"。</p>
 *
 * <p>主键 (as_of_date, lookback_days) 唯一. 同一天同一 lookback 仅一行; 不同 lookback
 * (e.g. 7d / 14d) 各占一行支持多维度对比.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-17.md 第三节.</p>
 */
@Data
@Accessors(chain = true)
@TableName("leader_rolling_aggregation_daily")
public class LeaderRollingAggregationDailyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate asOfDate;
    private Integer lookbackDays;
    private Integer windowSize;
    private Integer minSamples;
    private BigDecimal minWinRate;

    private Integer activeLeaders;
    private Integer evaluatedCount;
    private Integer activeCount;
    private Integer coolingCount;
    private Integer coldCount;
    private BigDecimal coolingRatio;
    private BigDecimal overallWinRate;

    /** OK / WARN_HIGH_COOLING / WARN_LOW_WIN_RATE / WARN_BOTH. */
    private String healthStatus;

    /** top-N 低胜率 leader 简短 JSON. */
    private String topCoolingLeadersJson;

    private Date computedAt;
    private Date createdAt;
    private Date updatedAt;
}
