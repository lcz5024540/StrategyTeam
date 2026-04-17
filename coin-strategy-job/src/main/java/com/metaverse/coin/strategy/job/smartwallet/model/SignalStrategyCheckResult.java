package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SignalStrategyCheckResult {

    private String strategyKey;
    private boolean accepted;
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
}
