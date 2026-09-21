package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Append-only cross-strategy ledger event used by dashboards, attribution and backtests. */
@Data
@TableName("coin_strategy_ledger_event")
public class CoinStrategyLedgerEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventKey;
    private String eventType;
    private Long accountId;
    private String accountLabel;
    private String exchange;
    private String marketType;
    private String strategyFamily;
    private String strategyName;
    private String modelName;
    private String runId;
    private Long factorSnapshotId;
    private String symbol;
    private String contractCode;
    private Long positionId;
    private Long orderId;
    private String clientOrderId;
    private String side;
    private BigDecimal amountUsdt;
    private BigDecimal feeUsdt;
    private BigDecimal fundingFeeUsdt;
    private BigDecimal grossPnlUsdt;
    private BigDecimal netPnlAfterFee;
    private String reason;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
