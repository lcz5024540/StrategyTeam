package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletMarketMonitoringSummaryView {
    private Date generatedAt;
    private Integer lookbackDays;
    private Integer trendDays;
    private Integer monitoredMarketCount;
    private Integer activeMarketCount;
    private Integer effectiveMarketCount;
    private Integer concentratedMarketCount;
    private Integer extremeConcentratedMarketCount;
    private Integer openPositionMarketCount;
    private Integer pendingTaskMarketCount;
    private Integer signalDrivenMarketCount;
    private BigDecimal concentrationCoverageRate;
    @Builder.Default
    private List<SmartWalletMarketMonitorTrendPointView> trend = new ArrayList<>();
    @Builder.Default
    private List<SmartWalletMarketMonitorItemView> items = new ArrayList<>();
}
