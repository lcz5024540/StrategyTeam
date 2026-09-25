package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable audit event for a profit-protection state transition or its execution outcome. */
@Data
@TableName("htx_trend_profit_protection_event")
public class HtxTrendProfitProtectionEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventKey;
    private Long positionId;
    private String runId;
    private Long accountId;
    private String accountLabel;
    private String contractCode;
    private String side;
    private String primaryModel;
    private String eventType;
    private String protectionMode;
    private BigDecimal referencePrice;
    private BigDecimal mfePrice;
    private BigDecimal mfeNetPnl;
    private BigDecimal protectionPrice;
    private BigDecimal projectedLockedNetPnl;
    private BigDecimal mfeCaptureRatio;
    private String executionReadiness;
    private String reason;
    private String evidenceJson;
    private LocalDateTime createdAt;
}
