package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class HistoricalPaperSettlementReviewView {
    private Integer scannedPositions;
    private Integer autoClaimedPositions;
    private SettlementSyncSummary settlementSync;
    @Builder.Default
    private List<Long> autoClaimedPositionIds = new ArrayList<>();
    @Builder.Default
    private List<String> notes = new ArrayList<>();
}
