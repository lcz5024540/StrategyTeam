package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;

public record SmartWalletCapacitySummaryView(
    Integer openPositionCount, Integer openPositionLimit, Integer remainingPositionSlots,
    BigDecimal totalOpenCostUsdc, BigDecimal openCostLimitUsdc,
    BigDecimal remainingCapitalBudgetUsdc, BigDecimal availableCapitalUsdc,
    BigDecimal lockedClaimProceedsUsdc,
    Integer maxHoursToMarketEnd,
    Boolean duplicateOpenPositionBlocked, Boolean duplicatePendingTaskBlocked) {}
