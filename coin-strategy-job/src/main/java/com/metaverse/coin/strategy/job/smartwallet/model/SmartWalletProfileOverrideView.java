package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.util.Date;

@Builder
public record SmartWalletProfileOverrideView(
        String walletAddress,
        String alias,
        Boolean qualified,
        Boolean watchEnabled,
        Boolean manualWatchEnabled,
        String manualRecommendedExitLane,
        String manualOverrideNote,
        Date manualOverrideUpdatedAt
) {
}
