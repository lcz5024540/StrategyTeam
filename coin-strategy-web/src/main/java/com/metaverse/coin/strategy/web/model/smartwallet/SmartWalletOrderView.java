package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletOrderView(
        Long id,
        Long followTaskId,
        String walletAddress,
        String executionMode,
        String executionAddress,
        String externalOrderId,
        Long sourceEventId,
        String externalTradeId,
        String marketId,
        String marketSlug,
        String marketUrl,
        String externalMarketUrl,
        String internalMarketUrl,
        String marketLinkStatus,
        String tokenId,
        String question,
        String outcome,
        Long marketEndAt,
        String marketEndAtBjt,
        String marketLifecycle,
        Boolean activeMarket,
        Boolean closed,
        Boolean acceptingOrders,
        Boolean metadataMissing,
        Boolean manualReviewRequired,
        String abnormalMarketReason,
        String normalizedCategory,
        String sourceCategory,
        String side,
        String orderType,
        Boolean earlyExit,
        String exitReason,
        String status,
        BigDecimal price,
        BigDecimal size,
        BigDecimal notionalUsdc,
        BigDecimal entryQuantity,
        BigDecimal entryAvgPrice,
        BigDecimal entryCostUsdc,
        BigDecimal roiBaseUsdc,
        String roiAnomalyReason,
        BigDecimal costBasisUsdc,
        BigDecimal feeUsdc,
        BigDecimal tradingFeeUsdc,
        BigDecimal feePol,
        BigDecimal gasFeeUsdc,
        BigDecimal realizedPnlUsdc,
        BigDecimal estimatedFeeUsdc,
        BigDecimal estimatedSlippageUsdc,
        BigDecimal followProbability,
        BigDecimal calibratedFollowProbability,
        BigDecimal slippageSpikeProbability,
        Integer estimateSampleSize,
        String errorMessage,
        Date placedAt,
        Date filledAt
) {

    public Long getId() { return id; }
    public Long getFollowTaskId() { return followTaskId; }
    public String getExecutionMode() { return executionMode; }
    public String getQuestion() { return question; }
    public String getMarketSlug() { return marketSlug; }
    public String getOutcome() { return outcome; }
    public String getSide() { return side; }
    public String getOrderType() { return orderType; }
    public Boolean getEarlyExit() { return earlyExit; }
    public String getExitReason() { return exitReason; }
    public String getStatus() { return status; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getSize() { return size; }
    public BigDecimal getNotionalUsdc() { return notionalUsdc; }
    public BigDecimal getCostBasisUsdc() { return costBasisUsdc; }
    public BigDecimal getRealizedPnlUsdc() { return realizedPnlUsdc; }
    public BigDecimal getEstimatedFeeUsdc() { return estimatedFeeUsdc; }
    public BigDecimal getEstimatedSlippageUsdc() { return estimatedSlippageUsdc; }
    public BigDecimal getFollowProbability() { return followProbability; }
    public BigDecimal getCalibratedFollowProbability() { return calibratedFollowProbability; }
    public BigDecimal getSlippageSpikeProbability() { return slippageSpikeProbability; }
    public Integer getEstimateSampleSize() { return estimateSampleSize; }
    public String getMarketEndAtBjt() { return marketEndAtBjt; }
    public Date getPlacedAt() { return placedAt; }
}
