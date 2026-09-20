package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Append-only-ish audit row for one HTX trend-following contract order. */
@Data
@TableName("htx_trend_order")
public class HtxTrendOrderEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long positionId;
    private String positionKey;
    private String contractCode;
    private String leg;
    private String trendSide;
    private String orderSide;
    private String positionSide;
    private Boolean reduceOnly;
    private String clientOrderId;
    private Long exchangeOrderId;
    private Long exchangeClientOrderId;
    private Long requestVolume;
    private BigDecimal filledContracts;
    private BigDecimal averagePrice;
    private BigDecimal fee;
    private String feeCurrency;
    private BigDecimal realizedProfit;
    private String exchangeState;
    private String status;
    private String reason;
    private String message;
    private String accountLabel;
    private BigDecimal feeRateUsed;
    private BigDecimal estimatedFee;
    private BigDecimal estimatedOpenFee;
    private BigDecimal estimatedCloseFee;
    private BigDecimal sizingMultiplier;
    private String sizingReason;
    private String entryGateReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
