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
@TableName("smart_wallet_feature_snapshot")
public class SmartWalletFeatureSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String datasetVersion;
    private String walletAddress;
    private Date featureAsOf;
    private Date featureAvailableAt;
    private Date sourceProfileUpdatedAt;
    private BigDecimal winRate;
    private BigDecimal credibleWinRate;
    private BigDecimal shrunkWinRate;
    private Integer decisiveTrades;
    private BigDecimal recentWinRate7d;
    private BigDecimal recentWinRate30d;
    private Integer recentTradeCount7d;
    private Integer recentTradeCount30d;
    private BigDecimal realizedPnlUsdc;
    private Integer totalTrades;
    private BigDecimal averageRoiRate;
    private BigDecimal maxDrawdownRate;
    private BigDecimal walletScore;
    private Boolean qualified;
    private Boolean watchEnabled;
    private Date observeCooldownUntil;
    private String archetype;
    private BigDecimal replicabilityScore;
    private BigDecimal qualityScore;
    private BigDecimal stabilityScore;
    private BigDecimal behaviorComposite;
    private String recommendedExitLane;
    private Date createdAt;
}
