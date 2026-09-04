package com.metaverse.coin.strategy.job.funding.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeRiskSnapshotMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeRiskSnapshotEntity;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossExchangePaperPnlSnapshotJobTest {
    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    private final CrossExchangePaperPnlService pnlService = mock(CrossExchangePaperPnlService.class);
    private final CrossExchangeArbPositionMapper positions = mock(CrossExchangeArbPositionMapper.class);
    private final CrossExchangeRiskSnapshotMapper snapshots = mock(CrossExchangeRiskSnapshotMapper.class);

    @Test
    void persistsPortfolioAndOpenPositionPnlSnapshots() {
        when(pnlService.summarizePortfolio(7)).thenReturn(new CrossExchangePaperPnlService.PortfolioPnlSummary(
                "PAPER_PUBLIC_MARK_TO_MARKET", new BigDecimal("-2.0"), new BigDecimal("1.5"),
                new BigDecimal("0.3"), new BigDecimal("-0.8"),
                2, 1, 1, 4, 3, 7));
        when(positions.selectList(any())).thenReturn(List.of(new CrossExchangeArbPositionEntity()
                .setPositionCode("position-1").setOpportunityCode("opportunity-1")
                .setSymbol("BTCUSDT").setLongExchange("OKX").setShortExchange("BITGET")));
        when(pnlService.summarize("position-1")).thenReturn(new CrossExchangePaperPnlService.PaperPnlSummary(
                "position-1", "PAPER_PUBLIC_MARK_TO_MARKET", new BigDecimal("-1.2"),
                new BigDecimal("0.7"), new BigDecimal("0.1"), new BigDecimal("-0.6"),
                2, 1, true, null));

        int rows = job(true).snapshot(NOW);

        assertEquals(2, rows);
        verify(snapshots).insert(argThat(snapshot ->
                CrossExchangePaperPnlSnapshotJob.STAGE_PORTFOLIO.equals(snapshot.getCheckStage())
                        && "LOSS".equals(snapshot.getReason())
                        && snapshot.getDetailsJson().contains("\"totalPnl\":-0.8")
                        && snapshot.getDetailsJson().contains("\"windowDays\":7")));
        verify(snapshots).insert(argThat(snapshot ->
                "position-1".equals(snapshot.getPositionCode())
                        && CrossExchangePaperPnlSnapshotJob.STAGE_POSITION.equals(snapshot.getCheckStage())
                        && "LOSS".equals(snapshot.getReason())
                        && snapshot.getDetailsJson().contains("\"symbol\":\"BTCUSDT\"")
                        && snapshot.getDetailsJson().contains("\"markToMarketIncluded\":true")));
    }

    @Test
    void skipsWhenSnapshottingIsDisabled() {
        int rows = job(false).snapshot(NOW);

        assertEquals(0, rows);
        verify(pnlService, never()).summarizePortfolio(anyInt());
        verify(snapshots, never()).insert(any(CrossExchangeRiskSnapshotEntity.class));
    }

    private CrossExchangePaperPnlSnapshotJob job(boolean enabled) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("arbitrage.multi-exchange.paper-pnl-snapshot-enabled",
                        String.valueOf(enabled))
                .withProperty("arbitrage.multi-exchange.paper-pnl-snapshot-window-days", "7");
        return new CrossExchangePaperPnlSnapshotJob(pnlService, positions, snapshots,
                new MultiExchangeFundingRateProperties(environment), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
