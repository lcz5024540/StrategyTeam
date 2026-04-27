package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SmartWalletMarketMonitorTrendPointView {
    private String date;
    private Integer monitoredMarketCount;
    private Integer effectiveMarketCount;
    private Integer concentratedMarketCount;
    private Integer extremeConcentratedMarketCount;
    private Integer signalCount;
    private Integer pendingTaskCount;
}
