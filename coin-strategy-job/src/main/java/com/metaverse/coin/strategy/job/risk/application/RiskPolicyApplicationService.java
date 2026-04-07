package com.metaverse.coin.strategy.job.risk.application;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
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
                .maxSignalAgeSeconds(risk.getMaxSignalAgeSeconds())
                .minDistinctWallets(risk.getMinDistinctWallets())
                .minConsensusScore(risk.getMinConsensusScore())
                .enableConsensusGate(risk.isEnableConsensusGate())
                .enableSingleWalletOverride(risk.isEnableSingleWalletOverride())
                .singleWalletOverrideMinWalletScore(risk.getSingleWalletOverrideMinWalletScore())
                .singleWalletOverrideMinWinRate(risk.getSingleWalletOverrideMinWinRate())
                .singleWalletOverrideMinTrades(risk.getSingleWalletOverrideMinTrades())
                .singleWalletOverrideMinTriggerSizeUsdc(risk.getSingleWalletOverrideMinTriggerSizeUsdc())
                .singleWalletOverrideMinRecommendedStakeUsdc(risk.getSingleWalletOverrideMinRecommendedStakeUsdc())
                .build();
    }
}
