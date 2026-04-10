package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateBaselineModelConfigRequest {
    private BigDecimal grossEdgePriorWeight;
    private FollowProbability followProbability;
    private Slippage slippage;
    private SlippageSpike slippageSpike;
    private Calibration calibration;

    @Data
    public static class FollowProbability {
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
    public static class Slippage {
        private BigDecimal spreadCostWeight;
        private BigDecimal impactCostWeight;
        private BigDecimal volatilityCostWeight;
    }

    @Data
    public static class SlippageSpike {
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
    public static class Calibration {
        private BigDecimal followProbabilityScale;
        private BigDecimal followProbabilityBias;
        private BigDecimal slippageSpikeScale;
        private BigDecimal slippageSpikeBias;
    }
}
