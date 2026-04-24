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
@TableName("smart_wallet_behavior_profile")
public class SmartWalletBehaviorProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;
    private String source;
    private String alias;
    private String archetype;
    private String followLane;
    private Boolean highFrequencyTradingAddress;
    private Integer trades7d;
    private Integer trades30d;
    private BigDecimal medianInterTradeMinutes;
    private Integer closedPositionSampleSize;
    private BigDecimal orderWinRate;
    private BigDecimal averageOrderRoiRate;
    private BigDecimal medianOrderRoiRate;
    private BigDecimal totalRealizedPnlUsdc;
    private BigDecimal settlementCompletionRate;
    private BigDecimal earlyExitRate;
    private BigDecimal profitEarlyExitRate;
    private BigDecimal lossEarlyExitRate;
    private String winSourceType;
    private BigDecimal latencyAlphaRate;
    private BigDecimal swingAlphaRate;
    private BigDecimal settlementWinRate;
    private BigDecimal medianHoldMinutes;
    private BigDecimal stabilityScore;
    private BigDecimal qualityScore;
    private BigDecimal replicabilityScore;
    private String recommendedExitLane;
    private String summary;
    private Date computedAt;
    private Date createdAt;
    private Date updatedAt;
}
