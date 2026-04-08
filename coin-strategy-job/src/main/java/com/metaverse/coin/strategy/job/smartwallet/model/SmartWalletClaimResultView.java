package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SmartWalletClaimResultView {
    private Long positionId;
    private String executionAddress;
    private String previousStatus;
    private String currentStatus;
    private boolean externalClaimRequired;
    private boolean externalClaimConfirmed;
    private BigDecimal settlementProceedsUsdc;
    private String message;
}
