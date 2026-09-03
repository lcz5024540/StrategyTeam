package com.metaverse.coin.strategy.web.service.strategy;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeExecutionFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        CrossExchangeExecutionFillMapper executionFillMapper = mock(CrossExchangeExecutionFillMapper.class);
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
                opportunityMapper, positionMapper, orderMapper, settlementMapper, executionFillMapper,
                singlePositionMapper);

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
    void returnsPartialOverviewWhenFundingTablesAreNotDeployed() {
        CrossExchangeArbOpportunityMapper opportunityMapper = mock(CrossExchangeArbOpportunityMapper.class);
        CrossExchangeArbPositionMapper positionMapper = mock(CrossExchangeArbPositionMapper.class);
        CrossExchangeArbOrderMapper orderMapper = mock(CrossExchangeArbOrderMapper.class);
        CrossExchangeFundingSettlementMapper settlementMapper = mock(CrossExchangeFundingSettlementMapper.class);
        CrossExchangeExecutionFillMapper executionFillMapper = mock(CrossExchangeExecutionFillMapper.class);
        SingleExchangeArbitragePositionMapper singlePositionMapper = mock(SingleExchangeArbitragePositionMapper.class);
        DataAccessResourceFailureException unavailable = new DataAccessResourceFailureException("table unavailable");

        when(opportunityMapper.selectList(any())).thenThrow(unavailable);
        when(positionMapper.selectList(any())).thenThrow(unavailable);
        when(settlementMapper.selectList(any())).thenThrow(unavailable);

        ArbitrageDashboardQueryService service = new ArbitrageDashboardQueryService(
                opportunityMapper, positionMapper, orderMapper, settlementMapper, executionFillMapper,
                singlePositionMapper);

        var response = service.overview();

        assertTrue(response.partial());
        assertEquals(0, response.data().executableOpportunities());
        assertEquals(0, response.data().openPositions());
        assertEquals("UNKNOWN", response.data().riskState());
        assertEquals("2000", response.data().paperCapitalPerExchange().toPlainString());
        assertEquals("3", response.data().maximumPaperLeverage().toPlainString());
    }
}
