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
@TableName("smart_wallet_market_snapshot")
public class SmartWalletMarketSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String marketId;
    private String outcome;
    private Date snapshotAt;
    private BigDecimal midPrice;
    private BigDecimal bestBid;
    private BigDecimal bestAsk;
    private BigDecimal spreadBps;
    @TableField("bid_depth_1c")
    private BigDecimal bidDepth1c;
    @TableField("ask_depth_1c")
    private BigDecimal askDepth1c;
    @TableField("bid_depth_3c")
    private BigDecimal bidDepth3c;
    @TableField("ask_depth_3c")
    private BigDecimal askDepth3c;
    @TableField("bid_depth_5c")
    private BigDecimal bidDepth5c;
    @TableField("ask_depth_5c")
    private BigDecimal askDepth5c;
    private BigDecimal orderImbalance;
    private BigDecimal lastTradePrice;
    private BigDecimal lastTradeSize;
    @TableField("trade_count_30s")
    private Integer tradeCount30s;
    @TableField("buy_volume_30s")
    private BigDecimal buyVolume30s;
    @TableField("sell_volume_30s")
    private BigDecimal sellVolume30s;
    @TableField("realized_vol_30s")
    private BigDecimal realizedVol30s;
    private Long timeToEndSeconds;
    private Date createdAt;
}
