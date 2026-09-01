package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Durable intent row for one aggregate moving-grid delta hedge action. */
@Data
@TableName("htx_delta_hedge_ledger")
public class HtxDeltaHedgeLedgerEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String hedgeKey;
    private String symbol;
    private String contractCode;
    private Long epoch;
    private String action;
    private BigDecimal targetShortBase;
    private Long contractVolume;
    private String side;
    private String positionSide;
    private Boolean reduceOnly;
    private String clientOrderId;
    private Long exchangeOrderId;
    private String exchangeClientOrderId;
    private String exchangeState;
    private BigDecimal filledContracts;
    private BigDecimal tradeAvgPrice;
    private BigDecimal fee;
    private String feeCurrency;
    private BigDecimal realizedProfit;
    private String plannerState;
    private String status;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
