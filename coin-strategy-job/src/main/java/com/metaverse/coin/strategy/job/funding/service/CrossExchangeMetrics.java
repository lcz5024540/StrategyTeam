package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.ExchangeHealthRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class CrossExchangeMetrics {
    private final Counter scans;
    private final Counter opportunities;
    private final Counter rollbacks;
    private final Counter manualInterventions;
    private final AtomicInteger activePositions = new AtomicInteger();
    private final AtomicReference<Double> unhedgedNotional = new AtomicReference<>(0D);
    private final Set<String> countedRollbacks = ConcurrentHashMap.newKeySet();
    private final Set<String> countedManualInterventions = ConcurrentHashMap.newKeySet();
    private final Map<Exchange, AtomicInteger> exchangeHealthy = new EnumMap<>(Exchange.class);
    private final Map<Exchange, AtomicReference<Double>> exchangeSuccessRate = new EnumMap<>(Exchange.class);

    public CrossExchangeMetrics(MeterRegistry registry) {
        scans = registry.counter("funding_arb_scans_total");
        opportunities = registry.counter("funding_arb_opportunities_total");
        rollbacks = registry.counter("funding_arb_rollbacks_total");
        manualInterventions = registry.counter("funding_arb_manual_interventions_total");
        Gauge.builder("funding_arb_active_positions", activePositions, AtomicInteger::get)
                .register(registry);
        Gauge.builder("funding_arb_unhedged_notional", unhedgedNotional, AtomicReference::get)
                .register(registry);
        for (Exchange exchange : Exchange.values()) {
            AtomicInteger healthy = new AtomicInteger();
            AtomicReference<Double> successRate = new AtomicReference<>(0D);
            exchangeHealthy.put(exchange, healthy);
            exchangeSuccessRate.put(exchange, successRate);
            Gauge.builder("funding_arb_exchange_healthy", healthy, AtomicInteger::get)
                    .tag("exchange", exchange.name()).register(registry);
            Gauge.builder("funding_arb_exchange_request_success_ratio", successRate, AtomicReference::get)
                    .tag("exchange", exchange.name()).register(registry);
        }
    }

    public void scan(int found) {
        scans.increment();
        opportunities.increment(found);
    }

    public void positions(Iterable<PaperHedgePosition> positions) {
        int active = 0;
        BigDecimal naked = BigDecimal.ZERO;
        for (PaperHedgePosition position : positions) {
            if (position.status() == PaperHedgePosition.Status.OPENED) {
                active++;
            }
            if (position.status() == PaperHedgePosition.Status.ROLLED_BACK
                    && countedRollbacks.add(position.positionId())) {
                rollbacks.increment();
            }
            if (position.status() == PaperHedgePosition.Status.MANUAL_INTERVENTION) {
                if (countedManualInterventions.add(position.positionId())) {
                    manualInterventions.increment();
                }
                naked = naked.add(position.hedgedQuantity().multiply(
                        position.opportunity().longQuote().markPrice()));
            }
        }
        activePositions.set(active);
        unhedgedNotional.set(naked.doubleValue());
    }

    public void exchangeHealth(Map<Exchange, ExchangeHealthRegistry.Health> health) {
        for (Exchange exchange : Exchange.values()) {
            ExchangeHealthRegistry.Health snapshot = health.get(exchange);
            if (snapshot == null) {
                exchangeHealthy.get(exchange).set(0);
                exchangeSuccessRate.get(exchange).set(0D);
                continue;
            }
            exchangeHealthy.get(exchange).set(snapshot.healthy() ? 1 : 0);
            long total = snapshot.successCount() + snapshot.failureCount();
            double ratio = total == 0 ? 0D : (double) snapshot.successCount() / total;
            exchangeSuccessRate.get(exchange).set(ratio);
        }
    }
}
