package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Versioned evaluation of one independent model and sample slice. */
@Data
@TableName("htx_trend_model_evaluation")
public class HtxTrendModelEvaluationEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String evaluationKey;
    private String datasetKey;
    private String researchVersion;
    private String sampleSplit;
    private String modelCode;
    private String modelVersion;
    private String direction;
    private String timeframeProfile;
    private String marketState;
    private String symbolBucket;
    private Long tradeCount;
    private Long winCount;
    private Long lossCount;
    private BigDecimal netProfit;
    private BigDecimal profitFactor;
    private BigDecimal expectancy;
    private BigDecimal maxDrawdown;
    private BigDecimal feeTotal;
    private String decision;
    private String evidenceJson;
    private LocalDateTime createdAt;
}
