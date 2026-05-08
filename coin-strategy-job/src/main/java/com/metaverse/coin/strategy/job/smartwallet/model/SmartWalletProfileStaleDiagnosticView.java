package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

@Builder
public record SmartWalletProfileStaleDiagnosticView(
        String walletAddress,
        String alias,
        Boolean qualified,
        Boolean watchEnabled,
        Boolean manualWatchEnabled,
        Boolean manualAllowlistActive,
        String monitorTier,
        String monitorReason,
        BigDecimal winRate,
        BigDecimal walletScore,
        Integer totalTrades,
        BigDecimal realizedPnlUsdc,
        BigDecimal maxDrawdownRate,
        Date lastMetricsRefreshAt,
        Date lastEventSyncAt,
        Date lastTradeAt,
        Date lastDiscoveredAt,
        Boolean metricsStale,
        Boolean eventSyncStale,
        String tierDecisionReason,
        String firstHotBlockReason
) {
}
