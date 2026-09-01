package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** FIFO spot inventory lot sourced from a buy fill ledger row. */
@Data
@TableName("htx_inventory_lot")
public class HtxInventoryLotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String symbol;
    private Long sourcePairId;
    private Long buyOrderId;
    private Long buyFillLedgerId;
    private BigDecimal acquiredBase;
    private BigDecimal remainingBase;
    private BigDecimal costQuote;
    private BigDecimal feeBase;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
