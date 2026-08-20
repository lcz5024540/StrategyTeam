package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.market.PublicJsonHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExchangeHealthRegistryTest {
    @Test
    void tracksLatencyAndNestedRateLimitFailures() {
        ExchangeHealthRegistry registry = new ExchangeHealthRegistry();
        Instant now = Instant.now();
        registry.success(Exchange.BINANCE, now.minusSeconds(1), 45);
        registry.failure(Exchange.BINANCE, now,
                new IllegalStateException("wrapped", new PublicJsonHttpClient.RateLimitException("HTTP 429")),
                120);

        ExchangeHealthRegistry.Health health = registry.snapshot().get(Exchange.BINANCE);

        assertFalse(health.healthy());
        assertEquals(1, health.successCount());
        assertEquals(1, health.failureCount());
        assertEquals(1, health.rateLimitCount());
        assertEquals(120, health.lastLatencyMs());
    }
}
