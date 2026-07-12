package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StrategyPromotionEvaluationRequest {
    private String championModelVersion;
    private String challengerModelVersion;
    private Integer settledTradeCount;
    private BigDecimal maxDrawdownReductionRate;
    private BigDecimal rollingWinRateStdReductionRate;
    private Boolean catastrophicSliceRegression;
    private Boolean dataQualityPassed;
    private BigDecimal bootstrapNetEvLowerBoundUsdc;
    private BigDecimal profitFactor;
    private BigDecimal winRateDeltaPercentagePoints;
    private BigDecimal winRateImprovementProbability;
    private BigDecimal challengerToChampionMaxDrawdownRatio;
    private BigDecimal accountingDiscrepancyRate;
    private Boolean labelBacklogDetected;
    private Boolean dailyLossHaltTriggered;
    private Boolean drawdownHaltTriggered;
}
