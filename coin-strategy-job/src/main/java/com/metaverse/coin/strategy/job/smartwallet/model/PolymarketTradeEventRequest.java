package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class PolymarketTradeEventRequest {
    private String walletAddress;
    private String chain = "polygon";
    private String externalTradeId;
    private String marketId;
    private String question;
    private String outcome;
    private String side = "BUY";
    private BigDecimal price = BigDecimal.ZERO;
    private BigDecimal sizeUsdc = BigDecimal.ZERO;
    private Date openedAt;
}
