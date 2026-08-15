package com.metaverse.coin.strategy.common.client.htx.rest;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.LockSupport;

/** Conservative fixed-window guard for HTX's per-UID private query and trade quotas. */
public class HtxUidRateLimiter {

    public enum RequestType {
        QUERY,
        TRADE
    }

    public static final int DEFAULT_QUERY_LIMIT = 68;
    public static final int DEFAULT_TRADE_LIMIT = 68;
    public static final long DEFAULT_WINDOW_MS = 3_000L;

    private final Window queryWindow;
    private final Window tradeWindow;

    public HtxUidRateLimiter() {
        this(DEFAULT_QUERY_LIMIT, DEFAULT_TRADE_LIMIT, DEFAULT_WINDOW_MS);
    }

    public HtxUidRateLimiter(int queryLimit, int tradeLimit, long windowMs) {
        if (queryLimit <= 0 || tradeLimit <= 0 || windowMs <= 0) {
            throw new IllegalArgumentException("positive HTX rate limits and window are required");
        }
        queryWindow = new Window(queryLimit, windowMs);
        tradeWindow = new Window(tradeLimit, windowMs);
    }

    public void acquire(RequestType requestType) {
        Window window = requestType == RequestType.TRADE ? tradeWindow : queryWindow;
        while (true) {
            long waitNanos = window.reserveOrWaitNanos(System.nanoTime());
            if (waitNanos <= 0) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("interrupted while waiting for HTX UID rate limit");
            }
            LockSupport.parkNanos(waitNanos);
        }
    }

    /** Non-blocking probe used by metrics and deterministic tests; a successful probe consumes one slot. */
    public boolean tryAcquire(RequestType requestType) {
        Window window = requestType == RequestType.TRADE ? tradeWindow : queryWindow;
        return window.reserveOrWaitNanos(System.nanoTime()) <= 0;
    }

    private static final class Window {
        private final int limit;
        private final long windowNanos;
        private final Deque<Long> timestamps = new ArrayDeque<>();

        private Window(int limit, long windowMs) {
            this.limit = limit;
            this.windowNanos = windowMs * 1_000_000L;
        }

        private synchronized long reserveOrWaitNanos(long now) {
            long cutoff = now - windowNanos;
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.removeFirst();
            }
            if (timestamps.size() < limit) {
                timestamps.addLast(now);
                return 0L;
            }
            return Math.max(1L, timestamps.peekFirst() + windowNanos - now);
        }
    }
}
