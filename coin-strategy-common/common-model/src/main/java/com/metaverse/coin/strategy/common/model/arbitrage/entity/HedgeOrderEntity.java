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
@TableName("hedge_order")
public class HedgeOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long hedgePositionId;
    private String marketId;
    private String tokenId;
    /** YES | NO */
    private String side;

    private String externalOrderId;
    private String orderType;
    private BigDecimal price;
    private BigDecimal requestedQuantity;
    private BigDecimal filledQuantity;
    private BigDecimal costUsdc;
    private BigDecimal feeUsdc;

    /** PENDING | FILLED | PARTIAL | FAILED | CANCELLED */
    private String status;
    /** PAPER | LIVE */
    private String mode;
    private String errorMessage;
    private Integer pollAttempts;
    private Date nextPollAt;

    private Date submittedAt;
    private Date filledAt;
    private Date createdAt;
    private Date updatedAt;
}
