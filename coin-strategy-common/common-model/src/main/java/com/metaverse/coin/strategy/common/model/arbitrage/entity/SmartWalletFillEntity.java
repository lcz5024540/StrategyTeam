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
@TableName("smart_wallet_fill")
public class SmartWalletFillEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;
    private Long positionId;
    private Long followTaskId;
    /** 关联公共市场信息表 smart_wallet_market_info.id */
    private Long marketInfoId;
    private String walletAddress;
    private String executionAddress;
    private String side;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal notionalUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal tradingFeeUsdc;
    private BigDecimal feePol;
    private BigDecimal gasFeeUsdc;
    private BigDecimal realizedPnlUsdc;
    private Date filledAt;
    private Date createdAt;

    @TableField(exist = false)
    private SmartWalletMarketInfoEntity marketInfo;

    public String getMarketId()    { return marketInfo == null ? null : marketInfo.getMarketId(); }
    public String getQuestion()    { return marketInfo == null ? null : marketInfo.getQuestion(); }
    public String getOutcome()     { return marketInfo == null ? null : marketInfo.getOutcome(); }
    public String getTokenId()     { return marketInfo == null ? null : marketInfo.getTokenId(); }
    public Integer getIndexSet()   { return marketInfo == null ? null : marketInfo.getIndexSet(); }
    public Long getMarketEndAt()   { return marketInfo == null ? null : marketInfo.getMarketEndAt(); }
    public String getSourceCategory() { return marketInfo == null ? null : marketInfo.getSourceCategory(); }
    public String getNormalizedCategory() { return marketInfo == null ? null : marketInfo.getNormalizedCategory(); }
    public String getMarketLifecycle() { return marketInfo == null ? null : marketInfo.getMarketLifecycle(); }
    public Boolean getClosed() { return marketInfo == null ? null : marketInfo.getClosed(); }
    public Boolean getAcceptingOrders() { return marketInfo == null ? null : marketInfo.getAcceptingOrders(); }
    public String getResolvedOutcome() { return marketInfo == null ? null : marketInfo.getResolvedOutcome(); }

    private SmartWalletMarketInfoEntity ensureMarketInfo() {
        if (marketInfo == null) marketInfo = new SmartWalletMarketInfoEntity();
        return marketInfo;
    }
    public SmartWalletFillEntity setMarketId(String v)           { ensureMarketInfo().setMarketId(v); return this; }
    public SmartWalletFillEntity setQuestion(String v)           { ensureMarketInfo().setQuestion(v); return this; }
    public SmartWalletFillEntity setOutcome(String v)            { ensureMarketInfo().setOutcome(v); return this; }
    public SmartWalletFillEntity setTokenId(String v)            { ensureMarketInfo().setTokenId(v); return this; }
    public SmartWalletFillEntity setIndexSet(Integer v)          { ensureMarketInfo().setIndexSet(v); return this; }
    public SmartWalletFillEntity setMarketEndAt(Long v)          { ensureMarketInfo().setMarketEndAt(v); return this; }
    public SmartWalletFillEntity setSourceCategory(String v)     { ensureMarketInfo().setSourceCategory(v); return this; }
    public SmartWalletFillEntity setNormalizedCategory(String v) { ensureMarketInfo().setNormalizedCategory(v); return this; }
    public SmartWalletFillEntity setMarketLifecycle(String v)    { ensureMarketInfo().setMarketLifecycle(v); return this; }
    public SmartWalletFillEntity setClosed(Boolean v)            { ensureMarketInfo().setClosed(v); return this; }
    public SmartWalletFillEntity setAcceptingOrders(Boolean v)   { ensureMarketInfo().setAcceptingOrders(v); return this; }
    public SmartWalletFillEntity setResolvedOutcome(String v)    { ensureMarketInfo().setResolvedOutcome(v); return this; }
}
