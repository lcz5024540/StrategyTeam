package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BaselineModelConfigView {
    private BigDecimal grossEdgePriorWeight;
    private FollowProbabilityView followProbability;
    private SlippageView slippage;
    private SlippageSpikeView slippageSpike;
    private CalibrationView calibration;

    @Data
    @Builder
    public static class FollowProbabilityView {
        private BigDecimal intercept;
        private BigDecimal walletScoreWeight;
        private BigDecimal consensusScoreWeight;
        private BigDecimal positiveRateWeight;
        private BigDecimal triggerSizeWeight;
        private BigDecimal distinctWalletWeight;
        private BigDecimal imbalanceWeight;
        private BigDecimal spreadPenaltyWeight;
        private BigDecimal volatilityPenaltyWeight;
        private BigDecimal triggerSizeCapUsdc;
        private BigDecimal distinctWalletCap;
        private BigDecimal spreadPenaltyScaleBps;
        private BigDecimal volatilityPenaltyScale;
    }

    @Data
    @Builder
    public static class SlippageView {
        private BigDecimal spreadCostWeight;
        private BigDecimal impactCostWeight;
        private BigDecimal volatilityCostWeight;
    }

    @Data
    @Builder
    public static class SlippageSpikeView {
        private BigDecimal intercept;
        private BigDecimal spreadRiskWeight;
        private BigDecimal volatilityRiskWeight;
        private BigDecimal sizeDepthRiskWeight;
        private BigDecimal historicalSpikeWeight;
        private BigDecimal spreadRiskScaleBps;
        private BigDecimal volatilityRiskScale;
        private BigDecimal spikeThresholdBps;
    }

    @Data
    @Builder
    public static class CalibrationView {
        private BigDecimal followProbabilityScale;
        private BigDecimal followProbabilityBias;
        private BigDecimal slippageSpikeScale;
        private BigDecimal slippageSpikeBias;
    }
}
