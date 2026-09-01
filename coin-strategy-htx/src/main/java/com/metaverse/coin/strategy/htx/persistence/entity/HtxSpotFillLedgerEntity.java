package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable spot fill delta derived from HTX cumulative order snapshots. */
@Data
@TableName("htx_spot_fill_ledger")
public class HtxSpotFillLedgerEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ledgerKey;
    private String symbol;
    private Long pairId;
    private Long orderRowId;
    private Long htxOrderId;
    private String clientOrderId;
    private String side;
    private String orderState;
    private BigDecimal deltaBase;
    private BigDecimal deltaQuote;
    private BigDecimal feeAmount;
    private String feeCurrency;
    private BigDecimal cumulativeFilledAmount;
    private BigDecimal cumulativeFilledCashAmount;
    private BigDecimal cumulativeFilledFees;
    private String source;
    private LocalDateTime observedAt;
    private LocalDateTime createdAt;
}
