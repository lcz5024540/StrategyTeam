package com.metaverse.coin.strategy.web.model.smartwallet;

public record SmartWalletManualAllowlistSummaryView(int total, int active, int expired, int disabled,
    int paperOnly, int liveEnabled) {}
