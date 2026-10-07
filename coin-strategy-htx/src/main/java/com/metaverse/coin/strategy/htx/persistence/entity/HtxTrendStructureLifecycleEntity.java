package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable lifecycle for one model structure. A Fibonacci breakout/retest may create one real trade only;
 * later scans may observe the same structure but must not turn it into another entry after it was consumed.
 */
@Data
@TableName("htx_trend_structure_lifecycle")
public class HtxTrendStructureLifecycleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String structureKey;
    private String contractCode;
    private String timeframe;
    private String direction;
    private String primaryModel;
    private Long breakoutBarTime;
    private String state;
    private String sourceRunId;
    private Long entryPositionId;
    private Long terminalPositionId;
    private String terminalReason;
    private LocalDateTime firstSeenAt;
    private LocalDateTime openedAt;
    private LocalDateTime terminalAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
