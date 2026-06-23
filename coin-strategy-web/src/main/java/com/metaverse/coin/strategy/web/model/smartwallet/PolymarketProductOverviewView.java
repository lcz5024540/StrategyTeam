package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record PolymarketProductOverviewView(
        String mode,
        boolean discoveryEnabled,
        boolean eventSyncEnabled,
        boolean liveExecutionEnabled,
        int profiles,
        int candidateWatchlist,
        int executableWatchlist,
        int warmWatchlist,
        int coldProfiles,
        int watchlist,
        long tradeEvents,
        BigDecimal totalDeployedUsdc,
        int activePositions,
        Map<String, Long> taskStatusCounts,
        ExecutionWalletStatusView executionWallet,
        List<String> notes
) {
}
