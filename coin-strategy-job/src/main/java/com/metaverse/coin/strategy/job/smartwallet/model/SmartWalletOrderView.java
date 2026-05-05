package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class SmartWalletOrderView {
    private Long id;
    private Long followTaskId;
    private String walletAddress;
    private String executionMode;
    private String executionAddress;
    private String externalOrderId;
    private Long sourceEventId;
    private String externalTradeId;
    private String marketId;
    private String marketSlug;
    private String marketUrl;
    private String externalMarketUrl;
    private String internalMarketUrl;
    private String marketLinkStatus;
    private String tokenId;
    private String question;
    private String outcome;
    private Long marketEndAt;
    private String marketEndAtBjt;
    private String marketLifecycle;
    private Boolean activeMarket;
    private Boolean closed;
    private Boolean acceptingOrders;
    private Boolean metadataMissing;
    private Boolean manualReviewRequired;
    private String abnormalMarketReason;
    private String normalizedCategory;
    private String sourceCategory;
    private String side;
    private String orderType;
    private Boolean earlyExit;
    private String exitReason;
    private String status;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal notionalUsdc;
    private BigDecimal entryQuantity;
    private BigDecimal entryAvgPrice;
    private BigDecimal entryCostUsdc;
    private BigDecimal roiBaseUsdc;
    private String roiAnomalyReason;
    private BigDecimal costBasisUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal tradingFeeUsdc;
    private BigDecimal feePol;
    private BigDecimal gasFeeUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal estimatedFeeUsdc;
    private BigDecimal estimatedSlippageUsdc;
    private BigDecimal followProbability;
    private BigDecimal calibratedFollowProbability;
    private BigDecimal slippageSpikeProbability;
    private Integer estimateSampleSize;
    private String errorMessage;
    private Date placedAt;
    private Date filledAt;
}
