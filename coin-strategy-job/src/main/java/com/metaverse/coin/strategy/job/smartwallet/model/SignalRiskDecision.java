package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SignalRiskDecision {
    private boolean accepted;
    private boolean overrideApplied;
    private BigDecimal recommendedStakeUsdc;
    private BigDecimal expectedNetEvUsdc;
    private BigDecimal estimatedFeeUsdc;
    private BigDecimal estimatedSlippageUsdc;
    private BigDecimal followProbability;
    private BigDecimal calibratedFollowProbability;
    private BigDecimal slippageSpikeProbability;
    private Integer evSampleSize;
    /** 原始参与钱包数（历史兼容，勿直接用于风控决策） */
    private Integer consensusWallets;
    /** 实体去重后的参与实体数（真实共识强度，风控门控使用此字段） */
    private Integer consensusEntities;
    private BigDecimal consensusScore;
    /** 是否检测到同一实体多钱包触发同一信号（告警标志） */
    private boolean hasSuspiciousMultiWallet;
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
}
