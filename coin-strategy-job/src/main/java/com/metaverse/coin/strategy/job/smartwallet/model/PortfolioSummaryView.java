package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class PortfolioSummaryView {
    private long totalTasks;
    private long followedTasks;
    private long skippedTasks;
    private long failedTasks;
    private long pendingTasks;
    private int activePositions;
    private BigDecimal totalDeployedUsdc;
    private BigDecimal averageStakeUsdc;
    private Map<String, Long> statusCounts;
    @Builder.Default
    private List<String> notes = new ArrayList<>();
}

