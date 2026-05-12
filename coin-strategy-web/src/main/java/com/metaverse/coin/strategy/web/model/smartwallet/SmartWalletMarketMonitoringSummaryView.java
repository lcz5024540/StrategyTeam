package com.metaverse.coin.strategy.web.model.smartwallet;

import java.util.Date;
import java.util.List;

public record SmartWalletMarketMonitoringSummaryView(
    Date generatedAt, Integer lookbackDays, Integer trendDays,
    Integer monitoredMarketCount, Integer activeMarketCount, Integer effectiveMarketCount,
    Integer concentratedMarketCount, Integer extremeConcentratedMarketCount,
    Integer openPositionMarketCount, Integer pendingTaskMarketCount,
    Integer signalDrivenMarketCount, java.math.BigDecimal concentrationCoverageRate,
    List<SmartWalletMarketMonitorTrendPointView> trend,
    List<SmartWalletMarketMonitorItemView> items) {}
