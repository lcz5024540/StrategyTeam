package com.metaverse.coin.strategy.job.funding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.PaperHedgePosition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossExchangeFundingSettlementJobTest {

    @Test
    void skipsSettlementBeforePositionOpened() {
        Instant settlementAt = Instant.parse("2026-08-20T08:00:00Z");
        PaperHedgePosition position = position(settlementAt.plusSeconds(60), settlementAt);
        CrossExchangePaperTradingService paper = mock(CrossExchangePaperTradingService.class);
        CrossExchangeFundingSettlementMapper mapper = mock(CrossExchangeFundingSettlementMapper.class);
        when(paper.positions()).thenReturn(List.of(position));

        new CrossExchangeFundingSettlementJob(paper, mapper).settle(settlementAt.plusSeconds(120));

        verify(mapper, never()).insert(any());
    }

    @Test
    void databaseRecordMakesSettlementIdempotentAcrossRestart() {
        Instant settlementAt = Instant.parse("2026-08-20T08:00:00Z");
        PaperHedgePosition position = position(settlementAt.minusSeconds(60), settlementAt);
        CrossExchangePaperTradingService paper = mock(CrossExchangePaperTradingService.class);
        CrossExchangeFundingSettlementMapper mapper = mock(CrossExchangeFundingSettlementMapper.class);
        when(paper.positions()).thenReturn(List.of(position));
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        new CrossExchangeFundingSettlementJob(paper, mapper).settle(settlementAt.plusSeconds(120));

        verify(mapper, never()).insert(any());
    }

    @Test
    void recordsBothPaperFundingLegs() {
        Instant settlementAt = Instant.parse("2026-08-20T08:00:00Z");
        PaperHedgePosition position = position(settlementAt.minusSeconds(60), settlementAt);
        CrossExchangePaperTradingService paper = mock(CrossExchangePaperTradingService.class);
        CrossExchangeFundingSettlementMapper mapper = mock(CrossExchangeFundingSettlementMapper.class);
        when(paper.positions()).thenReturn(List.of(position));
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        new CrossExchangeFundingSettlementJob(paper, mapper).settle(settlementAt.plusSeconds(120));

        ArgumentCaptor<CrossExchangeFundingSettlementEntity> captor =
                ArgumentCaptor.forClass(CrossExchangeFundingSettlementEntity.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertEquals(2, captor.getAllValues().size());
        assertEquals(0, captor.getAllValues().get(0).getExpectedAmount()
                .compareTo(captor.getAllValues().get(0).getActualAmount()));
    }

    private PaperHedgePosition position(Instant openedAt, Instant settlementAt) {
        NormalizedFundingRateSnapshot longQuote = quote(Exchange.BINANCE, "-0.001", settlementAt);
        NormalizedFundingRateSnapshot shortQuote = quote(Exchange.OKX, "-0.0001", settlementAt);
        CrossExchangeOpportunity opportunity = new CrossExchangeOpportunity("opportunity", "BTCUSDT",
                longQuote, shortQuote, new BigDecimal("0.0001125"), new BigDecimal("0.0001"),
                new BigDecimal("100"), CrossExchangeOpportunity.Status.APPROVED, null,
                openedAt.minusSeconds(180));
        return new PaperHedgePosition("position", opportunity, null, null, BigDecimal.ONE,
                PaperHedgePosition.Status.OPENED, openedAt, openedAt, null);
    }

    private NormalizedFundingRateSnapshot quote(Exchange exchange, String rate, Instant settlementAt) {
        return new NormalizedFundingRateSnapshot(exchange, "BTCUSDT", new BigDecimal(rate), 8,
                settlementAt, new BigDecimal("100"), new BigDecimal("100"),
                settlementAt.minusSeconds(60));
    }
}
