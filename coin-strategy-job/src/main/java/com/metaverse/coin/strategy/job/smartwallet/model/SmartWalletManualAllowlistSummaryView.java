package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

@Builder
public record SmartWalletManualAllowlistSummaryView(
        Integer total,
        Integer active,
        Integer expired,
        Integer disabled,
        Integer paperOnly,
        Integer liveEnabled
) {
}
