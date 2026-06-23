package com.metaverse.coin.strategy.web.model.smartwallet;

public record SettlementRetryReportView(int total, int active, int resolved,
    int failedPermanently, int abandoned) {}
