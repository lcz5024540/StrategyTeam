package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("smart_wallet_order")
public class SmartWalletOrderEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long followTaskId;
    private Long positionId;
    /** 关联公共市场信息表 smart_wallet_market_info.id */
    private Long marketInfoId;
    private String walletAddress;
    private String executionMode;
    private String executionAddress;
    private String externalOrderId;
    private String side;
    private String orderType;
    private String status;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal notionalUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal tradingFeeUsdc;
    private BigDecimal feePol;
    private BigDecimal gasFeeUsdc;
    private BigDecimal realizedPnlUsdc;
    private String exitReason;
    private String errorMessage;
    private Date placedAt;
    private Date filledAt;
    private Date nextPollAt;
    private Integer pollAttempts;
    private Date createdAt;
    private Date updatedAt;

    /** 乐观锁版本号，防止 OrderPollingService 与 AccountingService 并发更新冲突 */
    @Version
    private Integer version;

    // ── 运行时关联对象，不映射数据库列 ──
    @TableField(exist = false)
    private SmartWalletMarketInfoEntity marketInfo;

    // ── 便捷代理方法：业务代码通过这些方法读取市场信息，无需手动 join ──
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
    public SmartWalletOrderEntity setMarketId(String v)           { ensureMarketInfo().setMarketId(v); return this; }
    public SmartWalletOrderEntity setQuestion(String v)           { ensureMarketInfo().setQuestion(v); return this; }
    public SmartWalletOrderEntity setOutcome(String v)            { ensureMarketInfo().setOutcome(v); return this; }
    public SmartWalletOrderEntity setTokenId(String v)            { ensureMarketInfo().setTokenId(v); return this; }
    public SmartWalletOrderEntity setIndexSet(Integer v)          { ensureMarketInfo().setIndexSet(v); return this; }
    public SmartWalletOrderEntity setMarketEndAt(Long v)          { ensureMarketInfo().setMarketEndAt(v); return this; }
    public SmartWalletOrderEntity setSourceCategory(String v)     { ensureMarketInfo().setSourceCategory(v); return this; }
    public SmartWalletOrderEntity setNormalizedCategory(String v) { ensureMarketInfo().setNormalizedCategory(v); return this; }
    public SmartWalletOrderEntity setMarketLifecycle(String v)    { ensureMarketInfo().setMarketLifecycle(v); return this; }
    public SmartWalletOrderEntity setClosed(Boolean v)            { ensureMarketInfo().setClosed(v); return this; }
    public SmartWalletOrderEntity setAcceptingOrders(Boolean v)   { ensureMarketInfo().setAcceptingOrders(v); return this; }
    public SmartWalletOrderEntity setResolvedOutcome(String v)    { ensureMarketInfo().setResolvedOutcome(v); return this; }
}
