package com.metaverse.coin.strategy.job.risk.application;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskPresetCatalogView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskPresetView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RiskPolicyApplicationService {

    private final SmartWalletProperties properties;

    public RiskPolicyApplicationService(SmartWalletProperties properties) {
        this.properties = properties;
    }

    public SignalRiskPresetCatalogView presets() {
        List<SignalRiskPresetView> presets = new ArrayList<>();
        properties.getSignalRiskPresets().forEach((name, risk) ->
                presets.add(toView(name, risk, false)));
        return SignalRiskPresetCatalogView.builder()
                .activeDefault(toView("default", properties.getSignalRisk(), true))
                .presets(presets)
                .build();
    }

    private SignalRiskPresetView toView(String name, SmartWalletProperties.SignalRisk risk, boolean activeDefault) {
        return SignalRiskPresetView.builder()
                .name(name)
                .activeDefault(activeDefault)
                .policyTag(risk.getPolicyTag())
                .maxSignalAgeSeconds(risk.getMaxSignalAgeSeconds())
                .minDistinctWallets(risk.getMinDistinctWallets())
                .minDistinctEntities(risk.getMinDistinctEntities())
                .minConsensusScore(risk.getMinConsensusScore())
                .enableConsensusGate(risk.isEnableConsensusGate())
                .qualityWeightedConsensusEnabled(risk.isQualityWeightedConsensusEnabled())
                .enableSingleWalletOverride(risk.isEnableSingleWalletOverride())
                .enableSingleWalletOverrideCanary(risk.isEnableSingleWalletOverrideCanary())
                .singleWalletOverrideCanaryRate(risk.getSingleWalletOverrideCanaryRate())
                .singleWalletOverrideMinWalletScore(risk.getSingleWalletOverrideMinWalletScore())
                .singleWalletOverrideMinWinRate(risk.getSingleWalletOverrideMinWinRate())
                .singleWalletOverrideMinTrades(risk.getSingleWalletOverrideMinTrades())
                .singleWalletOverrideMinRecentTrades7d(risk.getSingleWalletOverrideMinRecentTrades7d())
                .singleWalletOverrideMinRecentWinRate7d(risk.getSingleWalletOverrideMinRecentWinRate7d())
                .singleWalletOverrideMinReplicabilityScore(risk.getSingleWalletOverrideMinReplicabilityScore())
                .singleWalletOverrideMinQualityScore(risk.getSingleWalletOverrideMinQualityScore())
                .singleWalletOverrideMinTriggerSizeUsdc(risk.getSingleWalletOverrideMinTriggerSizeUsdc())
                .singleWalletOverrideMinRecommendedStakeUsdc(risk.getSingleWalletOverrideMinRecommendedStakeUsdc())
                .enableConcentrationGate(risk.isEnableConcentrationGate())
                .concentrationHighRiskMode(risk.getConcentrationHighRiskMode())
                .whaleSignalSourceMode(risk.getWhaleSignalSourceMode())
                .exitSignalProviderMode(risk.getExitSignalProviderMode())
                .build();
    }
}
