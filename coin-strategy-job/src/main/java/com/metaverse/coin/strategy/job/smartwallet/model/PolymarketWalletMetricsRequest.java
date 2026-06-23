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
    private BigDecimal recentWinRate7d;
    private BigDecimal recentWinRate30d;
    private Integer recentTradeCount7d;
    private Integer recentTradeCount30d;
    /** Beta后验/Wilson 方案（2026-06-05）：决断样本数 wins+losses；null=本次来源不产出（store 侧不覆盖）。 */
    private Integer decisiveTrades;
    /** Wilson 单侧95%下界可信胜率。 */
    private BigDecimal credibleWinRate;
    /** Beta 后验均值收缩胜率。 */
    private BigDecimal shrunkWinRate;
}
