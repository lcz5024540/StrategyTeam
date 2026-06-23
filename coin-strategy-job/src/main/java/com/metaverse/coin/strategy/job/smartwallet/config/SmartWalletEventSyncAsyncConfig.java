package com.metaverse.coin.strategy.job.smartwallet.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated executor for HOT wallet trade-event polling.
 *
 * <p>The global scheduler should only trigger the polling cadence. The actual
 * Data API /trades work runs here so heavy scheduled jobs cannot starve event
 * ingestion and turn fresh leader trades into stale signals.</p>
 */
@Slf4j
@Configuration
public class SmartWalletEventSyncAsyncConfig {

    public static final String EXECUTOR_BEAN_NAME = "smartWalletEventSyncExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor smartWalletEventSyncExecutor(
            @Value("${smart-wallet.polymarket.event-sync.executor.queue-capacity:1}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix("sw-event-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("[SmartWalletEventSyncAsyncConfig] executor ready core=1 max=1 queue={}",
                Math.max(0, queueCapacity));
        return executor;
    }
}
