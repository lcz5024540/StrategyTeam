package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class SmartWalletManualAllowlistRequest {

    private String walletAddress;
    private String status;
    private String allowedExecutionModes;
    private BigDecimal stakeMultiplier;
    private Integer maxLiveOrdersPerDay;
    private Integer maxLiveOrdersPerRun;
    private Date expiresAt;
    private String reason;
    private String createdBy;
}
