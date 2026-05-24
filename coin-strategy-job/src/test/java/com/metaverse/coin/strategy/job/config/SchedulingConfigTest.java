package com.metaverse.coin.strategy.job.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void shouldProvideGlobalScheduledTaskSchedulerWithDefaultPoolSize() {
        contextRunner.run(context -> {
            ThreadPoolTaskScheduler scheduler = context.getBean(
                    SchedulingConfig.SCHEDULER_BEAN_NAME,
                    ThreadPoolTaskScheduler.class);

            assertNotNull(scheduler);
            assertEquals(SchedulingConfig.DEFAULT_POOL_SIZE,
                    scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            scheduler.execute(() -> {
                threadName.set(Thread.currentThread().getName());
                latch.countDown();
            });

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertTrue(threadName.get().startsWith("sched-"));
        });
    }
}
