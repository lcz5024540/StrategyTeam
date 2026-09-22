package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Append-only trend ensemble candidate signal, generated before any live-order decision. */
@Data
@TableName("htx_trend_candidate_signal")
public class HtxTrendCandidateSignalEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String contractCode;
    private String period;
    private Long barTime;
    private String direction;
    private String regime;
    private BigDecimal signalScore;
    private BigDecimal longScore;
    private BigDecimal shortScore;
    private BigDecimal scoreGap;
    private BigDecimal closePrice;
    private BigDecimal emaFast;
    private BigDecimal emaMedium;
    private BigDecimal emaSlow;
    private BigDecimal breakoutHigh;
    private BigDecimal breakoutLow;
    private BigDecimal atr;
    private BigDecimal atrPct;
    private BigDecimal adx;
    private BigDecimal momentumPct;
    private BigDecimal volumeRatio;
    private String entryReason;
    private String invalidationReason;
    private Boolean shadow;
    private String modelVersion;
    private String primaryModel;
    private String confirmationModels;
    private String policyVersion;
    /** Deterministic scan identifier joining this candidate to its execution attempt and resulting order. */
    private String runId;
    private Long factorSnapshotId;
    private String gateResult;
    private String gateReason;
    private String executionGateResult;
    private String executionGateReason;
    private LocalDateTime executionEvaluatedAt;
    private BigDecimal invalidationPrice;
    private String entryHypothesis;
    private BigDecimal sizingMultiplier;
    private BigDecimal plannedNotionalUsdt;
    private BigDecimal targetZoneLow;
    private BigDecimal targetZoneHigh;
    private BigDecimal boxMeasuredMoveTarget;
    @TableField("fib_extension_1272")
    private BigDecimal fibExtension1272;
    @TableField("fib_extension_1618")
    private BigDecimal fibExtension1618;
    private BigDecimal expectedRMultiple;
    private BigDecimal momentumQualityScore;
    private LocalDateTime createdAt;
}
