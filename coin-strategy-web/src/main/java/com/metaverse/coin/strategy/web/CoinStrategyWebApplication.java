package com.metaverse.coin.strategy.web;

import com.metaverse.coin.strategy.web.config.DashboardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DashboardProperties.class)
public class CoinStrategyWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoinStrategyWebApplication.class, args);
    }

}
