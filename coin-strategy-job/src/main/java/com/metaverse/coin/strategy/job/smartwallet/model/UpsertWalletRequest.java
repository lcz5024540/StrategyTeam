package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class UpsertWalletRequest {
    private String address;
    private String chain = "polygon";
    private String alias;
    private BigDecimal winRate = BigDecimal.ZERO;
    private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
    private Integer totalTrades = 0;
    private BigDecimal averageSizeUsdc = BigDecimal.ZERO;
    private BigDecimal maxDrawdownRate = BigDecimal.ZERO;
    private String preferredOutcome = "YES";
    private List<String> tags = new ArrayList<>();
}
