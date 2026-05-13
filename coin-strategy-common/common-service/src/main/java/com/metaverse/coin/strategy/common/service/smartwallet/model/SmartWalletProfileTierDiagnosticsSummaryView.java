package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;

import java.util.Date;
import java.util.Map;

/**
 * HOT/WARM/COLD 分层诊断汇总视图。
 *
 * <p>原为 {@code com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileTierDiagnosticsSummaryView}。
 * Phase 4.3 下沉到 common-service。</p>
 */
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
