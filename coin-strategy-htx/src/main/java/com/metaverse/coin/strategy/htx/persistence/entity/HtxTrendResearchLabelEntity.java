package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Outcome label with predeclared barriers; it must never be rewritten after capture. */
@Data
@TableName("htx_trend_research_label")
public class HtxTrendResearchLabelEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String labelKey;
    private String datasetKey;
    private Long sourceCandidateId;
    private String sourceRunId;
    private String modelCode;
    private String modelVersion;
    private String contractCode;
    private String side;
    private String timeframeProfile;
    private Long entryBarTime;
    private BigDecimal entryPrice;
    private BigDecimal stopPrice;
    private BigDecimal targetOneRPrice;
    private BigDecimal targetTwoRPrice;
    private Long horizonBarTime;
    private String firstBarrier;
    private Long firstBarrierTime;
    private BigDecimal mfe;
    private BigDecimal mae;
    private BigDecimal estimatedEntryFee;
    private BigDecimal estimatedExitFee;
    private BigDecimal netProfit;
    private String ambiguityReason;
    private String dataIntegrity;
    private String evidenceJson;
    private LocalDateTime createdAt;
}
