package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable evidence for one exit-engine evaluation; it is never updated after insertion. */
@Data
@TableName("htx_trend_exit_decision")
public class HtxTrendExitDecisionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String decisionKey;
    private Long positionId;
    private String runId;
    private Long accountId;
    private String accountLabel;
    private String contractCode;
    private String side;
    private String primaryModel;
    private String engineVersion;
    private String exitPhase;
    private String suggestedAction;
    private String executedAction;
    private String executionBlockReason;
    private BigDecimal referencePrice;
    private BigDecimal protectionPrice;
    private BigDecimal expectedTargetPrice;
    private BigDecimal currentNetPnl;
    private BigDecimal mfeNetPnl;
    private BigDecimal maeNetPnl;
    private BigDecimal givebackRate;
    private BigDecimal maxFavorableR;
    private Long targetRemainingContracts;
    private String evidenceJson;
    private LocalDateTime createdAt;
}
