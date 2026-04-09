package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class SmartWalletClaimRetryReportView {
    private Integer totalItems;
    private Integer pendingItems;
    private Integer retryingItems;
    private Integer waitingConfirmationItems;
    private Integer resolvedItems;
    private Integer abandonedItems;
    private Integer dueItems;
    private Integer maxFailureCount;
    private Date latestFailedAt;
}
