package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SignalScoreModelResult {
    private BigDecimal signalScore;
    private BigDecimal profitProbability;
    private BigDecimal expectedReturnFactor;
    private BigDecimal liquidityFactor;
    private BigDecimal leaderReliabilityFactor;
    private BigDecimal riskPenalty;
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
}
