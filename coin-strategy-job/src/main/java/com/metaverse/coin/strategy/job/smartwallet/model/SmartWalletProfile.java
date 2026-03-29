package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SmartWalletProfile {
    private String address;
    private String chain;
    private String alias;
    private BigDecimal winRate;
    private BigDecimal realizedPnlUsdc;
    private Integer totalTrades;
    private BigDecimal averageSizeUsdc;
    private BigDecimal maxDrawdownRate;
    private String preferredOutcome;
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
