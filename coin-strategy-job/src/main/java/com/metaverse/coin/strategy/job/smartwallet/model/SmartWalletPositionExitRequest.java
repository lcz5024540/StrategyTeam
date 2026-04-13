package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class SmartWalletPositionExitRequest {
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal feeUsdc;
    private String reason;
}
