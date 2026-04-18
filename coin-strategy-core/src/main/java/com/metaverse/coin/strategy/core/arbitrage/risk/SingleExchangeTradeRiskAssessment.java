package com.metaverse.coin.strategy.core.arbitrage.risk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleExchangeTradeRiskAssessment {

    private boolean passed;
    private String rejectionReason;
    private SingleExchangeRiskSnapshotData snapshotData;
}
