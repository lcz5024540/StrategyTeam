package com.metaverse.coin.strategy.job.engine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * 策略引擎全局配置。
 *
 * <p>独立的配置前缀 {@code strategy-engine}，不与现有的 smart-wallet 或 weather 配置冲突。</p>
 *
 * <pre>
 * strategy-engine:
 *   api-base-url: https://clob.polymarket.com
 *   global-risk:
 *     max-total-exposure-usdc: 2000
 *     max-total-daily-loss-usdc: 200
 *     enable-cross-strategy-exposure-check: true
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "strategy-engine")
public class StrategyEngineProperties {

    /** Polymarket CLOB API 基地址（新策略共用，跟单策略继续用自己的配置） */
    private String apiBaseUrl = "https://clob.polymarket.com";

    /** 全局风控配置 */
    private GlobalRisk globalRisk = new GlobalRisk();

    @Data
    public static class GlobalRisk {
        /** 所有策略的总持仓上限（USDC） */
        private BigDecimal maxTotalExposureUsdc = new BigDecimal("2000");

        /** 所有策略的每日总亏损上限（USDC） */
        private BigDecimal maxTotalDailyLossUsdc = new BigDecimal("200");

        /** 是否启用跨策略暴露检查 */
        private boolean enableCrossStrategyExposureCheck = true;
    }
}
