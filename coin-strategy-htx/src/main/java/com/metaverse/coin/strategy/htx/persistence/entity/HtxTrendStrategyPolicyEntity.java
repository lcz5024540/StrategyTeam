package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Dashboard-managed policy for one enabled trend model combination. */
@Data
@TableName("htx_trend_strategy_policy")
public class HtxTrendStrategyPolicyEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String policyCode;
    private String policyVersion;
    private String primaryModel;
    private String confirmationModels;
    private Boolean enabled;
    private BigDecimal baseNotionalUsdt;
    private BigDecimal maxNotionalUsdt;
    private BigDecimal scoreThreshold;
    private Integer boxWindowBars;
    private String updatedBy;
    private String changeNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
