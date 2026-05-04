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
@TableName("smart_wallet_trade_order")
public class SmartWalletTradeOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long positionId;
    private Long marketInfoId;
    private String executionAddress;
    private String walletAddress;
    private Long sourceFollowTaskId;

    private BigDecimal entryQuantity;
    private BigDecimal entryAvgPrice;
    private BigDecimal entryCostUsdc;
    private BigDecimal entryFeeUsdc;
    private Date openedAt;

    private BigDecimal exitQuantity;
    private BigDecimal exitAvgPrice;
    private BigDecimal exitProceedsUsdc;
    private BigDecimal exitRealizedPnlUsdc;
    private BigDecimal exitFeeUsdc;
    private Date lastExitAt;

    private BigDecimal remainingQuantity;
    private BigDecimal remainingCostBasisUsdc;
    private BigDecimal lastMarkPrice;
    private BigDecimal unrealizedPnlUsdc;

    private BigDecimal settlementPrice;
    private BigDecimal settlementProceedsUsdc;
    private BigDecimal settlementPnlUsdc;
    private Date settledAt;

    private BigDecimal totalRealizedPnlUsdc;
    private BigDecimal totalPnlUsdc;
    private BigDecimal roiRate;
    private String status;

    private Date createdAt;
    private Date updatedAt;

    @TableField(exist = false)
    private SmartWalletMarketInfoEntity marketInfo;

    public String getMarketId() { return marketInfo == null ? null : marketInfo.getMarketId(); }
    public String getMarketSlug() { return marketInfo == null ? null : marketInfo.getMarketSlug(); }
    public String getQuestion() { return marketInfo == null ? null : marketInfo.getQuestion(); }
    public String getOutcome() { return marketInfo == null ? null : marketInfo.getOutcome(); }
    public String getTokenId() { return marketInfo == null ? null : marketInfo.getTokenId(); }
    public Long getMarketEndAt() { return marketInfo == null ? null : marketInfo.getMarketEndAt(); }
    public String getNormalizedCategory() { return marketInfo == null ? null : marketInfo.getNormalizedCategory(); }
    public String getSourceCategory() { return marketInfo == null ? null : marketInfo.getSourceCategory(); }
    public String getMarketLifecycle() { return marketInfo == null ? null : marketInfo.getMarketLifecycle(); }
    public Boolean getClosed() { return marketInfo == null ? null : marketInfo.getClosed(); }
    public Boolean getAcceptingOrders() { return marketInfo == null ? null : marketInfo.getAcceptingOrders(); }
}
