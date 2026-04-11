package com.metaverse.coin.strategy.job.risk.application;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.BaselineModelConfigView;
import com.metaverse.coin.strategy.job.smartwallet.model.UpdateBaselineModelConfigRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BaselineModelConfigApplicationService {

    private final SmartWalletProperties properties;

    public BaselineModelConfigApplicationService(SmartWalletProperties properties) {
        this.properties = properties;
    }

    public BaselineModelConfigView current() {
        SmartWalletProperties.BaselineModel baseline = properties.getBaselineModel();
        SmartWalletProperties.ModelCalibration calibration = properties.getModelCalibration();
        return BaselineModelConfigView.builder()
                .grossEdgePriorWeight(baseline.getGrossEdgePriorWeight())
                .followProbability(BaselineModelConfigView.FollowProbabilityView.builder()
                        .intercept(baseline.getFollowProbability().getIntercept())
                        .walletScoreWeight(baseline.getFollowProbability().getWalletScoreWeight())
                        .consensusScoreWeight(baseline.getFollowProbability().getConsensusScoreWeight())
                        .positiveRateWeight(baseline.getFollowProbability().getPositiveRateWeight())
                        .triggerSizeWeight(baseline.getFollowProbability().getTriggerSizeWeight())
                        .distinctWalletWeight(baseline.getFollowProbability().getDistinctWalletWeight())
                        .imbalanceWeight(baseline.getFollowProbability().getImbalanceWeight())
                        .spreadPenaltyWeight(baseline.getFollowProbability().getSpreadPenaltyWeight())
                        .volatilityPenaltyWeight(baseline.getFollowProbability().getVolatilityPenaltyWeight())
                        .triggerSizeCapUsdc(baseline.getFollowProbability().getTriggerSizeCapUsdc())
                        .distinctWalletCap(baseline.getFollowProbability().getDistinctWalletCap())
                        .spreadPenaltyScaleBps(baseline.getFollowProbability().getSpreadPenaltyScaleBps())
                        .volatilityPenaltyScale(baseline.getFollowProbability().getVolatilityPenaltyScale())
                        .build())
                .slippage(BaselineModelConfigView.SlippageView.builder()
                        .spreadCostWeight(baseline.getSlippage().getSpreadCostWeight())
                        .impactCostWeight(baseline.getSlippage().getImpactCostWeight())
                        .volatilityCostWeight(baseline.getSlippage().getVolatilityCostWeight())
                        .build())
                .slippageSpike(BaselineModelConfigView.SlippageSpikeView.builder()
                        .intercept(baseline.getSlippageSpike().getIntercept())
                        .spreadRiskWeight(baseline.getSlippageSpike().getSpreadRiskWeight())
                        .volatilityRiskWeight(baseline.getSlippageSpike().getVolatilityRiskWeight())
                        .sizeDepthRiskWeight(baseline.getSlippageSpike().getSizeDepthRiskWeight())
                        .historicalSpikeWeight(baseline.getSlippageSpike().getHistoricalSpikeWeight())
                        .spreadRiskScaleBps(baseline.getSlippageSpike().getSpreadRiskScaleBps())
                        .volatilityRiskScale(baseline.getSlippageSpike().getVolatilityRiskScale())
                        .spikeThresholdBps(baseline.getSlippageSpike().getSpikeThresholdBps())
                        .build())
                .calibration(BaselineModelConfigView.CalibrationView.builder()
                        .followProbabilityScale(calibration.getFollowProbabilityScale())
                        .followProbabilityBias(calibration.getFollowProbabilityBias())
                        .slippageSpikeScale(calibration.getSlippageSpikeScale())
                        .slippageSpikeBias(calibration.getSlippageSpikeBias())
                        .build())
                .build();
    }

    public BaselineModelConfigView update(UpdateBaselineModelConfigRequest request) {
        if (request == null) {
            return current();
        }
        SmartWalletProperties.BaselineModel baseline = properties.getBaselineModel();
        SmartWalletProperties.ModelCalibration calibration = properties.getModelCalibration();
        baseline.setGrossEdgePriorWeight(coalesce(request.getGrossEdgePriorWeight(), baseline.getGrossEdgePriorWeight()));
        if (request.getFollowProbability() != null) {
            SmartWalletProperties.BaselineModel.FollowProbability current = baseline.getFollowProbability();
            UpdateBaselineModelConfigRequest.FollowProbability update = request.getFollowProbability();
            current.setIntercept(coalesce(update.getIntercept(), current.getIntercept()));
            current.setWalletScoreWeight(coalesce(update.getWalletScoreWeight(), current.getWalletScoreWeight()));
            current.setConsensusScoreWeight(coalesce(update.getConsensusScoreWeight(), current.getConsensusScoreWeight()));
            current.setPositiveRateWeight(coalesce(update.getPositiveRateWeight(), current.getPositiveRateWeight()));
            current.setTriggerSizeWeight(coalesce(update.getTriggerSizeWeight(), current.getTriggerSizeWeight()));
            current.setDistinctWalletWeight(coalesce(update.getDistinctWalletWeight(), current.getDistinctWalletWeight()));
            current.setImbalanceWeight(coalesce(update.getImbalanceWeight(), current.getImbalanceWeight()));
            current.setSpreadPenaltyWeight(coalesce(update.getSpreadPenaltyWeight(), current.getSpreadPenaltyWeight()));
            current.setVolatilityPenaltyWeight(coalesce(update.getVolatilityPenaltyWeight(), current.getVolatilityPenaltyWeight()));
            current.setTriggerSizeCapUsdc(coalesce(update.getTriggerSizeCapUsdc(), current.getTriggerSizeCapUsdc()));
            current.setDistinctWalletCap(coalesce(update.getDistinctWalletCap(), current.getDistinctWalletCap()));
            current.setSpreadPenaltyScaleBps(coalesce(update.getSpreadPenaltyScaleBps(), current.getSpreadPenaltyScaleBps()));
            current.setVolatilityPenaltyScale(coalesce(update.getVolatilityPenaltyScale(), current.getVolatilityPenaltyScale()));
        }
        if (request.getSlippage() != null) {
            SmartWalletProperties.BaselineModel.Slippage current = baseline.getSlippage();
            UpdateBaselineModelConfigRequest.Slippage update = request.getSlippage();
            current.setSpreadCostWeight(coalesce(update.getSpreadCostWeight(), current.getSpreadCostWeight()));
            current.setImpactCostWeight(coalesce(update.getImpactCostWeight(), current.getImpactCostWeight()));
            current.setVolatilityCostWeight(coalesce(update.getVolatilityCostWeight(), current.getVolatilityCostWeight()));
        }
        if (request.getSlippageSpike() != null) {
            SmartWalletProperties.BaselineModel.SlippageSpike current = baseline.getSlippageSpike();
            UpdateBaselineModelConfigRequest.SlippageSpike update = request.getSlippageSpike();
            current.setIntercept(coalesce(update.getIntercept(), current.getIntercept()));
            current.setSpreadRiskWeight(coalesce(update.getSpreadRiskWeight(), current.getSpreadRiskWeight()));
            current.setVolatilityRiskWeight(coalesce(update.getVolatilityRiskWeight(), current.getVolatilityRiskWeight()));
            current.setSizeDepthRiskWeight(coalesce(update.getSizeDepthRiskWeight(), current.getSizeDepthRiskWeight()));
            current.setHistoricalSpikeWeight(coalesce(update.getHistoricalSpikeWeight(), current.getHistoricalSpikeWeight()));
            current.setSpreadRiskScaleBps(coalesce(update.getSpreadRiskScaleBps(), current.getSpreadRiskScaleBps()));
            current.setVolatilityRiskScale(coalesce(update.getVolatilityRiskScale(), current.getVolatilityRiskScale()));
            current.setSpikeThresholdBps(coalesce(update.getSpikeThresholdBps(), current.getSpikeThresholdBps()));
        }
        if (request.getCalibration() != null) {
            UpdateBaselineModelConfigRequest.Calibration update = request.getCalibration();
            calibration.setFollowProbabilityScale(coalesce(update.getFollowProbabilityScale(), calibration.getFollowProbabilityScale()));
            calibration.setFollowProbabilityBias(coalesce(update.getFollowProbabilityBias(), calibration.getFollowProbabilityBias()));
            calibration.setSlippageSpikeScale(coalesce(update.getSlippageSpikeScale(), calibration.getSlippageSpikeScale()));
            calibration.setSlippageSpikeBias(coalesce(update.getSlippageSpikeBias(), calibration.getSlippageSpikeBias()));
        }
        return current();
    }

    private BigDecimal coalesce(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }
}
