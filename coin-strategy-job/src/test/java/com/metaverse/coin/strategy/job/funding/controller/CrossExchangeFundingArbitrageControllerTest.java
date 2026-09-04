package com.metaverse.coin.strategy.job.funding.controller;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeArbitragePersistenceService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunityScanService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePositionMonitorService;
import com.metaverse.coin.strategy.core.arbitrage.multi.ExchangeHealthRegistry;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import com.metaverse.coin.strategy.job.funding.service.CrossExchangeFundingReplayService;
import com.metaverse.coin.strategy.job.funding.service.CrossExchangePaperPnlService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossExchangeFundingArbitrageControllerTest {
    private final CrossExchangeArbOpportunityMapper opportunities = mock(CrossExchangeArbOpportunityMapper.class);
    private final CrossExchangeArbPositionMapper positions = mock(CrossExchangeArbPositionMapper.class);
    private final CrossExchangePaperTradingService paper = mock(CrossExchangePaperTradingService.class);
    private final CrossExchangeOpportunityScanService scanner = mock(CrossExchangeOpportunityScanService.class);
    private final ExchangeHealthRegistry health = new ExchangeHealthRegistry();
    private final CrossExchangeArbitragePersistenceService persistence = mock(CrossExchangeArbitragePersistenceService.class);
    private final CrossExchangeFundingReplayService replay = mock(CrossExchangeFundingReplayService.class);
    private final CrossExchangePositionMonitorService positionMonitor = mock(CrossExchangePositionMonitorService.class);
    private final CrossExchangePaperPnlService pnlService = mock(CrossExchangePaperPnlService.class);
    private final CrossExchangeFundingArbitrageController controller = new CrossExchangeFundingArbitrageController(
            opportunities, positions, paper, scanner, health, persistence, replay, positionMonitor, pnlService);

    @Test
    void opensOnlyServerApprovedOpportunity() {
        CrossExchangeOpportunity opportunity = opportunity("approved-id", CrossExchangeOpportunity.Status.APPROVED);
        PaperHedgePosition expected = new PaperHedgePosition("position", opportunity, null, null,
                BigDecimal.ONE, PaperHedgePosition.Status.OPENED, Instant.now(), Instant.now(), null);
        when(scanner.revalidateApprovedOpportunity(org.mockito.ArgumentMatchers.eq("approved-id"),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("100")), any(Instant.class)))
                .thenReturn(Optional.of(opportunity));
        when(paper.open(org.mockito.ArgumentMatchers.eq(opportunity),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("100")), any(Instant.class)))
                .thenReturn(expected);

        PaperHedgePosition actual = controller.paperOpen(
                new CrossExchangeFundingArbitrageController.PaperOpenRequest("approved-id", new BigDecimal("100")));

        assertEquals(expected, actual);
    }

    @Test
    void rejectsNonPositiveNotionalBeforeLookingUpOpportunity() {
        assertThrows(IllegalArgumentException.class, () -> controller.paperOpen(
                new CrossExchangeFundingArbitrageController.PaperOpenRequest("approved-id", BigDecimal.ZERO)));
        verify(scanner, never()).revalidateApprovedOpportunity(any(), any(BigDecimal.class), any());
    }

    @Test
    void scanPersistsApprovedAndRejectedCandidates() {
        CrossExchangeOpportunity approved = opportunity("approved-id", CrossExchangeOpportunity.Status.APPROVED);
        CrossExchangeOpportunity rejected = opportunity("rejected-id", CrossExchangeOpportunity.Status.REJECTED);
        when(scanner.scan(org.mockito.ArgumentMatchers.eq("BTCUSDT"), any(Instant.class)))
                .thenReturn(List.of(approved));
        when(scanner.lastEvaluated()).thenReturn(List.of(approved, rejected));

        assertEquals(List.of(approved), controller.scan("BTCUSDT"));
        verify(persistence).saveOpportunity(approved);
        verify(persistence).saveOpportunity(rejected);
    }

    @Test
    void delegatesOfflineReplayWithoutOpeningPaperPositions() {
        CrossExchangeFundingReplayService.ReplayReport report =
                new CrossExchangeFundingReplayService.ReplayReport("sample.csv", 2, 0,
                        2, 2, 1, java.util.Map.of(), List.of());
        when(replay.replay("sample.csv")).thenReturn(report);

        assertEquals(report, controller.replay("sample.csv"));
        verify(paper, never()).open(any(), any(), any());
    }

    @Test
    void manualCloseDelegatesToLatestPublicQuotes() {
        PaperHedgePosition expected = new PaperHedgePosition("position",
                opportunity("approved-id", CrossExchangeOpportunity.Status.APPROVED), null, null,
                BigDecimal.ONE, PaperHedgePosition.Status.CLOSED, Instant.now(), Instant.now(), null);
        when(positionMonitor.closeAtLatest(org.mockito.ArgumentMatchers.eq("position"), any(Instant.class)))
                .thenReturn(expected);

        assertEquals(expected, controller.paperClose("position"));
        verify(paper, never()).close(any(), any(Instant.class));
    }

    @Test
    void returnsPaperPnlAttribution() {
        CrossExchangePaperPnlService.PaperPnlSummary expected =
                new CrossExchangePaperPnlService.PaperPnlSummary("position", "PAPER_PUBLIC_MARK_TO_MARKET",
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 4, 2,
                        true, null);
        when(pnlService.summarize("position")).thenReturn(expected);

        assertEquals(expected, controller.paperPnl("position"));
    }

    @Test
    void returnsPaperPortfolioPnlForObservationWindow() {
        CrossExchangePaperPnlService.PortfolioPnlSummary expected =
                new CrossExchangePaperPnlService.PortfolioPnlSummary("PAPER_PUBLIC_CASHFLOW",
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        2, 1, 1, 4, 2, 7);
        when(pnlService.summarizePortfolio(7)).thenReturn(expected);

        assertEquals(expected, controller.paperPortfolioPnl(7));
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
