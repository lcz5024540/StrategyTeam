package com.metaverse.coin.strategy.job.funding.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinanceWebSocketRateLimiterTest {

    @Test
    void delaysTasksBeyondConfiguredRate() throws Exception {
        BinanceWebSocketRateLimiter limiter = new BinanceWebSocketRateLimiter("test-binance-ws-limiter", 1, 200L);
        CountDownLatch latch = new CountDownLatch(3);
        List<Long> runTimes = new CopyOnWriteArrayList<>();
        long start = System.currentTimeMillis();

        for (int i = 0; i < 3; i++) {
            limiter.execute(() -> {
                runTimes.add(System.currentTimeMillis() - start);
                latch.countDown();
            });
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(3, runTimes.size());
        assertTrue(runTimes.get(1) >= 150L);
        assertTrue(runTimes.get(2) >= 300L);
        assertTrue(limiter.getDelayedTasks() >= 2L);
        limiter.shutdownNow();
    }
}
