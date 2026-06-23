package com.metaverse.coin.strategy.web.model.smartwallet;
public record SignalHealthSummaryView(int lookbackHours, int totalTasks, int pendingTasks, int followedTasks,
    int skippedTasks, int failedTasks, int distinctWallets, int distinctMarkets) {}
