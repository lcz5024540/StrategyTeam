package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletFollowTaskView {
    private Long id;
    private String walletAddress;
    private String marketId;
    private String question;
    private String outcome;
    private Long marketEndAt;
    private String marketEndAtBjt;
    private String side;
    private BigDecimal triggerPrice;
    private BigDecimal triggerSizeUsdc;
    private BigDecimal recommendedStakeUsdc;
    private String marketType;
    private String entryLane;
    private String exitIntent;
    private String status;
    private String executionMode;
    private String externalOrderId;
    private String errorMessage;
    private String primarySkipReason;
    private List<String> skipReasonTags;
    private String wouldHaveSkippedReason;
    private Integer consensusWallets;
    private BigDecimal consensusScore;
    private BigDecimal estimatedFeeUsdc;
    private BigDecimal estimatedSlippageUsdc;
    private BigDecimal estimatedNetProfitabilityUsdc;
    private BigDecimal followProbability;
    private BigDecimal calibratedFollowProbability;
    private BigDecimal slippageSpikeProbability;
    private Integer estimateSampleSize;
    private BigDecimal signalScore;
    private BigDecimal signalScoreLiquidityFactor;
    private BigDecimal signalScoreRiskPenalty;
    private BigDecimal signalScoreLeaderReliabilityFactor;
    private Boolean taskExecutable;
    private Boolean overrideApplied;
    private Boolean manualAllowlistApplied;
    private String manualAllowlistReason;
    private BigDecimal stakeMultiplierApplied;
    private String appliedPreset;
    private Integer blockerCount;
    private String primaryBlockerCategory;
    private List<String> blockerCategories;
    private List<String> riskReasons;
    private Date scheduledAt;
    private Date executedAt;
}
