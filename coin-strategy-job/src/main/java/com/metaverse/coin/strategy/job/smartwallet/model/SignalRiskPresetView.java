package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SignalRiskPresetView {
    private String name;
    private boolean activeDefault;
    private String policyTag;
    private Integer maxSignalAgeSeconds;
    private Integer minDistinctWallets;
    private Integer minDistinctEntities;
    private BigDecimal minConsensusScore;
    private Boolean enableConsensusGate;
    private Boolean qualityWeightedConsensusEnabled;
    private Boolean enableSingleWalletOverride;
    private Boolean enableSingleWalletOverrideCanary;
    private BigDecimal singleWalletOverrideCanaryRate;
    private BigDecimal singleWalletOverrideMinWalletScore;
    private BigDecimal singleWalletOverrideMinWinRate;
    private Integer singleWalletOverrideMinTrades;
    private Integer singleWalletOverrideMinRecentTrades7d;
    private BigDecimal singleWalletOverrideMinRecentWinRate7d;
    private BigDecimal singleWalletOverrideMinReplicabilityScore;
    private BigDecimal singleWalletOverrideMinQualityScore;
    private BigDecimal singleWalletOverrideMinTriggerSizeUsdc;
    private BigDecimal singleWalletOverrideMinRecommendedStakeUsdc;
    private Boolean enableConcentrationGate;
    private String concentrationHighRiskMode;
    private String whaleSignalSourceMode;
    private String exitSignalProviderMode;
}
