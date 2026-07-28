package com.metaverse.coin.strategy.htx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

/**
 * Standalone HTX (Huobi) spot execution service.
 * <p>
 * Isolated from {@code coin-strategy-job} on purpose (see harness activeContext v2.8.2):
 * polymarket / funding-rate strategies live in job; HTX spot execution runs here so that
 * incidents in one service — WebSocket floods, thread-pool exhaustion, OOM — cannot cascade
 * into the other.
 * <p>
 * Port defaults to {@code 8082} (job = 8081, web = 8080). Downstream of {@code common-client}
 * only; no MySQL / no Kafka / no other exchange clients loaded.
 */
@SpringBootApplication(scanBasePackages = "com.metaverse.coin.strategy.htx")
@Slf4j
public class CoinStrategyHtxApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(CoinStrategyHtxApplication.class, args);
        log.info("================ CoinStrategyHtxApplication start success! ================");
    }
}
