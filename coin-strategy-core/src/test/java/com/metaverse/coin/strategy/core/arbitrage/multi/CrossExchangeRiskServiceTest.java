package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CrossExchangeRiskServiceTest {
    @Test void blocksPausedAndOversizedOrders() {
        var risk = new CrossExchangeRiskService(new CrossExchangeRiskService.Limits(new BigDecimal("100"), Duration.ofSeconds(5), Duration.ofDays(1), BigDecimal.ZERO));
        assertEquals("STRATEGY_PAUSED", risk.approveOpen(null, BigDecimal.TEN, List.of(), true, false).reason());
    }

    @Test
    void rejectsWhenEitherExchangeAggregateLimitWouldBeExceeded() {
        var risk = new CrossExchangeRiskService(new CrossExchangeRiskService.Limits(
                new BigDecimal("100"), new BigDecimal("150"), Duration.ofSeconds(5),
                Duration.ofDays(1), BigDecimal.ZERO));
        Instant now = Instant.now();
        CrossExchangeOpportunity first = opportunity("BTCUSDT", now);
        PaperHedgePosition existing = new PaperHedgePosition("position", first, null, null,
                BigDecimal.ONE, PaperHedgePosition.Status.OPENED, now, now, null);
        CrossExchangeOpportunity second = opportunity("ETHUSDT", now);

        var assessment = risk.approveOpen(second, new BigDecimal("60"), List.of(existing),
                false, false);

        assertFalse(assessment.passed());
        assertEquals("EXCHANGE_NOTIONAL_LIMIT", assessment.reason());
    }

    @Test
    void capsPaperOpenNotionalAtApprovedOpportunityCapacity() {
        var risk = new CrossExchangeRiskService(new CrossExchangeRiskService.Limits(
                new BigDecimal("200"), Duration.ofSeconds(5), Duration.ofDays(1),
                BigDecimal.ZERO));
        Instant now = Instant.now();
        CrossExchangeOpportunity opportunity = opportunity("BTCUSDT", now);

        assertTrue(risk.approveOpen(opportunity, new BigDecimal("100"), List.of(),
                false, false).passed());
        var assessment = risk.approveOpen(opportunity, new BigDecimal("100.01"), List.of(),
                false, false);

        assertFalse(assessment.passed());
        assertEquals("OPPORTUNITY_NOTIONAL_LIMIT", assessment.reason());
    }

    @Test
    void exitsWhenCrossExchangeMarkPriceDeviationIsTooLarge() {
        var risk = new CrossExchangeRiskService(new CrossExchangeRiskService.Limits(
                new BigDecimal("100"), Duration.ofSeconds(5), Duration.ofDays(1),
                BigDecimal.ZERO));
        Instant now = Instant.now();
        PaperHedgePosition position = new PaperHedgePosition("position", opportunity("BTCUSDT", now),
                null, null, BigDecimal.ONE, PaperHedgePosition.Status.OPENED, now, now, null);

        var assessment = risk.shouldExit(position, BigDecimal.ONE, new BigDecimal("0.02"),
                now, true, false);

        assertTrue(assessment.passed());
        assertEquals("MARK_PRICE_DEVIATION", assessment.reason());
    }

    @Test
    void opensCircuitSignalAfterMaximumNakedDuration() {
        var risk = new CrossExchangeRiskService(new CrossExchangeRiskService.Limits(
                new BigDecimal("100"), Duration.ofSeconds(5), Duration.ofDays(1),
                BigDecimal.ZERO));
        Instant now = Instant.now();
        PaperHedgePosition position = new PaperHedgePosition("position", opportunity("BTCUSDT", now),
                null, null, BigDecimal.ONE, PaperHedgePosition.Status.MANUAL_INTERVENTION,
                null, now, "naked");

        var assessment = risk.nakedExposure(position, now.plusSeconds(5));

        assertTrue(assessment.passed());
        assertEquals("MAX_NAKED_DURATION", assessment.reason());
    }

    private CrossExchangeOpportunity opportunity(String symbol, Instant now) {
        NormalizedFundingRateSnapshot longQuote = new NormalizedFundingRateSnapshot(
                Exchange.BINANCE, symbol, new BigDecimal("-0.001"), 8,
                now.plusSeconds(3600), new BigDecimal("100"), new BigDecimal("100"), now);
        NormalizedFundingRateSnapshot shortQuote = new NormalizedFundingRateSnapshot(
                Exchange.OKX, symbol, new BigDecimal("-0.0001"), 8,
                now.plusSeconds(3600), new BigDecimal("100"), new BigDecimal("100"), now);
        return new CrossExchangeOpportunity(symbol, symbol, longQuote, shortQuote,
                new BigDecimal("0.0001125"), new BigDecimal("0.0001"),
                new BigDecimal("100"), CrossExchangeOpportunity.Status.APPROVED, null, now);
    }
}
