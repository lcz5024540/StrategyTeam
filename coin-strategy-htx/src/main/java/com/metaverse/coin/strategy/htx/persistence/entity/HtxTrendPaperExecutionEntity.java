package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable simulated execution; this table is never consumed by HTX order placement. */
@Data
@TableName("htx_trend_paper_execution")
public class HtxTrendPaperExecutionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String executionKey;
    private String runId;
    private String contractCode;
    private String outcome;
    private BigDecimal fillPrice;
    private Long filledContracts;
    private String reason;
    private LocalDateTime createdAt;
}
