package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class UpsertMarketRequest {
    private String marketId;
    private String question;
    private String outcome = "YES";
    private BigDecimal price = BigDecimal.ZERO;
    private BigDecimal conviction = BigDecimal.ZERO;
    private BigDecimal liquidityUsdc = BigDecimal.ZERO;
    private String category;
    private List<String> tags = new ArrayList<>();
}
