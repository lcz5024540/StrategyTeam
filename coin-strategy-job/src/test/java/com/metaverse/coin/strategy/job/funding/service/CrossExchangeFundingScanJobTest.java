package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeArbitragePersistenceService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunityScanService;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossExchangeFundingScanJobTest {
    @Test
    void persistsEveryEvaluatedCandidateIncludingRejections() {
        CrossExchangeOpportunityScanService scanner = mock(CrossExchangeOpportunityScanService.class);
        CrossExchangeArbitragePersistenceService persistence = mock(CrossExchangeArbitragePersistenceService.class);
        CrossExchangeMetrics metrics = mock(CrossExchangeMetrics.class);
        CrossExchangeOpportunity approved = opportunity("approved", CrossExchangeOpportunity.Status.APPROVED);
        CrossExchangeOpportunity rejected = opportunity("rejected", CrossExchangeOpportunity.Status.REJECTED);
        when(scanner.commonSymbols()).thenReturn(Set.of("BTCUSDT"));
        when(scanner.scan(org.mockito.ArgumentMatchers.eq("BTCUSDT"), any(Instant.class)))
                .thenReturn(List.of(approved));
        when(scanner.lastEvaluated()).thenReturn(List.of(approved, rejected));

        new CrossExchangeFundingScanJob(scanner, persistence, metrics).scan();

        verify(persistence).saveOpportunity(approved);
        verify(persistence).saveOpportunity(rejected);
        verify(metrics).scan(1);
    }

    @Test
    void timestampsEachSymbolAtItsOwnScanStart() {
        CrossExchangeOpportunityScanService scanner = mock(CrossExchangeOpportunityScanService.class);
        CrossExchangeArbitragePersistenceService persistence = mock(CrossExchangeArbitragePersistenceService.class);
        CrossExchangeMetrics metrics = mock(CrossExchangeMetrics.class);
        when(scanner.commonSymbols()).thenReturn(new LinkedHashSet<>(List.of("BTCUSDT", "ETHUSDT")));
        when(scanner.scan(any(String.class), any(Instant.class))).thenReturn(List.of());
        when(scanner.lastEvaluated()).thenReturn(List.of());
        Instant first = Instant.parse("2026-09-01T10:00:00Z");

        new CrossExchangeFundingScanJob(scanner, persistence, metrics, advancingClock(first)).scan();

        var firstScanTime = forClass(Instant.class);
        var secondScanTime = forClass(Instant.class);
        verify(scanner).scan(eq("BTCUSDT"), firstScanTime.capture());
        verify(scanner).scan(eq("ETHUSDT"), secondScanTime.capture());
        assertEquals(first, firstScanTime.getValue());
        assertEquals(first.plusSeconds(1), secondScanTime.getValue());
    }

    private Clock advancingClock(Instant first) {
        AtomicLong seconds = new AtomicLong();
        return new Clock() {
            @Override public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override public Instant instant() {
                return first.plusSeconds(seconds.getAndIncrement());
            }
        };
    }

    private CrossExchangeOpportunity opportunity(String id, CrossExchangeOpportunity.Status status) {
        Instant now = Instant.now();
        NormalizedFundingRateSnapshot longQuote = new NormalizedFundingRateSnapshot(
                Exchange.BINANCE, "BTCUSDT", new BigDecimal("-0.001"), 8,
                now.plusSeconds(3600), new BigDecimal("100"), new BigDecimal("100"), now);
        NormalizedFundingRateSnapshot shortQuote = new NormalizedFundingRateSnapshot(
                Exchange.OKX, "BTCUSDT", new BigDecimal("-0.0001"), 8,
                now.plusSeconds(3600), new BigDecimal("100"), new BigDecimal("100"), now);
        return new CrossExchangeOpportunity(id, "BTCUSDT", longQuote, shortQuote,
                new BigDecimal("0.0001125"), new BigDecimal("0.0001"), new BigDecimal("100"),
                status, status == CrossExchangeOpportunity.Status.REJECTED ? "TEST_REJECTION" : null, now);
    }
}
