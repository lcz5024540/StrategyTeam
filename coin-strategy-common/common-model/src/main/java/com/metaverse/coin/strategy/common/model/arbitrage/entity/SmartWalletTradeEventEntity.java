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
@TableName("smart_wallet_trade_event")
public class SmartWalletTradeEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String chain;
    private String source;
    private String externalTradeId;
    private String marketId;
    private String question;
    private String outcome;
    private String side;
    private BigDecimal price;
    private BigDecimal sizeUsdc;
    private Date openedAt;
    private Boolean processed;
    private Date createdAt;
    private Date updatedAt;
}
