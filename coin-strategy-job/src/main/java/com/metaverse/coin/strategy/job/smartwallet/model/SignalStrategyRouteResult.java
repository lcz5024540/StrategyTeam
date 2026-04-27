package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SignalStrategyRouteResult {

    private boolean accepted;
    private String routeMode;
    @Builder.Default
    private List<String> enabledStrategies = new ArrayList<>();
    @Builder.Default
    private List<String> matchedStrategies = new ArrayList<>();
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
    @Builder.Default
    private BigDecimal stakeMultiplier = BigDecimal.ONE;
}
