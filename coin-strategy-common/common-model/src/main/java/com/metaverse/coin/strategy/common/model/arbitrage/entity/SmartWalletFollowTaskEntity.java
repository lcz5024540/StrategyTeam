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
@TableName("smart_wallet_follow_task")
public class SmartWalletFollowTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String walletAddress;

    /** 触发钱包对应的实体ID */
    private String entityId;

    /** 关联的候选跟单信号ID */
    private Long followCandidateId;

    private Long sourceEventId;

    /**
     * 跟单链路 trace_id, 继承自 source_event_id 对应 trade_event.trace_id.
     * 写入时机: SmartWalletFollowTaskService 创建任务时, 用 sourceEventId 反查并设置.
     * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T3).
     */
    private String traceId;

    /** 关联公共市场信息表 smart_wallet_market_info.id */
    private Long marketInfoId;
    private String side;
    private BigDecimal triggerPrice;
    private BigDecimal triggerSizeUsdc;
    private BigDecimal recommendedStakeUsdc;
    private String status;
    private String executionMode;
    private String externalOrderId;
    private String errorMessage;
    private String primarySkipReason;
    private String skipReasonTags;
    private String wouldHaveSkippedReason;
    /** P0-2: 意图聚合标识。同 wallet+market+outcome+side 在时间窗口内的事件共享同一 intentKey */
    private String intentKey;
    /** P0-2: 该意图下聚合的碎片事件数量 */
    private Integer fragmentCount;
    private Date scheduledAt;
    private Date executedAt;
    private Date createdAt;
    private Date updatedAt;

    @TableField(exist = false)
    private SmartWalletMarketInfoEntity marketInfo;

    public String getMarketId()    { return marketInfo == null ? null : marketInfo.getMarketId(); }
    public String getQuestion()    { return marketInfo == null ? null : marketInfo.getQuestion(); }
    public String getOutcome()     { return marketInfo == null ? null : marketInfo.getOutcome(); }
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
    public SmartWalletFollowTaskEntity setMarketId(String v)           { ensureMarketInfo().setMarketId(v); return this; }
    public SmartWalletFollowTaskEntity setQuestion(String v)           { ensureMarketInfo().setQuestion(v); return this; }
    public SmartWalletFollowTaskEntity setOutcome(String v)            { ensureMarketInfo().setOutcome(v); return this; }
    public SmartWalletFollowTaskEntity setMarketEndAt(Long v)          { ensureMarketInfo().setMarketEndAt(v); return this; }
    public SmartWalletFollowTaskEntity setSourceCategory(String v)     { ensureMarketInfo().setSourceCategory(v); return this; }
    public SmartWalletFollowTaskEntity setNormalizedCategory(String v) { ensureMarketInfo().setNormalizedCategory(v); return this; }
    public SmartWalletFollowTaskEntity setMarketLifecycle(String v)    { ensureMarketInfo().setMarketLifecycle(v); return this; }
    public SmartWalletFollowTaskEntity setClosed(Boolean v)            { ensureMarketInfo().setClosed(v); return this; }
    public SmartWalletFollowTaskEntity setAcceptingOrders(Boolean v)   { ensureMarketInfo().setAcceptingOrders(v); return this; }
    public SmartWalletFollowTaskEntity setResolvedOutcome(String v)    { ensureMarketInfo().setResolvedOutcome(v); return this; }
}
