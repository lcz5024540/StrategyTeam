package com.metaverse.coin.strategy.job.btcminute.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * BTC 5 分钟涨跌策略配置。
 *
 * <pre>
 * btc-minute:
 *   enabled: false
 *   mode: SHADOW
 *   wallet-id: btc-minute-1
 *   capital-pool-usdc: 200
 *   max-single-stake-usdc: 20
 *   max-open-positions: 3
 *   min-edge-threshold: 0.08
 *   scan-interval-ms: 30000
 *   max-daily-loss-usdc: 50
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "btc-minute")
public class BtcMinuteStrategyProperties {

    /** 策略是否启用 */
    private boolean enabled = false;

    /** 运行模式: OFF / SHADOW / PAPER / LIVE */
    private String mode = "SHADOW";

    /** 绑定的钱包 ID */
    private String walletId = "btc-minute-1";

    /** 资金池上限（USDC） */
    private BigDecimal capitalPoolUsdc = new BigDecimal("200");

    /** 单笔最大仓位（USDC） */
    private BigDecimal maxSingleStakeUsdc = new BigDecimal("20");

    /** 最大同时持仓数 */
    private int maxOpenPositions = 3;

    /** 最小 edge 阈值（如 0.08 = 8% 定价偏差才开仓） */
    private BigDecimal minEdgeThreshold = new BigDecimal("0.08");

    /** 扫描间隔（毫秒） */
    private long scanIntervalMs = 30000;

    /** 每日最大亏损（USDC） */
    private BigDecimal maxDailyLossUsdc = new BigDecimal("50");

    /** 订单超时取消时间（秒） —— 5分钟市场到期快 */
    private int orderTimeoutSeconds = 120;

    /** Binance WebSocket 配置 */
    private BinanceWs binanceWs = new BinanceWs();

    /** 动量计算窗口 */
    private Momentum momentum = new Momentum();

    /** Polymarket BTC 市场扫描配置 */
    private MarketScan marketScan = new MarketScan();

    @Data
    public static class BinanceWs {
        /** WebSocket 基地址 */
        private String baseUrl = "wss://stream.binance.com:9443/ws";

        /** 交易对 */
        private String symbol = "btcusdt";

        /** K线周期 */
        private String klineInterval = "1m";

        /** REST 回退基地址 */
        private String restBaseUrl = "https://api.binance.com";

        /** 断线重连间隔（秒） */
        private int reconnectIntervalSeconds = 5;

        /** 最大重连次数 */
        private int maxReconnectAttempts = 20;
    }

    @Data
    public static class Momentum {
        /** EMA 快线周期 */
        private int emaFastPeriod = 5;

        /** EMA 慢线周期 */
        private int emaSlowPeriod = 20;

        /** RSI 周期 */
        private int rsiPeriod = 14;

        /** 动量计算所需的历史 K线数 */
        private int historyKlineCount = 30;

        /** 波动率计算窗口（秒） */
        private int volatilityWindowSeconds = 300;
    }

    @Data
    public static class MarketScan {
        /** Polymarket BTC 市场搜索关键词 */
        private String searchKeyword = "Bitcoin";

        /** 市场最小日交易量（USDC）—— 过滤低流动性市场 */
        private BigDecimal minDailyVolumeUsdc = new BigDecimal("1000");

        /** 市场最小 ask depth（USDC） */
        private BigDecimal minAskDepthUsdc = new BigDecimal("200");

        /** 最大 spread（bps） */
        private BigDecimal maxSpreadBps = new BigDecimal("500");

        /** 只考虑距离结算在此分钟数以内的市场 */
        private int maxMinutesToSettlement = 30;

        /** 最少距离结算的分钟数（太近了挂不上单） */
        private int minMinutesToSettlement = 3;
    }
}
