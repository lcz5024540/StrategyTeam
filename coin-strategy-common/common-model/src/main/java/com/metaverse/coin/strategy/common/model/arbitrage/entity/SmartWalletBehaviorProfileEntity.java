package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("smart_wallet_behavior_profile")
public class SmartWalletBehaviorProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String source;
    private String alias;
    private String archetype;
    private String followLane;
    private Boolean highFrequencyTradingAddress;
    @TableField("trades_7d")
    private Integer trades7d;
    @TableField("trades_30d")
    private Integer trades30d;
    private BigDecimal medianInterTradeMinutes;
    private Integer closedPositionSampleSize;
    private BigDecimal orderWinRate;
    private BigDecimal averageOrderRoiRate;
    private BigDecimal medianOrderRoiRate;
    private BigDecimal totalRealizedPnlUsdc;
    private BigDecimal settlementCompletionRate;
    private BigDecimal earlyExitRate;
    private BigDecimal profitEarlyExitRate;
    private BigDecimal lossEarlyExitRate;
    private BigDecimal earlySettlementRate;
    private String winSourceType;
    private BigDecimal latencyAlphaRate;
    private BigDecimal swingAlphaRate;
    private BigDecimal settlementWinRate;
    private BigDecimal medianHoldMinutes;
    private BigDecimal stabilityScore;
    private BigDecimal qualityScore;
    private BigDecimal replicabilityScore;
    private String recommendedExitLane;
    private String summary;

    /**
     * 方案 B (W2-T6 优化): LeaderBehaviorDrift 共享的特征向量 centroid JSON.
     * 格式: {"centroid":[0.13,1.0,0.4,0.3,0.0,0.0,0.3], "sampleCount":42}
     * 由 BehaviorProfileSnapshotJob 写入, LeaderBehaviorDriftService 优先读取.
     * NULL 表示旧数据 / 未启用方案 B, drift service 自动 fallback 现有 trade_event 聚合算法.
     * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十五节.
     */
    private String behaviorVectorJson;

    /** 方案 B: vector centroid 最近一次计算时间; 陈旧时 fallback 现有算法. */
    private Date behaviorVectorComputedAt;

    private Date computedAt;
    private Date createdAt;
    private Date updatedAt;
}
