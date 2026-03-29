package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class PredictionMarketOpportunity {
    private String marketId;
    private String question;
    private String outcome;
    private BigDecimal price;
    private BigDecimal conviction;
    private BigDecimal liquidityUsdc;
    private String category;
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
