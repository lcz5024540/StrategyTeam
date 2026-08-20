package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeMarketDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.multi.ExchangeHealthRegistry;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "arbitrage.multi-exchange", name = "health-check-enabled",
        havingValue = "true")
public class CrossExchangeGatewayHealthJob {
    private final List<CrossExchangeMarketDataProvider> providers;
    private final ExchangeHealthRegistry health;
    private final CrossExchangeMetrics metrics;

    public CrossExchangeGatewayHealthJob(List<CrossExchangeMarketDataProvider> providers,
            ExchangeHealthRegistry health, MultiExchangeFundingRateProperties properties,
            CrossExchangeMetrics metrics) {
        this.providers = providers.stream()
                .filter(provider -> properties.getExchanges().contains(provider.exchange()))
                .toList();
        this.health = health;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${arbitrage.multi-exchange.health-check-delay-ms:60000}")
    public void check() {
        Instant now = Instant.now();
        for (CrossExchangeMarketDataProvider provider : providers) {
            Instant startedAt = Instant.now();
            try {
                provider.supportedUsdtPerpetualSymbols();
                health.success(provider.exchange(), now,
                        java.time.Duration.between(startedAt, Instant.now()).toMillis());
            } catch (RuntimeException exception) {
                health.failure(provider.exchange(), now, exception,
                        java.time.Duration.between(startedAt, Instant.now()).toMillis());
            }
        }
        metrics.exchangeHealth(health.snapshot());
    }
}
