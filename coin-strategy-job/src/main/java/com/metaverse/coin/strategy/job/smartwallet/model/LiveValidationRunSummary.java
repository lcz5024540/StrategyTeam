package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class LiveValidationRunSummary {
    private boolean enabled;
    private String preset;
    private Integer scannedCandidates;
    private Integer executedCount;
    private Integer skippedCount;
    @Builder.Default
    private List<String> notes = new ArrayList<>();
}
