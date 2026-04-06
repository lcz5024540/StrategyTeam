package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletConflictMarketView {

    private String marketId;
    private String question;
    private Integer outcomeCount;
    private Integer positionCount;
    private Integer openPositionCount;
    private List<String> outcomes;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private BigDecimal netPnlUsdc;
    private Long marketEndAt;
    private String marketEndAtBjt;
    private Date latestClosedAt;
}
