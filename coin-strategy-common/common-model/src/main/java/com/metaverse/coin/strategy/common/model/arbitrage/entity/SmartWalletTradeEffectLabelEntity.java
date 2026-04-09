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
@TableName("smart_wallet_trade_effect_label")
public class SmartWalletTradeEffectLabelEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sourceEventId;
    private String walletAddress;
    private String marketId;
    private String outcome;
    private String side;
    private Date eventAt;
    private BigDecimal midT0;
    private BigDecimal midT10s;
    private BigDecimal midT30s;
    private BigDecimal midT60s;
    private BigDecimal midT5m;
    private BigDecimal midT15m;
    private BigDecimal lastTradeT10s;
    private BigDecimal lastTradeT30s;
    private BigDecimal lastTradeT5m;
    private BigDecimal paperStakeUsdc;
    private BigDecimal estimatedFeeRate;
    private BigDecimal estimatedSlippageRate;
    private BigDecimal estimatedFeeUsdc;
    private BigDecimal estimatedSlippageUsdc;
    private BigDecimal paperEntryPrice;
    private BigDecimal paperExitPrice30s;
    private BigDecimal paperExitPrice5m;
    private BigDecimal paperExitPriceRule;
    private BigDecimal paperPnl30s;
    private BigDecimal paperPnl5m;
    private BigDecimal paperPnlRule;
    private BigDecimal netPnl30s;
    private BigDecimal netPnl5m;
    private BigDecimal netPnlRule;
    private Boolean isPositiveEv30s;
    private Boolean isPositiveEv5m;
    private Boolean isPositiveEvRule;
    private Date createdAt;
    private Date updatedAt;
}
