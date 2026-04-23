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
@TableName("smart_wallet_position")
public class SmartWalletPositionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联公共市场信息表 smart_wallet_market_info.id */
    private Long marketInfoId;
    private String executionAddress;
    private String netSide;
    private BigDecimal quantity;
    private BigDecimal averageEntryPrice;
    private BigDecimal costBasisUsdc;
    private BigDecimal originalCostBasisUsdc;
    private BigDecimal feePolTotal;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal lastMarkPrice;
    private Date openedAt;
    private Date lastFilledAt;
    private Date closedAt;
    private String status;
    private String source;  // FOLLOW_TRADE / WEATHER
    private Date createdAt;
    private Date updatedAt;

    @TableField(exist = false)
    private SmartWalletMarketInfoEntity marketInfo;

    public String getMarketId()    { return marketInfo == null ? null : marketInfo.getMarketId(); }
    public String getQuestion()    { return marketInfo == null ? null : marketInfo.getQuestion(); }
    public String getOutcome()     { return marketInfo == null ? null : marketInfo.getOutcome(); }
    public String getTokenId()     { return marketInfo == null ? null : marketInfo.getTokenId(); }
    public Integer getIndexSet()   { return marketInfo == null ? null : marketInfo.getIndexSet(); }
    public Long getMarketEndAt()   { return marketInfo == null ? null : marketInfo.getMarketEndAt(); }
}
