package com.metaverse.coin.strategy.web.service.strategy;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArbitrageDashboardQueryServiceTest {

    @Test
    void aggregatesTradingCashflowAndFundingByShanghaiDay() {
        CrossExchangeArbOpportunityMapper opportunityMapper = mock(CrossExchangeArbOpportunityMapper.class);
        CrossExchangeArbPositionMapper positionMapper = mock(CrossExchangeArbPositionMapper.class);
        CrossExchangeArbOrderMapper orderMapper = mock(CrossExchangeArbOrderMapper.class);
        CrossExchangeFundingSettlementMapper settlementMapper = mock(CrossExchangeFundingSettlementMapper.class);
        SingleExchangeArbitragePositionMapper singlePositionMapper = mock(SingleExchangeArbitragePositionMapper.class);
        Date occurredAt = Date.from(Instant.parse("2026-09-01T16:30:00Z"));
        CrossExchangeArbOrderEntity longOrder = new CrossExchangeArbOrderEntity().setLegSide("LONG")
                .setFilledQuantity(new BigDecimal("2")).setAveragePrice(new BigDecimal("100"))
                .setFeeAmount(new BigDecimal("0.10")).setCreatedAt(occurredAt);
        CrossExchangeArbOrderEntity shortOrder = new CrossExchangeArbOrderEntity().setLegSide("SHORT")
                .setFilledQuantity(new BigDecimal("2")).setAveragePrice(new BigDecimal("101"))
                .setFeeAmount(new BigDecimal("0.10")).setCreatedAt(occurredAt);
        CrossExchangeFundingSettlementEntity funding = new CrossExchangeFundingSettlementEntity()
                .setActualAmount(new BigDecimal("0.50")).setSettlementTime(occurredAt);
        when(orderMapper.selectList(any())).thenReturn(List.of(longOrder, shortOrder));
        when(settlementMapper.selectList(any())).thenReturn(List.of(funding));
        ArbitrageDashboardQueryService service = new ArbitrageDashboardQueryService(
                opportunityMapper, positionMapper, orderMapper, settlementMapper, singlePositionMapper);

        var points = service.timeseries().data();

        assertEquals(1, points.size());
        assertEquals("2026-09-02", points.get(0).bucketAt());
        assertEquals("2", points.get(0).tradingCashflowPnl().toPlainString());
        assertEquals("0.50", points.get(0).fundingPnl().toPlainString());
        assertEquals("0.20", points.get(0).fee().toPlainString());
        assertEquals("2.30", points.get(0).netPnl().toPlainString());
        assertEquals("2.30", points.get(0).cumulativeNetPnl().toPlainString());
    }

    @Test
    void groupsLatestPairwiseSnapshotIntoOneFundingRateRowPerSymbol() {
        CrossExchangeArbOpportunityMapper opportunityMapper = mock(CrossExchangeArbOpportunityMapper.class);
        Date oldSample = new Date(1_000L);
        Date currentSample = new Date(2_000L);
        when(opportunityMapper.selectList(any())).thenReturn(List.of(
                opportunity("old-btc", "BTCUSDT", "OKX", "HTX", "0.0010", "0.0020", "0.000125", oldSample,
                        "REJECTED", "SETTLEMENT_TIME_SKEW"),
                opportunity("current-btc-a", "BTCUSDT", "OKX", "HTX", "0.0024", "0.0048", "0.000300", currentSample,
                        "REJECTED", "SETTLEMENT_TIME_SKEW"),
                opportunity("current-btc-b", "BTCUSDT", "OKX", "BITGET", "0.0024", "0.0080", "0.000700", currentSample,
                        "QUALIFIED", null),
                opportunity("current-eth", "ETHUSDT", "HTX", "HYPERLIQUID", "-0.0008", "0.0010", "0.001800", currentSample,
                        "REJECTED", "COST_COVERAGE_BELOW_THRESHOLD")));

        ArbitrageDashboardQueryService service = new ArbitrageDashboardQueryService(opportunityMapper,
                mock(CrossExchangeArbPositionMapper.class), mock(CrossExchangeArbOrderMapper.class),
                mock(CrossExchangeFundingSettlementMapper.class), mock(SingleExchangeArbitragePositionMapper.class));

        var response = service.fundingRateMatrix();

        assertEquals(List.of("BINANCE", "OKX", "HTX", "BITGET", "HYPERLIQUID"), response.data().exchanges());
        assertEquals(2, response.data().items().size());
        var btc = response.data().items().get(1);
        assertEquals("BTCUSDT", btc.symbol());
        assertEquals(new BigDecimal("0.0024"), btc.ratesByExchange().get("OKX"));
        assertEquals(new BigDecimal("0.0048"), btc.ratesByExchange().get("HTX"));
        assertEquals(new BigDecimal("0.0080"), btc.ratesByExchange().get("BITGET"));
        assertEquals(8, btc.intervalHoursByExchange().get("OKX"));
        assertEquals(4, btc.intervalHoursByExchange().get("HTX"));
        assertNull(btc.ratesByExchange().get("BINANCE"));
        assertEquals("OKX", btc.longExchange());
        assertEquals("BITGET", btc.shortExchange());
        assertEquals(new BigDecimal("0.000700"), btc.hourlyFundingSpread());
        assertEquals("QUALIFIED", btc.status());
    }

    @Test
    void returnsPartialOverviewWhenFundingTablesAreNotDeployed() {
        CrossExchangeArbOpportunityMapper opportunityMapper = mock(CrossExchangeArbOpportunityMapper.class);
        CrossExchangeArbPositionMapper positionMapper = mock(CrossExchangeArbPositionMapper.class);
        CrossExchangeArbOrderMapper orderMapper = mock(CrossExchangeArbOrderMapper.class);
        CrossExchangeFundingSettlementMapper settlementMapper = mock(CrossExchangeFundingSettlementMapper.class);
        SingleExchangeArbitragePositionMapper singlePositionMapper = mock(SingleExchangeArbitragePositionMapper.class);
        DataAccessResourceFailureException unavailable = new DataAccessResourceFailureException("table unavailable");

        when(opportunityMapper.selectList(any())).thenThrow(unavailable);
        when(positionMapper.selectList(any())).thenThrow(unavailable);
        when(settlementMapper.selectList(any())).thenThrow(unavailable);

        ArbitrageDashboardQueryService service = new ArbitrageDashboardQueryService(
                opportunityMapper, positionMapper, orderMapper, settlementMapper, singlePositionMapper);

        var response = service.overview();

        assertTrue(response.partial());
        assertEquals(0, response.data().executableOpportunities());
        assertEquals(0, response.data().openPositions());
        assertEquals("UNKNOWN", response.data().riskState());
        assertEquals("2000", response.data().paperCapitalPerExchange().toPlainString());
        assertEquals("3", response.data().maximumPaperLeverage().toPlainString());
    }

    private CrossExchangeArbOpportunityEntity opportunity(String code, String symbol, String longExchange,
            String shortExchange, String longRate, String shortRate, String hourlySpread, Date sampledAt,
            String status, String rejectionReason) {
        return new CrossExchangeArbOpportunityEntity().setOpportunityCode(code).setSymbol(symbol)
                .setLongExchange(longExchange).setShortExchange(shortExchange)
                .setLongFundingRate(new BigDecimal(longRate)).setLongFundingIntervalHours("HTX".equals(longExchange) ? 4 : 8)
                .setShortFundingRate(new BigDecimal(shortRate)).setShortFundingIntervalHours("HTX".equals(shortExchange) ? 4 : 8)
                .setHourlyFundingSpread(new BigDecimal(hourlySpread)).setEstimatedNetYield(BigDecimal.ZERO)
                .setExecutableNotional(new BigDecimal("2000")).setStatus(status).setRejectionReason(rejectionReason)
                .setDiscoveredAt(sampledAt).setUpdatedAt(sampledAt);
    }
}
