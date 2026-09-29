package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable manifest for one reproducible strategy research input set. */
@Data
@TableName("htx_trend_research_dataset")
public class HtxTrendResearchDatasetEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String datasetKey;
    private String sourceKind;
    private String sourceReference;
    private LocalDateTime capturedAt;
    private LocalDateTime inputStartAt;
    private LocalDateTime inputEndAt;
    private String contractsJson;
    private String periodsJson;
    private String inputChecksum;
    private String inputIntegrity;
    private String status;
    private String purpose;
    private String provenanceJson;
    private LocalDateTime createdAt;
}
