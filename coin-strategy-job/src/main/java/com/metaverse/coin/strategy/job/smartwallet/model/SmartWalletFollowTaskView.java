package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class SmartWalletFollowTaskView {
    private Long id;
    private String walletAddress;
    private String marketId;
    private String question;
    private String outcome;
    private String side;
    private BigDecimal triggerPrice;
    private BigDecimal triggerSizeUsdc;
    private BigDecimal recommendedStakeUsdc;
    private String status;
    private String executionMode;
    private String externalOrderId;
    private String errorMessage;
    private Integer consensusWallets;
    private BigDecimal consensusScore;
    private Date scheduledAt;
    private Date executedAt;
}
