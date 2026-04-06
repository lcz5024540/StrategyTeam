package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class SmartWalletOrderView {
    private Long id;
    private Long followTaskId;
    private String walletAddress;
    private String executionMode;
    private String executionAddress;
    private String externalOrderId;
    private String marketId;
    private String tokenId;
    private String question;
    private String outcome;
    private Long marketEndAt;
    private String marketEndAtBjt;
    private String side;
    private String orderType;
    private String status;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal notionalUsdc;
    private BigDecimal feeUsdc;
    private String errorMessage;
    private Date placedAt;
    private Date filledAt;
}
