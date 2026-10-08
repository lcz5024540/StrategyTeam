package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable mark-to-market observation for one managed trend position at one completed entry-timeframe bar.
 * It preserves the evidence needed to distinguish a bad entry from a late exit or unprotected profit giveback.
 */
@Data
@TableName("htx_trend_position_mark_snapshot")
public class HtxTrendPositionMarkSnapshotEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String snapshotKey;
    private Long positionId;
    private String runId;
    private Long accountId;
    private String accountLabel;
    private String contractCode;
    private String side;
    private String primaryModel;
    private String timeframeProfile;
    private Long barTime;
    private BigDecimal referencePrice;
    private BigDecimal unrealizedNetPnl;
    private BigDecimal mfeNetPnl;
    private BigDecimal maeNetPnl;
    private BigDecimal givebackRate;
    private BigDecimal protectionPrice;
    private String trendState;
    private String exitPhase;
    private String suggestedAction;
    private String executionMode;
    private String evidenceJson;
    private LocalDateTime createdAt;
}
