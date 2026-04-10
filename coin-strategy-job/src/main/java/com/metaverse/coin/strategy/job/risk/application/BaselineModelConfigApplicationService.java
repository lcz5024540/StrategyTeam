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
            SmartWalletProperties.BaselineModel.FollowProbability fp = baseline.getFollowProbability();
            UpdateBaselineModelConfigRequest.FollowProbability incoming = request.getFollowProbability();
            fp.setIntercept(coalesce(incoming.getIntercept(), fp.getIntercept()));
            fp.setWalletScoreWeight(coalesce(incoming.getWalletScoreWeight(), fp.getWalletScoreWeight()));
            fp.setConsensusScoreWeight(coalesce(incoming.getConsensusScoreWeight(), fp.getConsensusScoreWeight()));
            fp.setPositiveRateWeight(coalesce(incoming.getPositiveRateWeight(), fp.getPositiveRateWeight()));
            fp.setTriggerSizeWeight(coalesce(incoming.getTriggerSizeWeight(), fp.getTriggerSizeWeight()));
            fp.setDistinctWalletWeight(coalesce(incoming.getDistinctWalletWeight(), fp.getDistinctWalletWeight()));
            fp.setImbalanceWeight(coalesce(incoming.getImbalanceWeight(), fp.getImbalanceWeight()));
            fp.setSpreadPenaltyWeight(coalesce(incoming.getSpreadPenaltyWeight(), fp.getSpreadPenaltyWeight()));
            fp.setVolatilityPenaltyWeight(coalesce(incoming.getVolatilityPenaltyWeight(), fp.getVolatilityPenaltyWeight()));
            fp.setTriggerSizeCapUsdc(coalesce(incoming.getTriggerSizeCapUsdc(), fp.getTriggerSizeCapUsdc()));
            fp.setDistinctWalletCap(coalesce(incoming.getDistinctWalletCap(), fp.getDistinctWalletCap()));
            fp.setSpreadPenaltyScaleBps(coalesce(incoming.getSpreadPenaltyScaleBps(), fp.getSpreadPenaltyScaleBps()));
            fp.setVolatilityPenaltyScale(coalesce(incoming.getVolatilityPenaltyScale(), fp.getVolatilityPenaltyScale()));
        }
        if (request.getSlippage() != null) {
            SmartWalletProperties.BaselineModel.Slippage slippage = baseline.getSlippage();
            UpdateBaselineModelConfigRequest.Slippage incoming = request.getSlippage();
            slippage.setSpreadCostWeight(coalesce(incoming.getSpreadCostWeight(), slippage.getSpreadCostWeight()));
            slippage.setImpactCostWeight(coalesce(incoming.getImpactCostWeight(), slippage.getImpactCostWeight()));
            slippage.setVolatilityCostWeight(coalesce(incoming.getVolatilityCostWeight(), slippage.getVolatilityCostWeight()));
        }
        if (request.getSlippageSpike() != null) {
            SmartWalletProperties.BaselineModel.SlippageSpike spike = baseline.getSlippageSpike();
            UpdateBaselineModelConfigRequest.SlippageSpike incoming = request.getSlippageSpike();
            spike.setIntercept(coalesce(incoming.getIntercept(), spike.getIntercept()));
            spike.setSpreadRiskWeight(coalesce(incoming.getSpreadRiskWeight(), spike.getSpreadRiskWeight()));
            spike.setVolatilityRiskWeight(coalesce(incoming.getVolatilityRiskWeight(), spike.getVolatilityRiskWeight()));
            spike.setSizeDepthRiskWeight(coalesce(incoming.getSizeDepthRiskWeight(), spike.getSizeDepthRiskWeight()));
            spike.setHistoricalSpikeWeight(coalesce(incoming.getHistoricalSpikeWeight(), spike.getHistoricalSpikeWeight()));
            spike.setSpreadRiskScaleBps(coalesce(incoming.getSpreadRiskScaleBps(), spike.getSpreadRiskScaleBps()));
            spike.setVolatilityRiskScale(coalesce(incoming.getVolatilityRiskScale(), spike.getVolatilityRiskScale()));
            spike.setSpikeThresholdBps(coalesce(incoming.getSpikeThresholdBps(), spike.getSpikeThresholdBps()));
        }
        if (request.getCalibration() != null) {
            UpdateBaselineModelConfigRequest.Calibration incoming = request.getCalibration();
            calibration.setFollowProbabilityScale(coalesce(incoming.getFollowProbabilityScale(), calibration.getFollowProbabilityScale()));
            calibration.setFollowProbabilityBias(coalesce(incoming.getFollowProbabilityBias(), calibration.getFollowProbabilityBias()));
            calibration.setSlippageSpikeScale(coalesce(incoming.getSlippageSpikeScale(), calibration.getSlippageSpikeScale()));
            calibration.setSlippageSpikeBias(coalesce(incoming.getSlippageSpikeBias(), calibration.getSlippageSpikeBias()));
        }
        return current();
    }

    private BigDecimal coalesce(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }
}
