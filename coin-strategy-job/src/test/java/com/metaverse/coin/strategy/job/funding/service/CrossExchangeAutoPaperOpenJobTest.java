package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunityScanService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossExchangeAutoPaperOpenJobTest {
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private final CrossExchangeOpportunityScanService scanner = mock(CrossExchangeOpportunityScanService.class);
    private final CrossExchangePaperTradingService paper = mock(CrossExchangePaperTradingService.class);
    private final CrossExchangeArbPositionMapper positions = mock(CrossExchangeArbPositionMapper.class);

    @Test
    void opensHighestYieldApprovedOpportunityAtExecutableNotional() {
        CrossExchangeOpportunity lower = opportunity("lower", "ETHUSDT", "0.0003", "100");
        CrossExchangeOpportunity higher = opportunity("higher", "BTCUSDT", "0.0008", "250");
        when(scanner.approvedOpportunities(NOW)).thenReturn(List.of(lower, rejected("rejected"), higher));
        when(scanner.revalidateApprovedOpportunity(eq("higher"),
                argThat(value -> sameAmount(value, "250")), eq(NOW))).thenReturn(Optional.of(higher));
        when(paper.positions()).thenReturn(List.of());
        when(positions.selectList(any())).thenReturn(List.of());
        when(paper.open(eq(higher), argThat(value -> sameAmount(value, "250")), eq(NOW)))
                .thenReturn(position("position", higher, PaperHedgePosition.Status.OPENED));

        int opened = job(properties("0", "1")).openApproved(NOW);

        assertEquals(1, opened);
        verify(paper).open(eq(higher), argThat(value -> sameAmount(value, "250")), eq(NOW));
        verify(scanner, never()).revalidateApprovedOpportunity(eq("lower"), any(), any());
    }

    @Test
    void usesConfiguredNotionalWhenItIsLowerThanExecutableCapacity() {
        CrossExchangeOpportunity opportunity = opportunity("approved", "BTCUSDT", "0.0008", "250");
        when(scanner.approvedOpportunities(NOW)).thenReturn(List.of(opportunity));
        when(scanner.revalidateApprovedOpportunity(eq("approved"),
                argThat(value -> sameAmount(value, "100")), eq(NOW))).thenReturn(Optional.of(opportunity));
        when(paper.positions()).thenReturn(List.of());
        when(positions.selectList(any())).thenReturn(List.of());
        when(paper.open(eq(opportunity), argThat(value -> sameAmount(value, "100")), eq(NOW)))
                .thenReturn(position("position", opportunity, PaperHedgePosition.Status.OPENED));

        int opened = job(properties("100", "1")).openApproved(NOW);

        assertEquals(1, opened);
        verify(paper).open(eq(opportunity), argThat(value -> sameAmount(value, "100")), eq(NOW));
    }

    @Test
    void skipsSymbolsThatAlreadyHaveActivePaperPositions() {
        CrossExchangeOpportunity opportunity = opportunity("approved", "BTCUSDT", "0.0008", "250");
        when(scanner.approvedOpportunities(NOW)).thenReturn(List.of(opportunity));
        when(paper.positions()).thenReturn(List.of(position("existing", opportunity,
                PaperHedgePosition.Status.OPENED)));
        when(positions.selectList(any())).thenReturn(List.of());

        int opened = job(properties("0", "1")).openApproved(NOW);

        assertEquals(0, opened);
        verify(scanner, never()).revalidateApprovedOpportunity(any(), any(), any());
        verify(paper, never()).open(any(), any(), any());
    }

    @Test
    void skipsScopedCircuitAndContinuesWithOtherApprovedSymbols() {
        CrossExchangeOpportunity blocked = opportunity("blocked", "BTCUSDT", "0.0009", "250");
        CrossExchangeOpportunity allowed = opportunity("allowed", "ETHUSDT", "0.0008", "200");
        when(scanner.approvedOpportunities(NOW)).thenReturn(List.of(blocked, allowed));
        when(paper.isCircuitOpen(blocked, NOW)).thenReturn(true);
        when(scanner.revalidateApprovedOpportunity(eq("allowed"),
                argThat(value -> sameAmount(value, "200")), eq(NOW))).thenReturn(Optional.of(allowed));
        when(paper.positions()).thenReturn(List.of());
        when(positions.selectList(any())).thenReturn(List.of());
        when(paper.open(eq(allowed), argThat(value -> sameAmount(value, "200")), eq(NOW)))
                .thenReturn(position("position", allowed, PaperHedgePosition.Status.OPENED));

        int opened = job(properties("0", "1")).openApproved(NOW);

        assertEquals(1, opened);
        verify(scanner, never()).revalidateApprovedOpportunity(eq("blocked"), any(), any());
        verify(paper).open(eq(allowed), argThat(value -> sameAmount(value, "200")), eq(NOW));
    }

    @Test
    void skipsSymbolsThatAlreadyHaveActiveDbPositionsAfterRestart() {
        CrossExchangeOpportunity opportunity = opportunity("approved", "BTCUSDT", "0.0008", "250");
        when(scanner.approvedOpportunities(NOW)).thenReturn(List.of(opportunity));
        when(paper.positions()).thenReturn(List.of());
        when(positions.selectList(any())).thenReturn(List.of(
                new CrossExchangeArbPositionEntity().setPositionCode("db-position")
                        .setSymbol("BTCUSDT").setStatus(PaperHedgePosition.Status.OPENED.name())));

        int opened = job(properties("0", "1")).openApproved(NOW);

        assertEquals(0, opened);
        verify(scanner, never()).revalidateApprovedOpportunity(any(), any(), any());
        verify(paper, never()).open(any(), any(), any());
    }

    @Test
    void skipsWhenRevalidationNoLongerApprovesOpportunity() {
        CrossExchangeOpportunity opportunity = opportunity("approved", "BTCUSDT", "0.0008", "250");
        when(scanner.approvedOpportunities(NOW)).thenReturn(List.of(opportunity));
        when(scanner.revalidateApprovedOpportunity(eq("approved"), any(BigDecimal.class), eq(NOW)))
                .thenReturn(Optional.empty());
        when(paper.positions()).thenReturn(List.of());
        when(positions.selectList(any())).thenReturn(List.of());

        int opened = job(properties("0", "1")).openApproved(NOW);

        assertEquals(0, opened);
        verify(paper, never()).open(any(), any(), any());
    }

    @Test
    void doesNotRunWhenPaperIsPaused() {
        CrossExchangeOpportunity opportunity = opportunity("approved", "BTCUSDT", "0.0008", "250");
        when(scanner.lastEvaluated()).thenReturn(List.of(opportunity));
        when(paper.isPaused()).thenReturn(true);

        int opened = job(properties("0", "1")).openApproved(NOW);

        assertEquals(0, opened);
        verify(scanner, never()).lastEvaluated();
        verify(paper, never()).open(any(), any(), any());
    }

    @Test
    void rejectsDirectionOutsideConfiguredAllowList() {
        CrossExchangeOpportunity opportunity = opportunity("approved", "BTCUSDT", "0.0008", "250");
        when(scanner.approvedOpportunities(NOW)).thenReturn(List.of(opportunity));
        when(paper.positions()).thenReturn(List.of());
        when(positions.selectList(any())).thenReturn(List.of());
        MockEnvironment environment = baseEnvironment("200", "1")
                .withProperty("arbitrage.multi-exchange.auto-paper-allowed-pairs", "BITGET>OKX");

        int opened = job(new MultiExchangeFundingRateProperties(environment)).openApproved(NOW);

        assertEquals(0, opened);
        verify(scanner, never()).revalidateApprovedOpportunity(any(), any(), any());
    }

    @Test
    void coolsDownFailedOpportunityInsteadOfRetryingEveryCycle() {
        CrossExchangeOpportunity opportunity = opportunity("approved", "BTCUSDT", "0.0008", "250");
        when(scanner.approvedOpportunities(any())).thenReturn(List.of(opportunity));
        when(scanner.revalidateApprovedOpportunity(eq("approved"), any(BigDecimal.class), any()))
                .thenReturn(Optional.empty());
        when(paper.positions()).thenReturn(List.of());
        when(positions.selectList(any())).thenReturn(List.of());
        CrossExchangeAutoPaperOpenJob job = job(properties("200", "1"));

        assertEquals(0, job.openApproved(NOW));
        assertEquals(0, job.openApproved(NOW.plusSeconds(30)));

        verify(scanner).revalidateApprovedOpportunity(eq("approved"), any(BigDecimal.class), eq(NOW));
        verify(scanner, never()).revalidateApprovedOpportunity(eq("approved"), any(BigDecimal.class),
                eq(NOW.plusSeconds(30)));
    }

    private CrossExchangeAutoPaperOpenJob job(MultiExchangeFundingRateProperties properties) {
        return new CrossExchangeAutoPaperOpenJob(scanner, paper, positions, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MultiExchangeFundingRateProperties properties(String notional, String maxPerCycle) {
        return new MultiExchangeFundingRateProperties(baseEnvironment(notional, maxPerCycle));
    }

    private MockEnvironment baseEnvironment(String notional, String maxPerCycle) {
        return new MockEnvironment()
                .withProperty("arbitrage.multi-exchange.auto-paper-open-enabled", "true")
                .withProperty("arbitrage.multi-exchange.auto-paper-open-notional", notional)
                .withProperty("arbitrage.multi-exchange.auto-paper-open-max-per-cycle", maxPerCycle);
    }

    private boolean sameAmount(BigDecimal actual, String expected) {
        return actual != null && actual.compareTo(new BigDecimal(expected)) == 0;
    }

    private PaperHedgePosition position(String id, CrossExchangeOpportunity opportunity,
            PaperHedgePosition.Status status) {
        return new PaperHedgePosition(id, opportunity, null, null, BigDecimal.ONE, status,
                NOW, NOW, null);
    }

    private CrossExchangeOpportunity rejected(String id) {
        CrossExchangeOpportunity opportunity = opportunity(id, "SOLUSDT", "0.0010", "300");
        return new CrossExchangeOpportunity(opportunity.opportunityId(), opportunity.symbol(),
                opportunity.longQuote(), opportunity.shortQuote(), opportunity.hourlyFundingSpread(),
                opportunity.estimatedNetYield(), opportunity.longEntryPrice(),
                opportunity.shortEntryPrice(), opportunity.executableNotional(),
                opportunity.firstLegExchange(), CrossExchangeOpportunity.Status.REJECTED,
                "TEST_REJECTION", opportunity.discoveredAt());
    }

    private CrossExchangeOpportunity opportunity(String id, String symbol, String netYield,
            String executableNotional) {
        NormalizedFundingRateSnapshot longQuote = new NormalizedFundingRateSnapshot(
                Exchange.OKX, symbol, new BigDecimal("-0.001"), 8,
                NOW.plusSeconds(3600), new BigDecimal("100"), new BigDecimal("100"), NOW);
        NormalizedFundingRateSnapshot shortQuote = new NormalizedFundingRateSnapshot(
                Exchange.BITGET, symbol, new BigDecimal("0.001"), 8,
                NOW.plusSeconds(3600), new BigDecimal("100"), new BigDecimal("100"), NOW);
        return new CrossExchangeOpportunity(id, symbol, longQuote, shortQuote,
                new BigDecimal("0.00025"), new BigDecimal(netYield), new BigDecimal("100"),
                new BigDecimal("100"), new BigDecimal(executableNotional), Exchange.OKX,
                CrossExchangeOpportunity.Status.APPROVED, null, NOW);
    }
}
