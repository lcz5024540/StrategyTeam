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
@TableName("smart_wallet_wallet_feature_daily")
public class SmartWalletWalletFeatureDailyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private Date featureDate;
    private Integer trades7d;
    private Integer trades30d;
    private Integer markets30d;
    private BigDecimal pnl30d;
    private BigDecimal winRate30d;
    private BigDecimal avgSize30d;
    private BigDecimal drawdown30d;
    private BigDecimal concentrationMarket30d;
    private BigDecimal lowLiquidityRatio30d;
    private BigDecimal lateMarketRatio30d;
    private BigDecimal followableRate30d;
    private Date createdAt;
    private Date updatedAt;
}
