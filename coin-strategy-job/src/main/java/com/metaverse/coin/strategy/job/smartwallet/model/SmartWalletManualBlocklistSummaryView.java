package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

@Builder
public record SmartWalletManualBlocklistSummaryView(
        Integer total,
        Integer active,
        Integer disabled
) {
}
