package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;

import java.util.List;

@Builder
public record SmartWalletProfileBatchOverrideResultView(
        Integer requestedCount,
        Integer uniqueWalletCount,
        Integer updatedCount,
        Integer missingCount,
        List<String> updatedWalletAddresses,
        List<String> missingWalletAddresses
) {
}
