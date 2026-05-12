package com.metaverse.coin.strategy.web.model.smartwallet;

public record SmartWalletBlockerStatView(
        String blockerCategory,
        Integer taskCount,
        Integer executableCount,
        Integer blockedCount
) {
}
