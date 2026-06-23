package com.metaverse.coin.strategy.job.smartwallet.config;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SmartWalletClosedSyncAsyncConfigTest {

    @Test
    void shouldRejectSaturatedClosedSyncWorkInsteadOfRunningOnCallerThread() {
        ThreadPoolTaskExecutor executor = new SmartWalletClosedSyncAsyncConfig()
                .smartWalletClosedSyncExecutor(new SmartWalletProperties());
        try {
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class,
                    executor.getThreadPoolExecutor().getRejectedExecutionHandler());
        } finally {
            executor.shutdown();
        }
    }
}
