package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import com.metaverse.coin.strategy.core.arbitrage.multi.ExchangeHealthRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrossExchangeMetricsTest {
    @Test
    void deduplicatesTerminalCountersAndRefreshesGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CrossExchangeMetrics metrics = new CrossExchangeMetrics(registry);
        Instant now = Instant.now();
        CrossExchangeOpportunity opportunity = opportunity(now);
        PaperHedgePosition opened = position("opened", opportunity, PaperHedgePosition.Status.OPENED,
                new BigDecimal("0.5"), now);
        PaperHedgePosition rolledBack = position("rollback", opportunity, PaperHedgePosition.Status.ROLLED_BACK,
                BigDecimal.ZERO, now);
        PaperHedgePosition manual = position("manual", opportunity, PaperHedgePosition.Status.MANUAL_INTERVENTION,
                new BigDecimal("0.25"), now);

        metrics.positions(List.of(opened, rolledBack, manual));
        metrics.positions(List.of(opened, rolledBack, manual));

        assertEquals(1D, registry.get("funding_arb_rollbacks_total").counter().count());
        assertEquals(1D, registry.get("funding_arb_manual_interventions_total").counter().count());
        assertEquals(1D, registry.get("funding_arb_active_positions").gauge().value());
        assertEquals(25D, registry.get("funding_arb_unhedged_notional").gauge().value());

        metrics.positions(List.of());
        assertEquals(0D, registry.get("funding_arb_active_positions").gauge().value());
        assertEquals(0D, registry.get("funding_arb_unhedged_notional").gauge().value());
    }

    @Test
    void exposesTaggedExchangeHealthAndSuccessRatio() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CrossExchangeMetrics metrics = new CrossExchangeMetrics(registry);
        Instant now = Instant.now();
        Map<Exchange, ExchangeHealthRegistry.Health> health = Map.of(
                Exchange.BINANCE, new ExchangeHealthRegistry.Health(Exchange.BINANCE, true,
                        3, 1, 2, 125, now, now.minusSeconds(1), null));

        metrics.exchangeHealth(health);

        assertEquals(1D, registry.get("funding_arb_exchange_healthy")
                .tag("exchange", "BINANCE").gauge().value());
        assertEquals(0.75D, registry.get("funding_arb_exchange_request_success_ratio")
                .tag("exchange", "BINANCE").gauge().value());
        assertEquals(0D, registry.get("funding_arb_exchange_healthy")
                .tag("exchange", "OKX").gauge().value());
        assertEquals(125D, registry.get("funding_arb_exchange_last_latency_ms")
                .tag("exchange", "BINANCE").gauge().value());
        assertEquals(2D, registry.get("funding_arb_exchange_rate_limits_total")
                .tag("exchange", "BINANCE").gauge().value());
    }

    @Test
    void exposesCircuitAndPauseState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CrossExchangeMetrics metrics = new CrossExchangeMetrics(registry);

        metrics.strategyState(true, false);

        assertEquals(1D, registry.get("funding_arb_circuit_open").gauge().value());
        assertEquals(0D, registry.get("funding_arb_paused").gauge().value());
    }

    private PaperHedgePosition position(String id, CrossExchangeOpportunity opportunity,
            PaperHedgePosition.Status status, BigDecimal quantity, Instant now) {
        return new PaperHedgePosition(id, opportunity, null, null, quantity, status, now, now, null);
    }

    private CrossExchangeOpportunity opportunity(Instant now) {
        NormalizedFundingRateSnapshot longQuote = new NormalizedFundingRateSnapshot(
                Exchange.BINANCE, "BTCUSDT", new BigDecimal("-0.001"), 8,
                now.plusSeconds(3600), new BigDecimal("100"), new BigDecimal("100"), now);
        NormalizedFundingRateSnapshot shortQuote = new NormalizedFundingRateSnapshot(
                Exchange.OKX, "BTCUSDT", new BigDecimal("0.001"), 8,
                now.plusSeconds(3600), new BigDecimal("100"), new BigDecimal("100"), now);
        return new CrossExchangeOpportunity("opportunity", "BTCUSDT", longQuote, shortQuote,
                new BigDecimal("0.00025"), new BigDecimal("0.0002"), new BigDecimal("100"),
                CrossExchangeOpportunity.Status.APPROVED, null, now);
    }
}
