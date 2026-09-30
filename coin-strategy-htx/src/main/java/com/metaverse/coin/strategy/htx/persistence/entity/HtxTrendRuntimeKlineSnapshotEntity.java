package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** A reproducible input-window reference for one runtime trend calculation. */
@Data
@TableName("htx_trend_runtime_kline_snapshot")
public class HtxTrendRuntimeKlineSnapshotEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String snapshotKey;
    private String runId;
    private Long factorSnapshotId;
    private Long positionId;
    private String captureStage;
    private String contractCode;
    private String period;
    private Long targetBarTime;
    private Long windowStartBarTime;
    private Long windowEndBarTime;
    private Integer expectedWindowSize;
    private Integer actualWindowSize;
    private Boolean completeWindow;
    private String windowSha256;
    private String sourceKind;
    private String sourceReference;
    private String dataIntegrity;
    private LocalDateTime capturedAt;
}
