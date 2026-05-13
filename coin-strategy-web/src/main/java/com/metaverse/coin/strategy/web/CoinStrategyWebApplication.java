package com.metaverse.coin.strategy.web;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.web.config.DashboardProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Web 应用入口。
 *
 * <p>{@code scanBasePackages} 扩到 {@code com.metaverse.coin.strategy}，
 * 使下沉到 {@code common-service} 的 SmartWallet 相关 service 也能被 web 扫描到。
 * 同时 {@link SmartWalletProperties} 在 common-service，用 {@link EnableConfigurationProperties}
 * 显式启用其绑定。</p>
 */
@SpringBootApplication(scanBasePackages = "com.metaverse.coin.strategy")
@MapperScan("com.metaverse.coin.strategy.common.dao.mapper")
@EnableConfigurationProperties({DashboardProperties.class, SmartWalletProperties.class})
public class CoinStrategyWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoinStrategyWebApplication.class, args);
    }

}
