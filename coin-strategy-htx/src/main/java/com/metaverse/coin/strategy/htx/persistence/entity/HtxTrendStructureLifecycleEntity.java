package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable ownership lifecycle for one completed K-line entry cycle. A Fibonacci breakout is evidence only;
 * it never identifies a later trade. A later completed K-line starts a distinct lifecycle.
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
