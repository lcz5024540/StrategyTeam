package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Accessors(chain = true)
@TableName("strategy_promotion_state")
public class StrategyPromotionStateEntity {
    @TableId(type = IdType.INPUT)
    private String strategyKey;
    private String championModelVersion;
    private String challengerModelVersion;
    private String stage;
    private BigDecimal riskBudgetPct;
    private Date stageStartedAt;
    private Integer settledTradeCount;
    private String gateStatusJson;
    private String lastRollbackReason;
    private Date updatedAt;
}
