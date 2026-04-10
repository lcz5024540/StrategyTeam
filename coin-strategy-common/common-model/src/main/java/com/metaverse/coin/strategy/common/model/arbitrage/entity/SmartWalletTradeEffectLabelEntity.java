package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    @TableField("mid_t_10s")
    private BigDecimal midT10s;
    @TableField("mid_t_30s")
    private BigDecimal midT30s;
    @TableField("mid_t_60s")
    private BigDecimal midT60s;
    @TableField("mid_t_5m")
    private BigDecimal midT5m;
    @TableField("mid_t_15m")
    private BigDecimal midT15m;
    @TableField("last_trade_t_10s")
    private BigDecimal lastTradeT10s;
    @TableField("last_trade_t_30s")
    private BigDecimal lastTradeT30s;
    @TableField("last_trade_t_5m")
    private BigDecimal lastTradeT5m;
    private BigDecimal paperStakeUsdc;
    private BigDecimal estimatedFeeRate;
    private BigDecimal estimatedSlippageRate;
    private BigDecimal estimatedFeeUsdc;
    private BigDecimal estimatedSlippageUsdc;
    private BigDecimal realizedSlippageBps;
    private BigDecimal realizedSlippageUsdc;
    private BigDecimal paperEntryPrice;
    @TableField("paper_exit_price_30s")
    private BigDecimal paperExitPrice30s;
    @TableField("paper_exit_price_5m")
    private BigDecimal paperExitPrice5m;
    private BigDecimal paperExitPriceRule;
    @TableField("paper_pnl_30s")
    private BigDecimal paperPnl30s;
    @TableField("paper_pnl_5m")
    private BigDecimal paperPnl5m;
    private BigDecimal paperPnlRule;
    @TableField("net_pnl_30s")
    private BigDecimal netPnl30s;
    @TableField("net_pnl_5m")
    private BigDecimal netPnl5m;
    private BigDecimal netPnlRule;
    @TableField("is_positive_ev_30s")
    private Boolean isPositiveEv30s;
    @TableField("is_positive_ev_5m")
    private Boolean isPositiveEv5m;
    private Boolean isPositiveEvRule;
    private Date createdAt;
    private Date updatedAt;
}
