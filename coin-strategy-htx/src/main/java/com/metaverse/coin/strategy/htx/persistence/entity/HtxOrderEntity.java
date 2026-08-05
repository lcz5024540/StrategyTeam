package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("htx_order")
public class HtxOrderEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pairId;
    private String clientOrderId;
    private Long htxOrderId;
    private String side;
    private String type;
    private BigDecimal price;
    private BigDecimal reqAmount;
    private String state;
    private BigDecimal filledAmount;
    private BigDecimal filledCashAmount;
    private BigDecimal filledFees;
    private String feeCurrency;
    private String intentStatus;
    private String rawResponse;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
