package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletMarketMonitorItemView {
    private String marketId;
    private String question;
    private Long marketEndAt;
    private String marketEndAtBjt;
    private Date lastSignalAt;
    private String lastSignalAtBjt;
    private Integer signalCount;
    private Integer uniqueWalletCount;
    private Integer pendingTaskCount;
    private Integer openPositionCount;
    private BigDecimal openCostUsdc;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private Boolean activeMarket;
    private Boolean effectiveMarket;
    private Boolean concentrationAvailable;
    private Integer observedHolders;
    private BigDecimal top1Pct;
    private BigDecimal top3Pct;
    private BigDecimal top5Pct;
    private Integer hhi;
    private Boolean highlyConcentrated;
    private Boolean extremelyConcentrated;
    private String concentrationBand;
    @Builder.Default
    private List<String> topHolderAddresses = new ArrayList<>();
}
