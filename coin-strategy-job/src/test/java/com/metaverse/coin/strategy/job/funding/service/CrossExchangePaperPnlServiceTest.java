package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeMarketDataProvider;
import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedFundingRateSnapshot;
import com.metaverse.coin.strategy.core.arbitrage.multi.NormalizedOrderBookSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossExchangePaperPnlServiceTest {
    @Test
    void attributesBasisFundingAndPersistedTradingFees() {
        CrossExchangeArbPositionMapper positions = mock(CrossExchangeArbPositionMapper.class);
        CrossExchangeArbOrderMapper orders = mock(CrossExchangeArbOrderMapper.class);
        CrossExchangeFundingSettlementMapper settlements = mock(CrossExchangeFundingSettlementMapper.class);
        when(positions.selectOne(any())).thenReturn(position("position", "CLOSED"));
        when(orders.selectList(any())).thenReturn(List.of(
                order("BINANCE", "LONG", false, "1", "100"),
                order("OKX", "SHORT", false, "1", "101"),
                order("BINANCE", "SHORT", true, "1", "105"),
                order("OKX", "LONG", true, "1", "103")));
        when(settlements.selectList(any())).thenReturn(List.of(
                new CrossExchangeFundingSettlementEntity().setActualAmount(new BigDecimal("0.8")),
                new CrossExchangeFundingSettlementEntity().setActualAmount(new BigDecimal("0.2"))));

        CrossExchangePaperPnlService.PaperPnlSummary result = new CrossExchangePaperPnlService(
                positions, orders, settlements, new MultiExchangeFundingRateProperties())
                .summarize("position");

        assertEquals(0, new BigDecimal("3").compareTo(result.basisPnl()));
        assertEquals(0, new BigDecimal("1.0").compareTo(result.fundingIncome()));
        assertEquals(0, new BigDecimal("0.4").compareTo(result.estimatedExecutionCost()));
        assertEquals(0, new BigDecimal("3.6").compareTo(result.totalPnl()));
        assertEquals("PAPER_PUBLIC_CASHFLOW", result.source());
    }

    @Test
    void includesMarkToMarketBasisForOpenPositionsWhenFreshBooksExist() {
        CrossExchangeArbPositionMapper positions = mock(CrossExchangeArbPositionMapper.class);
        CrossExchangeArbOrderMapper orders = mock(CrossExchangeArbOrderMapper.class);
        CrossExchangeFundingSettlementMapper settlements = mock(CrossExchangeFundingSettlementMapper.class);
        Date now = Date.from(Instant.now());
        when(positions.selectOne(any())).thenReturn(new CrossExchangeArbPositionEntity()
                .setPositionCode("position").setStatus("OPENED").setSymbol("BTCUSDT")
                .setLongExchange("OKX").setShortExchange("BITGET")
                .setHedgedQuantity(new BigDecimal("1")).setOpenedAt(now));
        when(orders.selectList(any())).thenReturn(List.of(
                order("OKX", "LONG", false, "1", "100"),
                order("BITGET", "SHORT", false, "1", "101")));
        when(settlements.selectList(any())).thenReturn(List.of());

        CrossExchangePaperPnlService.PaperPnlSummary result = new CrossExchangePaperPnlService(
                positions, orders, settlements,
                List.of(provider(Exchange.OKX, "104", "105"), provider(Exchange.BITGET, "102", "103")),
                new MultiExchangeFundingRateProperties()).summarize("position");

        assertEquals(0, new BigDecimal("2").compareTo(result.basisPnl()));
        assertEquals(0, new BigDecimal("0.2").compareTo(result.estimatedExecutionCost()));
        assertEquals(0, new BigDecimal("1.8").compareTo(result.totalPnl()));
        assertEquals("PAPER_PUBLIC_MARK_TO_MARKET", result.source());
        assertTrue(result.markToMarketIncluded());
    }

    @Test
    void summarizesRecentPortfolioPnlForPaperObservationWindow() {
        CrossExchangeArbPositionMapper positions = mock(CrossExchangeArbPositionMapper.class);
        CrossExchangeArbOrderMapper orders = mock(CrossExchangeArbOrderMapper.class);
        CrossExchangeFundingSettlementMapper settlements = mock(CrossExchangeFundingSettlementMapper.class);
        when(positions.selectList(any())).thenReturn(List.of(
                position("p1", "OPENED"), position("p2", "CLOSED")));
        when(positions.selectOne(any())).thenReturn(position("p", "CLOSED"));
        when(orders.selectList(any())).thenReturn(List.of(
                order("OKX", "LONG", false, "1", "100"),
                order("BITGET", "SHORT", false, "1", "101")));
        when(settlements.selectList(any())).thenReturn(List.of(
                new CrossExchangeFundingSettlementEntity().setActualAmount(new BigDecimal("0.5"))));

        CrossExchangePaperPnlService.PortfolioPnlSummary result = new CrossExchangePaperPnlService(
                positions, orders, settlements, new MultiExchangeFundingRateProperties())
                .summarizePortfolio(7);

        assertEquals(2, result.positionCount());
        assertEquals(1, result.openPositionCount());
        assertEquals(1, result.closedPositionCount());
        assertEquals(4, result.orderCount());
        assertEquals(2, result.settlementCount());
        assertEquals(0, new BigDecimal("2").compareTo(result.basisPnl()));
        assertEquals(0, new BigDecimal("1.0").compareTo(result.fundingIncome()));
        assertEquals(0, new BigDecimal("0.4").compareTo(result.estimatedExecutionCost()));
        assertEquals(0, new BigDecimal("2.6").compareTo(result.totalPnl()));
        assertEquals("PAPER_PUBLIC_CASHFLOW", result.source());
        assertEquals(7, result.windowDays());
    }

    private CrossExchangeMarketDataProvider provider(Exchange exchange, String bid, String ask) {
        Instant now = Instant.now();
        return new CrossExchangeMarketDataProvider() {
            @Override
            public Exchange exchange() {
                return exchange;
            }

            @Override
            public List<String> supportedUsdtPerpetualSymbols() {
                return List.of("BTCUSDT");
            }

            @Override
            public NormalizedFundingRateSnapshot fundingRate(String canonicalSymbol) {
                return new NormalizedFundingRateSnapshot(exchange, canonicalSymbol, BigDecimal.ZERO,
                        8, now.plusSeconds(3600), new BigDecimal(bid), new BigDecimal(bid), now);
            }

            @Override
            public NormalizedOrderBookSnapshot orderBook(String canonicalSymbol, int depth) {
                return new NormalizedOrderBookSnapshot(exchange, canonicalSymbol,
                        new BigDecimal(bid), new BigDecimal(ask),
                        new BigDecimal("10000"), new BigDecimal("10000"),
                        List.of(new NormalizedOrderBookSnapshot.Level(new BigDecimal(bid),
                                new BigDecimal("10000"))),
                        List.of(new NormalizedOrderBookSnapshot.Level(new BigDecimal(ask),
                                new BigDecimal("10000"))),
                        now);
            }
        };
    }

    private CrossExchangeArbOrderEntity order(String exchange, String side, boolean reduceOnly,
            String quantity, String price) {
        return new CrossExchangeArbOrderEntity()
                .setExchangeName(exchange)
                .setLegSide(side)
                .setReduceOnly(reduceOnly)
                .setFilledQuantity(new BigDecimal(quantity))
                .setAveragePrice(new BigDecimal(price))
                .setFeeAmount(new BigDecimal("0.1"));
    }

    private CrossExchangeArbPositionEntity position(String code, String status) {
        return new CrossExchangeArbPositionEntity()
                .setPositionCode(code)
                .setStatus(status)
                .setOpenedAt(new Date());
    }
}
