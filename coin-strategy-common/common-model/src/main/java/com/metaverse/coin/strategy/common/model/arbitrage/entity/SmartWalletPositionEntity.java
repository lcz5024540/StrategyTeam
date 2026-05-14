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
    /**
     * 可领取收益USDC. 此字段当前**仅存在于 entity / view 层, 不持久化到 smart_wallet_position 表**.
     * 业务上通过 SmartWalletAccountingService.lockedClaimProceedsForPosition(position) 在
     * RESOLVED_UNCLAIMED 状态下实时计算; SmartWalletReportReadService /
     * SmartWalletSettlementReadService 读到的 getter 永远是 null (safe() 会转 0), 这是历史现状,
     * 不是 bug 也不是本次修改引入. 加 @TableField(exist = false) 是为了让 MyBatis-Plus 自动
     * SELECT 不再带上 claimable_proceeds_usdc 列, 避免 BadSqlGrammarException.
     * 未来如果业务要落库, 再去掉这个注解 + 加 DDL.
     */
    @TableField(exist = false)
    private BigDecimal claimableProceedsUsdc;
    private BigDecimal lastMarkPrice;
    private Date openedAt;
    private Date lastFilledAt;
    private Date closedAt;
    private String status;
    private String source;  // FOLLOW_TRADE / WEATHER
    private Date createdAt;
    private Date updatedAt;

    /**
     * 乐观锁版本号，防止 PositionSyncService / SettlementSyncService / ClaimService
     * 三个定时任务并发修改仓位状态时出现状态跳跃。
     * MyBatis-Plus 的 OptimisticLockerInnerInterceptor 会自动在 UPDATE 时
     * 添加 WHERE version=? 并 SET version=version+1。
     */
    @Version
    private Integer version;

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

    // Delegation setters – update the nested marketInfo for test-setup convenience
    private SmartWalletMarketInfoEntity ensureMarketInfo() {
        if (marketInfo == null) marketInfo = new SmartWalletMarketInfoEntity();
        return marketInfo;
    }
    public SmartWalletPositionEntity setMarketId(String v)           { ensureMarketInfo().setMarketId(v); return this; }
    public SmartWalletPositionEntity setQuestion(String v)           { ensureMarketInfo().setQuestion(v); return this; }
    public SmartWalletPositionEntity setOutcome(String v)            { ensureMarketInfo().setOutcome(v); return this; }
    public SmartWalletPositionEntity setTokenId(String v)            { ensureMarketInfo().setTokenId(v); return this; }
    public SmartWalletPositionEntity setIndexSet(Integer v)          { ensureMarketInfo().setIndexSet(v); return this; }
    public SmartWalletPositionEntity setMarketEndAt(Long v)          { ensureMarketInfo().setMarketEndAt(v); return this; }
    public SmartWalletPositionEntity setSourceCategory(String v)     { ensureMarketInfo().setSourceCategory(v); return this; }
    public SmartWalletPositionEntity setNormalizedCategory(String v) { ensureMarketInfo().setNormalizedCategory(v); return this; }
    public SmartWalletPositionEntity setMarketLifecycle(String v)    { ensureMarketInfo().setMarketLifecycle(v); return this; }
    public SmartWalletPositionEntity setClosed(Boolean v)            { ensureMarketInfo().setClosed(v); return this; }
    public SmartWalletPositionEntity setAcceptingOrders(Boolean v)   { ensureMarketInfo().setAcceptingOrders(v); return this; }
    public SmartWalletPositionEntity setResolvedOutcome(String v)    { ensureMarketInfo().setResolvedOutcome(v); return this; }
}
