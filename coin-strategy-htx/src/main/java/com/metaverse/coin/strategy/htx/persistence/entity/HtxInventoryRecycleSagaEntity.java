package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Durable saga row for paired spot-inventory recycle and reduce-only short close. */
@Data
@TableName("htx_inventory_recycle_saga")
public class HtxInventoryRecycleSagaEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sagaKey;
    private String symbol;
    private String contractCode;
    private Long epoch;
    private Long spotFillLedgerId;
    private BigDecimal spotSoldBase;
    private BigDecimal availableShortBase;
    private Long closeContracts;
    private String closeClientOrderId;
    private Long closeExchangeOrderId;
    private String closeExchangeClientOrderId;
    private String closeExchangeState;
    private BigDecimal closeFilledContracts;
    private BigDecimal closeTradeAvgPrice;
    private BigDecimal closeFee;
    private String closeFeeCurrency;
    private BigDecimal closeRealizedProfit;
    private String plannerState;
    private String status;
    private String reason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
