package com.metaverse.coin.strategy.job.smartwallet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "smart-wallet")
public class SmartWalletProperties {

    private boolean enabled = true;
    private boolean autoExecutionEnabled = false;
    private boolean scanEnabled = true;
    private boolean requireTagMatch = true;
    private BigDecimal minWinRate = new BigDecimal("0.62");
    private BigDecimal minWalletScore = new BigDecimal("0.70");
    private Integer minTotalTrades = 25;
    private BigDecimal minRealizedPnlUsdc = new BigDecimal("1000");
    private BigDecimal maxDrawdownRate = new BigDecimal("0.25");
    private BigDecimal minMarketLiquidity = new BigDecimal("25000");
    private BigDecimal maxStakeUsdc = new BigDecimal("150");
    private BigDecimal bankrollUtilizationRate = new BigDecimal("0.03");
    private Integer followDelaySeconds = 3;
    private Long pollFixedDelayMs = 5000L;
    private PolymarketMonitor polymarket = new PolymarketMonitor();
    private List<WalletSeed> wallets = new ArrayList<>();
    private List<MarketSeed> markets = new ArrayList<>();

    @Data
    public static class PolymarketMonitor {
        private boolean enabled = true;
        private boolean autoDiscoverEnabled = false;
        private boolean autoSyncEventsEnabled = true;
        private String dataApiBaseUrl = "https://data-api.polymarket.com";
        private String gammaApiBaseUrl = "https://gamma-api.polymarket.com";
        private String leaderboardCategory = "OVERALL";
        private String leaderboardTimePeriod = "ALL";
        private String leaderboardOrderBy = "PNL";
        private Integer leaderboardLimit = 50;
        private Integer analyzeTopN = 30;
        private Integer closedPositionLimit = 50;
        private Integer recentTradeLimit = 30;
        private Long discoveryFixedDelayMs = 3600000L;
        private Long eventSyncFixedDelayMs = 15000L;
    }

    @Data
    public static class WalletSeed {
        private String address;
        private String chain;
        private String alias;
        private BigDecimal winRate = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private Integer totalTrades = 0;
        private BigDecimal averageSizeUsdc = BigDecimal.ZERO;
        private BigDecimal maxDrawdownRate = BigDecimal.ZERO;
        private String preferredOutcome = "YES";
        private List<String> tags = new ArrayList<>();
    }

    @Data
    public static class MarketSeed {
        private String marketId;
        private String question;
        private String outcome = "YES";
        private BigDecimal price = BigDecimal.ZERO;
        private BigDecimal conviction = BigDecimal.ZERO;
        private BigDecimal liquidityUsdc = BigDecimal.ZERO;
        private String category;
        private List<String> tags = new ArrayList<>();
    }
}
