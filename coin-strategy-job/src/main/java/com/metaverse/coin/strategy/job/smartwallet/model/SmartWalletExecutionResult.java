package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SmartWalletExecutionResult {
    private String signalId;
    private ExecutionMode mode;
    private boolean success;
    private String message;
    private String externalOrderId;
    private String executionAddress;
    private String marketId;
    private String tokenId;
    private Integer indexSet;
    private String outcome;
    private String side;
    private String orderType;
    private String status;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal notionalUsdc;
    private BigDecimal feeUsdc;
}
