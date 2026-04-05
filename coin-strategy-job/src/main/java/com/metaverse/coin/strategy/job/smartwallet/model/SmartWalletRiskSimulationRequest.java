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
    private BigDecimal minConsensusScore;
    private Boolean enableSingleWalletOverride;
    private BigDecimal singleWalletOverrideMinWalletScore;
    private BigDecimal singleWalletOverrideMinWinRate;
    private Integer singleWalletOverrideMinTrades;
    private BigDecimal singleWalletOverrideMinTriggerSizeUsdc;
    private BigDecimal singleWalletOverrideMinRecommendedStakeUsdc;
}
