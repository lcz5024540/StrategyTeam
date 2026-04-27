package com.metaverse.coin.strategy.job;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.metaverse.coin.strategy.common.dao.mapper")
@EnableConfigurationProperties({SmartWalletProperties.class, WeatherArbitrageProperties.class})
@EnableScheduling
public class CoinStrategyJobApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoinStrategyJobApplication.class, args);
    }

}
