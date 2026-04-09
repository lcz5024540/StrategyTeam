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
@TableName("smart_wallet_position")
public class SmartWalletPositionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String executionAddress;
    private String marketId;
    private String tokenId;
    private String question;
    private String outcome;
    private Long marketEndAt;
    private String netSide;
    private BigDecimal quantity;
    private BigDecimal averageEntryPrice;
    private BigDecimal costBasisUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal lastMarkPrice;
    private Date openedAt;
    private Date lastFilledAt;
    private Date closedAt;
    private String status;
    private Date createdAt;
    private Date updatedAt;
}
