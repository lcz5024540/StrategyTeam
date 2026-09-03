package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("cross_exchange_execution_fill")
public class CrossExchangeExecutionFillEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String positionCode;
    private String clientOrderId;
    private Integer levelNo;
    private String exchangeName;
    private String symbol;
    private String side;
    private BigDecimal price;
    private BigDecimal filledQuantity;
    private BigDecimal filledNotional;
    private Date bookReceivedAt;
    private String bookFingerprint;
    private Date createdAt;
}
