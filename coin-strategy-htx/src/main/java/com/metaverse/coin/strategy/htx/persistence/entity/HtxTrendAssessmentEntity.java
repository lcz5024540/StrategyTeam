package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable entry, holding or trend-transition evidence. */
@Data
@TableName("htx_trend_assessment")
public class HtxTrendAssessmentEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String assessmentKey;
    private String assessmentStage;
    private Long positionId;
    private String runId;
    private String accountLabel;
    private String contractCode;
    private String side;
    private String primaryModel;
    private String timeframeProfile;
    private Long barTime;
    private String trendState;
    private String previousTrendState;
    private String transitionType;
    private String transitionReason;
    private BigDecimal referencePrice;
    private BigDecimal expectedRMultiple;
    private BigDecimal currentNetPnl;
    private BigDecimal mfeNetPnl;
    private BigDecimal maeNetPnl;
    private BigDecimal protectionPrice;
    private String evidenceJson;
    private LocalDateTime createdAt;
}
