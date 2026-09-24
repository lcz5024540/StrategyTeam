package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Versioned HTX account fee schedule observed while calculating trend execution costs. */
@Data
@TableName("htx_trade_account_fee_profile")
public class HtxTradeAccountFeeProfileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private String accountLabel;
    private String exchange;
    private String marketType;
    private String contractCode;
    private BigDecimal openMakerRate;
    private BigDecimal openTakerRate;
    private BigDecimal closeMakerRate;
    private BigDecimal closeTakerRate;
    private BigDecimal effectiveTakerRate;
    private String source;
    private Boolean fallback;
    private String currentProfileKey;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private LocalDateTime lastObservedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
