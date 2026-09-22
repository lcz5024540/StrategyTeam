package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Durable state for one managed HTX contract trend-following position. */
@Data
@TableName("htx_trend_position")
public class HtxTrendPositionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String positionKey;
    private String runId;
    private String contractCode;
    private String status;
    private String side;
    private BigDecimal contractSize;
    private Long contractVolume;
    private Integer leverage;
    private String entryClientOrderId;
    private Long entryOrderId;
    private BigDecimal entryFilledContracts;
    private BigDecimal entryAvgPrice;
    private BigDecimal entryFee;
    private String closeClientOrderId;
    private Long closeOrderId;
    private BigDecimal closeFilledContracts;
    private BigDecimal closeAvgPrice;
    private BigDecimal closeFee;
    private BigDecimal realizedProfit;
    private BigDecimal stopPrice;
    private BigDecimal trailingStopPrice;
    private BigDecimal takeProfitPrice;
    private BigDecimal highestPrice;
    private BigDecimal lowestPrice;
    private String entrySignal;
    private String exitReason;
    private String message;
    private String accountLabel;
    private BigDecimal entryFeeRate;
    private BigDecimal estimatedEntryFee;
    private BigDecimal estimatedCloseFee;
    private BigDecimal expectedNetAfterFees;
    private BigDecimal sizingMultiplier;
    private String sizingReason;
    private String entryGateReason;
    private String primaryModel;
    private String confirmationModels;
    private String policyVersion;
    private String marketRegime;
    private Long factorSnapshotId;
    private String entryHypothesis;
    private BigDecimal invalidationPrice;
    private BigDecimal targetZoneLow;
    private BigDecimal targetZoneHigh;
    private BigDecimal expectedRMultiple;
    private BigDecimal momentumQualityScore;
    private String exitSnapshotState;
    private BigDecimal exitSnapshotNet;
    private BigDecimal exitSnapshotMfe;
    private BigDecimal exitSnapshotMae;
    private BigDecimal exitSnapshotGiveback;
    private Long exitSnapshotBars;
    private String exitSnapshotCandidates;
    private LocalDateTime exitSnapshotUpdatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
