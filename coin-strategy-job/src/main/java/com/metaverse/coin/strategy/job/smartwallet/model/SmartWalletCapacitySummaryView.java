package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SmartWalletCapacitySummaryView {
    private Integer openPositionCount;
    private Integer openPositionLimit;
    private Integer remainingPositionSlots;
    private BigDecimal totalOpenCostUsdc;
    private BigDecimal openCostLimitUsdc;
    private BigDecimal remainingCapitalBudgetUsdc;
    private Integer maxHoursToMarketEnd;
    private Boolean duplicateOpenPositionBlocked;
    private Boolean duplicatePendingTaskBlocked;
}
