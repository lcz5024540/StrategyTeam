package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PortfolioAttributionItem {
    private String key;
    private String label;
    private int executionCount;
    private BigDecimal totalStakeUsdc;
    private BigDecimal averageStakeUsdc;
    private BigDecimal shareOfDeployedCapital;
}

