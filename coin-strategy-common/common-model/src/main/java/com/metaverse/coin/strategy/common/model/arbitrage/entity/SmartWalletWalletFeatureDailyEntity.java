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
@TableName("smart_wallet_wallet_feature_daily")
public class SmartWalletWalletFeatureDailyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private Date featureDate;
    @TableField("trades_7d")
    private Integer trades7d;
    @TableField("trades_30d")
    private Integer trades30d;
    @TableField("markets_30d")
    private Integer markets30d;
    @TableField("pnl_30d")
    private BigDecimal pnl30d;
    @TableField("win_rate_30d")
    private BigDecimal winRate30d;
    @TableField("avg_size_30d")
    private BigDecimal avgSize30d;
    @TableField("drawdown_30d")
    private BigDecimal drawdown30d;
    @TableField("concentration_market_30d")
    private BigDecimal concentrationMarket30d;
    @TableField("low_liquidity_ratio_30d")
    private BigDecimal lowLiquidityRatio30d;
    @TableField("late_market_ratio_30d")
    private BigDecimal lateMarketRatio30d;
    @TableField("followable_rate_30d")
    private BigDecimal followableRate30d;
    private Date createdAt;
    private Date updatedAt;
}
