package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 钱包画像 HOT/WARM/COLD 分层诊断视图。
 *
 * <p>原为 {@code com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileStaleDiagnosticView}。
 * Phase 4.3 下沉到 common-service。</p>
 */
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
