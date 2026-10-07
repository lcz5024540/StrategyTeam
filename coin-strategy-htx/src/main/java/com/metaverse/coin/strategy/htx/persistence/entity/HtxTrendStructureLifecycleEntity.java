package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable ownership lifecycle for one model structure. It only prevents concurrent duplicate entries while an
 * intent or position is active. A terminal trade releases the structure for a later candidate to reassess.
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
