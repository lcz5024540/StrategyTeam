package com.metaverse.coin.strategy.job;

import com.metaverse.coin.strategy.job.hedge.config.HedgeProperties;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.metaverse.coin.strategy")
@MapperScan("com.metaverse.coin.strategy.common.dao.mapper")
@EnableConfigurationProperties({SmartWalletProperties.class, HedgeProperties.class})
@EnableScheduling
@Slf4j
public class CoinStrategyJobApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoinStrategyJobApplication.class, args);
        log.info("================CoinStrategyJobApplication start success!================");
    }

}
