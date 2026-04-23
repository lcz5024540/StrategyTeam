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
@TableName("smart_wallet_profile")
public class SmartWalletProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String chain;
    private String alias;
    private String source;
    private BigDecimal winRate;
    private BigDecimal realizedPnlUsdc;
    private Integer totalTrades;
    private BigDecimal averageSizeUsdc;
    private BigDecimal averageRoiRate;
    private BigDecimal maxDrawdownRate;
    private BigDecimal walletScore;
    private Boolean qualified;
    private Boolean watchEnabled;
    private Date lastTradeAt;
    private Date lastEvaluatedAt;
    private Date lastDiscoveredAt;
    private String lastDiscoveryQuery;
    @TableField("leaderboard_hit_count_30d")
    private Integer leaderboardHitCount30d;
    private String dominantSession;
    @TableField("asia_trade_ratio_30d")
    private BigDecimal asiaTradeRatio30d;
    @TableField("eu_trade_ratio_30d")
    private BigDecimal euTradeRatio30d;
    @TableField("us_trade_ratio_30d")
    private BigDecimal usTradeRatio30d;
    private String activeHourHistogramJson;
    @TableField("recent_win_rate_30d")
    private BigDecimal recentWinRate30d;
    private Date createdAt;
    private Date updatedAt;
}
