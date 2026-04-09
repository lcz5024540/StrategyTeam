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
    private String status;
    private String executionMode;
    private String externalOrderId;
    private String errorMessage;
    private Integer consensusWallets;
    private BigDecimal consensusScore;
    private BigDecimal expectedNetEvUsdc;
    private BigDecimal estimatedFeeUsdc;
    private BigDecimal estimatedSlippageUsdc;
    private Integer evSampleSize;
    private Boolean taskExecutable;
    private Boolean overrideApplied;
    private String appliedPreset;
    private Integer blockerCount;
    private String primaryBlockerCategory;
    private List<String> blockerCategories;
    private List<String> riskReasons;
    private Date scheduledAt;
    private Date executedAt;
}
