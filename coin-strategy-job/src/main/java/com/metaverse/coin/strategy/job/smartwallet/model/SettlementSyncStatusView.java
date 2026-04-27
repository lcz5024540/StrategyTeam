package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Builder
public class SettlementSyncStatusView {
    private boolean enabled;
    private boolean running;
    private Date lastRunAt;
    private Integer invocationCount;
    private boolean nextRunRefreshesCache;
    private Integer pendingRetryCount;
    private Integer dueRetryCount;
    private Integer oraclePendingPositionCount;
    private Integer staleOraclePendingCount;
    private Integer manualConfirmRetryCount;
    private List<String> anomalyNotes;
    private SettlementSyncSummary lastSummary;
}
