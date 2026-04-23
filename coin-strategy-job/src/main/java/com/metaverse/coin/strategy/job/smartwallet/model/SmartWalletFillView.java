package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class SmartWalletFillView {
    private Long id;
    private Long orderId;
    private Long followTaskId;
    private String walletAddress;
    private String executionAddress;
    private String marketId;
    private String tokenId;
    private String question;
    private String outcome;
    private String side;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal notionalUsdc;
    private BigDecimal feeUsdc;
    private BigDecimal tradingFeeUsdc;
    private BigDecimal feePol;
    private BigDecimal gasFeeUsdc;
    private BigDecimal realizedPnlUsdc;
    private Date filledAt;
}
