package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PolymarketOrderDryRunRequest {
    private String conditionId;
    private String outcome = "YES";
    private String tokenId;
    private BigDecimal stakeUsdc = BigDecimal.ONE;
    private BigDecimal price;
    private String question;
}
