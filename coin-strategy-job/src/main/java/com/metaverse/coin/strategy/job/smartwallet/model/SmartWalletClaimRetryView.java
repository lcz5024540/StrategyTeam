package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class SmartWalletClaimRetryView {
    private Long id;
    private Long positionId;
    private String executionAddress;
    private String marketId;
    private String question;
    private Long latestRunId;
    private Integer failureCount;
    private String lastErrorMessage;
    private Date lastFailedAt;
    private Date nextRetryAt;
    private String status;
    private String txHash;
}
