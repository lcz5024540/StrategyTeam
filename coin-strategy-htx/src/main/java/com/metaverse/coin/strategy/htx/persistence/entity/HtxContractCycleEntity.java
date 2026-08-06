package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Durable audit and recovery boundary for one contract open-long/close-long cycle. */
@Data
@TableName("htx_contract_cycle")
public class HtxContractCycleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long clientOrderId;
    private String contractCode;
    private Long reqVolume;
    private BigDecimal openPrice;
    private BigDecimal closePrice;
    private Integer leverage;
    private String status;
    private Long openOrderId;
    private Long closeOrderId;
    private BigDecimal openFilledVolume;
    private BigDecimal closeFilledVolume;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
