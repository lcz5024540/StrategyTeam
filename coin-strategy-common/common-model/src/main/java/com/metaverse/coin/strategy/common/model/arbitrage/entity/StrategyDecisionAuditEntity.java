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
@TableName("strategy_decision_audit")
public class StrategyDecisionAuditEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String experimentId;
    private Long sourceEventId;
    private String lane;
    private Date decisionAt;
    private Long featureSnapshotId;
    private String modelVersion;
    private Boolean accepted;
    private BigDecimal recommendedStakeUsdc;
    private BigDecimal pWin;
    private BigDecimal pWinLowerBound;
    private BigDecimal estimatedNetEvUsdc;
    private String reasonJson;
    private Date createdAt;
}
