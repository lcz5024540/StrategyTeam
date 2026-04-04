package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SignalRiskDecision {
    private boolean accepted;
    private BigDecimal recommendedStakeUsdc;
    private Integer consensusWallets;
    private BigDecimal consensusScore;
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
}
