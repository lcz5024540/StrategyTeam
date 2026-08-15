package com.metaverse.coin.strategy.common.client.htx.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxUidRateLimiterTest {

    @Test
    void queryAndTradeWindowsAreIndependentAndRejectBeyondCapacity() {
        HtxUidRateLimiter limiter = new HtxUidRateLimiter(1, 2, 60_000L);

        assertTrue(limiter.tryAcquire(HtxUidRateLimiter.RequestType.QUERY));
        assertFalse(limiter.tryAcquire(HtxUidRateLimiter.RequestType.QUERY));
        assertTrue(limiter.tryAcquire(HtxUidRateLimiter.RequestType.TRADE));
        assertTrue(limiter.tryAcquire(HtxUidRateLimiter.RequestType.TRADE));
        assertFalse(limiter.tryAcquire(HtxUidRateLimiter.RequestType.TRADE));
    }
}
