package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SignalRiskPresetView {
    private String name;
    private boolean activeDefault;
    private Integer maxSignalAgeSeconds;
    private Integer minDistinctWallets;
    private BigDecimal minConsensusScore;
    private Boolean enableConsensusGate;
    private Boolean enableSingleWalletOverride;
    private BigDecimal singleWalletOverrideMinWalletScore;
    private BigDecimal singleWalletOverrideMinWinRate;
    private Integer singleWalletOverrideMinTrades;
    private BigDecimal singleWalletOverrideMinTriggerSizeUsdc;
    private BigDecimal singleWalletOverrideMinRecommendedStakeUsdc;
}
