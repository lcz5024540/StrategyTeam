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
 * W2-T1.2 市场流动性 + 价差分位日级快照。
 *
 * <p>每日 02:30 (Asia/Shanghai) 由 {@code MarketLiquidityPercentileSnapshotJob} 写入,
 * 按 {@code (category, asOfDate)} 唯一索引去重。{@code MarketLiquidityPercentileService}
 * 优先读本表的最近一行 (P30 滚动窗口), 缺数据 / 服务关闭时回退到现有内存即时聚合.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十六节.</p>
 */
@Data
@Accessors(chain = true)
@TableName("pm_market_liquidity_percentile_daily")
public class PmMarketLiquidityPercentileDailyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String category;
    private LocalDate asOfDate;
    private Integer sampleCount;

    private BigDecimal liquidityP10;
    private BigDecimal liquidityP30;
    private BigDecimal liquidityP50;
    private BigDecimal liquidityP70;
    private BigDecimal liquidityMean;
    private BigDecimal liquidityMax;

    /** W2-T1.4 后续 sprint 写入: 该品类价差 bps P70 (高分位 → 容忍上限). */
    private BigDecimal spreadBpsP70;

    private Date computedAt;
    private Date createdAt;
    private Date updatedAt;
}
