package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SmartWalletExecutionResult {
    private String signalId;
    private ExecutionMode mode;
    private boolean success;
    private String message;
    private String externalOrderId;
}
