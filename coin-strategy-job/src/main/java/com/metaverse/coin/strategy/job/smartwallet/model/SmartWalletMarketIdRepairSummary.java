package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SmartWalletMarketIdRepairSummary {
    private Integer requestedPositions;
    private Integer scannedPositions;
    private Integer repairedPositions;
    @Builder.Default
    private List<Long> repairedPositionIds = new ArrayList<>();
    @Builder.Default
    private List<String> repairedMarketIds = new ArrayList<>();
    @Builder.Default
    private List<String> notes = new ArrayList<>();
}
