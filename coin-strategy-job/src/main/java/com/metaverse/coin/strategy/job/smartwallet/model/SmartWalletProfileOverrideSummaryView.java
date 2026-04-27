package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.util.Map;

@Builder
public record SmartWalletProfileOverrideSummaryView(
        Integer totalOverrides,
        Integer observeOnlyOverrides,
        Integer forceExecutableOverrides,
        Integer exitLaneOverrides,
        Integer noteOnlyOverrides,
        Integer updatedLast24h,
        Map<String, Integer> exitLaneCounts
) {
}
