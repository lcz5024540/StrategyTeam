package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable audit record for a strategy position reconciled against HTX's current position view. */
@Data
@TableName("htx_trend_position_reconciliation")
public class HtxTrendPositionReconciliationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String reconciliationKey;
    private Long positionId;
    private String accountLabel;
    private String contractCode;
    private String priorStatus;
    private String exchangePositionState;
    private Long closeOrderId;
    private String closeClientOrderId;
    private Long exchangeCloseOrderId;
    private String resolution;
    private String reason;
    private LocalDateTime createdAt;
}
