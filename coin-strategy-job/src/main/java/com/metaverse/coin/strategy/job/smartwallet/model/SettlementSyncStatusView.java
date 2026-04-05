package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class SettlementSyncStatusView {
    private boolean enabled;
    private boolean running;
    private Date lastRunAt;
    private Integer invocationCount;
    private boolean nextRunRefreshesCache;
    private SettlementSyncSummary lastSummary;
}
