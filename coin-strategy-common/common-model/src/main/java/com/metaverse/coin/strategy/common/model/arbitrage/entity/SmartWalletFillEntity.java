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
@TableName("smart_wallet_fill")
public class SmartWalletFillEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private Long followTaskId;
    private String walletAddress;
    private String executionAddress;
    private String marketId;
    private String tokenId;
    private String question;
    private String outcome;
    private String side;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal notionalUsdc;
    private BigDecimal feeUsdc;
    private Date filledAt;
    private Date createdAt;
}
