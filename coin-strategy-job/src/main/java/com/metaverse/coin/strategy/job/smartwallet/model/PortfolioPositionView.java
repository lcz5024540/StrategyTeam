package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class PortfolioPositionView {
    private String marketId;
    private String question;
    private String outcome;
    private String side;
    private String lifecycle;
    private int executionCount;
    private BigDecimal totalStakeUsdc;
    private BigDecimal averageTriggerPrice;
    private Date firstExecutedAt;
    private Date latestExecutedAt;
    @Builder.Default
    private List<String> sourceWallets = new ArrayList<>();
}

