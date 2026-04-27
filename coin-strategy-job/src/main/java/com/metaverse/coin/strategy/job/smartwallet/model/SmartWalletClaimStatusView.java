package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletClaimStatusView {
    private boolean enabled;
    private boolean running;
    private Date lastRunAt;
    private Integer invocationCount;
    private Integer resolvedUnclaimedCount;
    private Integer dueRetryCount;
    private Integer waitingConfirmationRetryCount;
    private Integer staleResolvedUnclaimedCount;
    private List<String> anomalyNotes;
    private SmartWalletClaimSummary lastSummary;
}
