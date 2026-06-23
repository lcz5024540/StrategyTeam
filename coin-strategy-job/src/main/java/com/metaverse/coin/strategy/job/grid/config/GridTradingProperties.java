package com.metaverse.coin.strategy.job.grid.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 网格交易策略配置。
 *
 * <pre>
 * grid-trading:
 *   enabled: false
 *   mode: SHADOW
 *   wallet-id: grid-1
 *   capital-pool-usdc: 500
 *   instances:
 *     - market-id: "0x..."
 *       center-price: 0.50
 *       grid-spacing: 0.02
 *       grid-levels: 5
 *       amount-per-level-usdc: 10
 *   global:
 *     max-total-grid-exposure-usdc: 500
 *     order-refresh-interval-ms: 30000
 *     cancel-before-settlement-hours: 4
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "grid-trading")
public class GridTradingProperties {

    private boolean enabled = false;
    private String mode = "SHADOW";
    private String walletId = "grid-1";
    private BigDecimal capitalPoolUsdc = new BigDecimal("500");

    /** 网格实例列表 —— 每个实例对应一个 Polymarket 市场 */
    private List<GridInstanceConfig> instances = new ArrayList<>();

    /** 全局配置 */
    private GlobalConfig global = new GlobalConfig();

    @Data
    public static class GridInstanceConfig {
        /** Polymarket conditionId */
        private String marketId;
        /** 网格中心价格 */
        private BigDecimal centerPrice = new BigDecimal("0.50");
        /** 每层间距（Polymarket 价格单位，如 0.02 = 2 美分） */
        private BigDecimal gridSpacing = new BigDecimal("0.02");
        /** 中心价上下各多少层 */
        private int gridLevels = 5;
        /** 每层挂单金额（USDC） */
        private BigDecimal amountPerLevelUsdc = new BigDecimal("10");
        /** 该市场单侧最大暴露（USDC） */
        private BigDecimal maxOneSideExposureUsdc = new BigDecimal("100");
    }

    @Data
    public static class GlobalConfig {
        /** 所有网格实例的总暴露上限（USDC） */
        private BigDecimal maxTotalGridExposureUsdc = new BigDecimal("500");
        /** 市场最小日交易量（USDC） */
        private BigDecimal minMarketDailyVolumeUsdc = new BigDecimal("5000");
        /** 市场最小 ask depth */
        private BigDecimal minAskDepth3cUsdc = new BigDecimal("200");
        /** 最大 spread（bps） */
        private BigDecimal maxSpreadBps = new BigDecimal("300");
        /** 结算前取消挂单的小时数 */
        private int cancelBeforeSettlementHours = 4;
        /** 挂单刷新间隔（毫秒） */
        private long orderRefreshIntervalMs = 30000;
        /** Polymarket 最小价格 tick */
        private BigDecimal minimumTickSize = new BigDecimal("0.01");
    }
}
