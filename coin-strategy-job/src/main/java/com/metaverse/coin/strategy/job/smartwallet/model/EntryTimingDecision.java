package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class EntryTimingDecision {
    private boolean accepted;
    private BigDecimal leaderPrice;
    private BigDecimal currentEntryPrice;
    private BigDecimal driftRate;
    private BigDecimal allowedDriftRate;
    private BigDecimal spreadPct;
    private BigDecimal rangePosition1h;
    private BigDecimal marketMove5mRate;
    private String snapshotSource;
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
}
