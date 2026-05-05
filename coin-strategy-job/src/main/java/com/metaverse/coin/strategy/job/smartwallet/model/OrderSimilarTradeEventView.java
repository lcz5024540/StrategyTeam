package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
public class OrderSimilarTradeEventView {
    private Long sourceEventId;
    private String walletAddress;
    private String externalTradeId;
    private String marketId;
    private String question;
    private String outcome;
    private String side;
    private BigDecimal price;
    private BigDecimal sizeUsdc;
    private Date openedAt;
}
