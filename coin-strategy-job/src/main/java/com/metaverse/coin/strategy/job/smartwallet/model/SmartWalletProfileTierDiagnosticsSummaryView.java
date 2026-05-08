package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.util.Date;
import java.util.Map;

@Builder
public record SmartWalletProfileTierDiagnosticsSummaryView(
        String monitorTier,
        Integer walletCount,
        Integer metricsMissingCount,
        Integer metricsStaleCount,
        Integer eventSyncStaleCount,
        Date earliestMetricsRefreshAt,
        Date latestMetricsRefreshAt,
        Map<String, Integer> hotBlockReasonCounts
) {
}
