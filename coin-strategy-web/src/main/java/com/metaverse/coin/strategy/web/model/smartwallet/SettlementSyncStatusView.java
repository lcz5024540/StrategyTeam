package com.metaverse.coin.strategy.web.model.smartwallet;

import java.util.Date;

public record SettlementSyncStatusView(int pendingOracleCount, int resolvedUnclaimedCount,
    int recentRunCount, int activeRetryCount, Date lastRunAt) {}
