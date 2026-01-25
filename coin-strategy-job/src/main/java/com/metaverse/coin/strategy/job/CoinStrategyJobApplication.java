package com.metaverse.coin.strategy.job;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.metaverse.coin.strategy.common.dao.mapper")
public class CoinStrategyJobApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoinStrategyJobApplication.class, args);
    }

}
