package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SmartWalletRiskSimulationRequest {
    private Integer taskLimit = 100;
    private Integer resultLimit = 20;
    private Integer maxBlockers = 3;
    private Boolean includeExecutable = Boolean.TRUE;
    private Boolean allowSellSignal;
    private Integer maxSignalAgeSeconds;
    private BigDecimal minTriggerSizeUsdc;
    private BigDecimal minFollowPrice;
    private BigDecimal maxFollowPrice;
    private Boolean enableConsensusGate;
    private Integer minDistinctWallets;
    private Integer minDistinctEntities;
    private BigDecimal minConsensusScore;
    private Boolean qualityWeightedConsensusEnabled;
    private String policyTag;
    private BigDecimal consensusEntityBaseWeight;
    private BigDecimal consensusBehaviorWeight;
    private BigDecimal consensusRecencyWeight;
    private BigDecimal consensusLeaderFollowerWeight;
    private BigDecimal consensusIndependenceWeight;
    private BigDecimal consensusRecentConsistencyWeight;
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
    private BigDecimal concentrationHighRiskDownsizeRate;
    private String whaleSignalSourceMode;
    private Boolean kellyStakeEnabled;
    private BigDecimal kellyFraction;
    private String exitSignalProviderMode;
}
