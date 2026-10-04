package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable paper-order lifecycle evidence. It is deliberately separate from the paper fill
 * summary so replay can distinguish an accepted order, a partial fill and a cancellation.
 */
@Data
@TableName("htx_trend_paper_execution_event")
public class HtxTrendPaperExecutionEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventKey;
    private String executionKey;
    private String runId;
    private String stateFrom;
    private String stateTo;
    private String eventType;
    private Long requestedContracts;
    private Long cumulativeFilledContracts;
    private BigDecimal fillPrice;
    private String reason;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
