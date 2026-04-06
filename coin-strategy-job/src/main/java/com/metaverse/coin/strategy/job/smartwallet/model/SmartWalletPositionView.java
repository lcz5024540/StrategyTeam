package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class SmartWalletPositionView {
    private Long id;
    private String executionAddress;
    private String marketId;
    private String tokenId;
    private String question;
    private String outcome;
    private Long marketEndAt;
    private String marketEndAtBjt;
    private String netSide;
    private BigDecimal quantity;
    private BigDecimal averageEntryPrice;
    private BigDecimal costBasisUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal lastMarkPrice;
    private String status;
    private Date openedAt;
    private Date lastFilledAt;
    private Date closedAt;
}
