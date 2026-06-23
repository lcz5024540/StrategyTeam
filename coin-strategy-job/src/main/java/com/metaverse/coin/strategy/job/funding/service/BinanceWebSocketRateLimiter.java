package com.metaverse.coin.strategy.job.funding.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class BinanceWebSocketRateLimiter {

    private final ScheduledExecutorService executor;
    private final long intervalMs;
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicLong delayedTasks = new AtomicLong();
    private long nextRunAt;

    BinanceWebSocketRateLimiter(String threadName, int limit, long windowMs) {
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        if (limit <= 0 || windowMs <= 0) {
            this.intervalMs = 0L;
        } else {
            this.intervalMs = Math.max(1L, windowMs / limit);
        }
    }

    void execute(Runnable task) {
        long delayMs = reserveDelay();
        if (delayMs > 0) {
            delayedTasks.incrementAndGet();
        }
        queuedTasks.incrementAndGet();
        executor.schedule(() -> {
            try {
                task.run();
            } finally {
                queuedTasks.decrementAndGet();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private synchronized long reserveDelay() {
        if (intervalMs <= 0) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        long scheduledAt = Math.max(now, nextRunAt);
        nextRunAt = scheduledAt + intervalMs;
        return Math.max(0L, scheduledAt - now);
    }

    int getQueuedTasks() {
        return queuedTasks.get();
    }

    long getDelayedTasks() {
        return delayedTasks.get();
    }

    void shutdownNow() {
        executor.shutdownNow();
    }
}
