package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletProfile {
    private String address;
    private String chain;
    private String alias;
    private BigDecimal winRate;
    private BigDecimal recentWinRate7d;
    private BigDecimal recentWinRate30d;
    private BigDecimal realizedPnlUsdc;
    private Integer totalTrades;
    private Integer recentTradeCount7d;
    private Integer recentTradeCount30d;
    private Date observeCooldownUntil;
    private String observeCooldownReason;
    private BigDecimal averageSizeUsdc;
    private BigDecimal averageRoiRate;
    private BigDecimal maxDrawdownRate;
    private BigDecimal behaviorComposite;
    private String preferredOutcome;
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
