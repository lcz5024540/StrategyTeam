package com.metaverse.coin.strategy.core.arbitrage.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class CrossExchangeOpportunityScanServiceTest {
    @Test
    void requotesApprovedOpportunityAndRejectsAChangedDirectionalPair() {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        AtomicReference<BigDecimal> binanceRate = new AtomicReference<>(new BigDecimal("-0.001"));
        AtomicReference<BigDecimal> okxRate = new AtomicReference<>(new BigDecimal("-0.0001"));
        CrossExchangeOpportunityScanService scanner = scanner(now, binanceRate, okxRate);

        CrossExchangeOpportunity approved = scanner.scan("BTCUSDT", now).get(0);
        assertTrue(scanner.revalidateApprovedOpportunity(approved.opportunityId(), now.plusSeconds(1))
                .isPresent());

        binanceRate.set(new BigDecimal("0.0002"));
        okxRate.set(new BigDecimal("-0.0001"));
        assertTrue(scanner.revalidateApprovedOpportunity(approved.opportunityId(), now.plusSeconds(2))
                .isEmpty());
    }

    @Test
    void requestsConfiguredOrderBookDepthForEveryLeg() {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        AtomicReference<BigDecimal> binanceRate = new AtomicReference<>(new BigDecimal("-0.001"));
        AtomicReference<BigDecimal> okxRate = new AtomicReference<>(new BigDecimal("-0.0001"));
        AtomicReference<Integer> requestedDepth = new AtomicReference<>();
        CrossExchangeOpportunityScanService scanner = scanner(now, binanceRate, okxRate,
                requestedDepth, 100);

        scanner.scan("BTCUSDT", now);

        assertEquals(100, requestedDepth.get());
    }

    private CrossExchangeOpportunityScanService scanner(Instant now,
            AtomicReference<BigDecimal> binanceRate, AtomicReference<BigDecimal> okxRate) {
        return scanner(now, binanceRate, okxRate, new AtomicReference<>(), 20);
    }

    private CrossExchangeOpportunityScanService scanner(Instant now,
            AtomicReference<BigDecimal> binanceRate, AtomicReference<BigDecimal> okxRate,
            AtomicReference<Integer> requestedDepth, int orderBookDepth) {
        Map<Exchange, ExchangeCost> costs = new EnumMap<>(Exchange.class);
        costs.put(Exchange.BINANCE, new ExchangeCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        costs.put(Exchange.OKX, new ExchangeCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        CrossExchangeOpportunitySelector.Limits limits = new CrossExchangeOpportunitySelector.Limits(
                Duration.ofMinutes(1), Duration.ofMinutes(2), new BigDecimal("0.01"), BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, false, BigDecimal.ONE, new BigDecimal("0.01"));
        return new CrossExchangeOpportunityScanService(List.of(
                provider(Exchange.BINANCE, binanceRate, now, requestedDepth),
                provider(Exchange.OKX, okxRate, now, requestedDepth)),
                new CrossExchangeOpportunitySelector(),
                new CrossExchangeOpportunityStabilityGate(Duration.ZERO), limits, costs,
                new BigDecimal("100"), new ExchangeHealthRegistry(), null, orderBookDepth);
    }

    private CrossExchangeMarketDataProvider provider(Exchange exchange,
            AtomicReference<BigDecimal> rate, Instant receivedAt,
            AtomicReference<Integer> requestedDepth) {
        return new CrossExchangeMarketDataProvider() {
            @Override public Exchange exchange() { return exchange; }
            @Override public List<String> supportedUsdtPerpetualSymbols() { return List.of("BTCUSDT"); }
            @Override public NormalizedFundingRateSnapshot fundingRate(String symbol) {
                return new NormalizedFundingRateSnapshot(exchange, symbol, rate.get(), 8,
                        receivedAt.plus(Duration.ofHours(1)), new BigDecimal("100"),
                        new BigDecimal("100"), receivedAt);
            }
            @Override public NormalizedOrderBookSnapshot orderBook(String symbol, int depth) {
                requestedDepth.set(depth);
                return new NormalizedOrderBookSnapshot(exchange, symbol, new BigDecimal("99.9"),
                        new BigDecimal("100.1"), new BigDecimal("1000"), new BigDecimal("1000"), receivedAt);
            }
        };
    }
}
