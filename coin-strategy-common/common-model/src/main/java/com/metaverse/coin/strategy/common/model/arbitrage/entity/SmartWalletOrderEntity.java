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
@TableName("smart_wallet_order")
public class SmartWalletOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long followTaskId;
    private Long positionId;
    private String walletAddress;
    private String executionMode;
    private String executionAddress;
    private String externalOrderId;
    private String marketId;
    private String tokenId;
    private String question;
    private String outcome;
    private Long marketEndAt;
    private String side;
    private String orderType;
    private String status;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal notionalUsdc;
    private BigDecimal feeUsdc;
    private String errorMessage;
    private Date placedAt;
    private Date filledAt;
    private Date nextPollAt;
    private Integer pollAttempts;
    private Date createdAt;
    private Date updatedAt;
}
