package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;

@Builder
public record SmartWalletManualBlocklistSummaryView(
        Integer total,
        Integer active,
        Integer disabled
) {
}
