package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class PolymarketWalletMetricsRequest {
    private String walletAddress;
    private String chain = "polygon";
    private String alias;
    private BigDecimal winRate = BigDecimal.ZERO;
    private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
    private Integer totalTrades = 0;
    private BigDecimal averageSizeUsdc = BigDecimal.ZERO;
    private BigDecimal averageRoiRate = BigDecimal.ZERO;
    private BigDecimal maxDrawdownRate = BigDecimal.ZERO;
    private Date lastTradeAt;
    private Date lastDiscoveredAt;
    private String lastDiscoveryQuery;
    private Integer leaderboardHitCount30d;
    private String dominantSession;
    private BigDecimal asiaTradeRatio30d;
    private BigDecimal euTradeRatio30d;
    private BigDecimal usTradeRatio30d;
    private String activeHourHistogramJson;
    private BigDecimal recentWinRate30d;
}
