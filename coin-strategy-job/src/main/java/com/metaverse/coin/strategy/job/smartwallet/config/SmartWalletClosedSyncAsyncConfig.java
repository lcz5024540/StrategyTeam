package com.metaverse.coin.strategy.job.smartwallet.config;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Polymarket /closed-positions 增量同步专用线程池。
 *
 * 资源约束：
 * - 当前 2 核服务器：core=1, max=2, queue=200, HTTP 并发上限 4（由 ClosedPositionIncrementalSyncService 的 Semaphore 控制）
 * - 升级到 4 核时：通过 smart-wallet.polymarket.closed-position-sync.thread-pool-* 配置覆盖即可，无需改代码
 *
 * 拒绝策略：CallerRunsPolicy —— 让调度线程自己执行，避免任务丢失。
 */
@Slf4j
@Configuration
public class SmartWalletClosedSyncAsyncConfig {

    public static final String EXECUTOR_BEAN_NAME = "smartWalletClosedSyncExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor smartWalletClosedSyncExecutor(SmartWalletProperties properties) {
        SmartWalletProperties.PolymarketMonitor.ClosedPositionSync cfg =
                properties.getPolymarket().getClosedPositionSync();
        int core = resolve(cfg == null ? null : cfg.getThreadPoolCoreSize(), 1);
        int max = Math.max(core, resolve(cfg == null ? null : cfg.getThreadPoolMaxSize(), 2));
        int queue = resolve(cfg == null ? null : cfg.getThreadPoolQueueCapacity(), 200);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("sw-closed-sync-");
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("[SmartWalletClosedSyncAsyncConfig] executor ready core={} max={} queue={}", core, max, queue);
        return executor;
    }

    private static int resolve(Integer value, int fallback) {
        return (value == null || value <= 0) ? fallback : value;
    }
}