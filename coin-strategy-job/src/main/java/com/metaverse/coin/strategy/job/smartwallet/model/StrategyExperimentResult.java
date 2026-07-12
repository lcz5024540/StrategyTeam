package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class StrategyExperimentResult {
    private String experimentId;
    private String status;
    private String resultHash;
    private Date generatedAt;
    private StrategyExperimentSpec spec;
    @Builder.Default
    private List<String> gateFailures = new ArrayList<>();
    @Builder.Default
    private List<StrategyExperimentFoldResult> folds = new ArrayList<>();
}
