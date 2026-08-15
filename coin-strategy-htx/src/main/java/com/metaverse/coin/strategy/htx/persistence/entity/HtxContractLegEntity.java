package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Durable audit row for one long/short open/close leg in a contract cycle. */
@Data
@TableName("htx_contract_leg")
public class HtxContractLegEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long cycleId;
    private String legType;
    private Long clientOrderId;
    private Long orderId;
    private String side;
    private String positionSide;
    private Boolean reduceOnly;
    private Long reqVolume;
    private BigDecimal price;
    private String state;
    private BigDecimal filledVolume;
    private BigDecimal averagePrice;
    private BigDecimal fee;
    private String feeCurrency;
    private BigDecimal realizedProfit;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
