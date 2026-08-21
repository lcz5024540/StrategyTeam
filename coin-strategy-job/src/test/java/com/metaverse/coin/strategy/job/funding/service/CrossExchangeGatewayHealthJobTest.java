package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeMarketDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.ExchangeHealthRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossExchangeGatewayHealthJobTest {
    @Test
    void isolatesProviderFailureAndPublishesHealthMetrics() {
        CrossExchangeMarketDataProvider binance = provider(Exchange.BINANCE);
        CrossExchangeMarketDataProvider okx = provider(Exchange.OKX);
        when(binance.supportedUsdtPerpetualSymbols()).thenReturn(List.of("BTCUSDT"));
        when(okx.supportedUsdtPerpetualSymbols()).thenThrow(
                new IllegalStateException("wrapped",
                        new PerpetualMarketDataClient.RateLimitException(
                                new IllegalStateException("HTTP 429"))));
        ExchangeHealthRegistry health = new ExchangeHealthRegistry();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        CrossExchangeMetrics metrics = new CrossExchangeMetrics(meterRegistry);

        new CrossExchangeGatewayHealthJob(List.of(binance, okx), health,
                new MultiExchangeFundingRateProperties(), metrics).check();

        assertTrue(health.snapshot().get(Exchange.BINANCE).healthy());
        assertFalse(health.snapshot().get(Exchange.OKX).healthy());
        assertEquals(1, health.snapshot().get(Exchange.OKX).rateLimitCount());
        assertEquals(1D, meterRegistry.get("funding_arb_exchange_rate_limits_total")
                .tag("exchange", "OKX").gauge().value());
    }

    private CrossExchangeMarketDataProvider provider(Exchange exchange) {
        CrossExchangeMarketDataProvider provider = mock(CrossExchangeMarketDataProvider.class);
        when(provider.exchange()).thenReturn(exchange);
        return provider;
    }
}
