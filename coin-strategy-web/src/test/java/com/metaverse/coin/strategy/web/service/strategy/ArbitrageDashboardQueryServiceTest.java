package com.metaverse.coin.strategy.web.service.strategy;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SingleExchangeArbitragePositionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArbitrageDashboardQueryServiceTest {

    @Test
    void returnsPartialOverviewWhenFundingTablesAreNotDeployed() {
        CrossExchangeArbOpportunityMapper opportunityMapper = mock(CrossExchangeArbOpportunityMapper.class);
        CrossExchangeArbPositionMapper positionMapper = mock(CrossExchangeArbPositionMapper.class);
        CrossExchangeFundingSettlementMapper settlementMapper = mock(CrossExchangeFundingSettlementMapper.class);
        SingleExchangeArbitragePositionMapper singlePositionMapper = mock(SingleExchangeArbitragePositionMapper.class);
        DataAccessResourceFailureException unavailable = new DataAccessResourceFailureException("table unavailable");

        when(opportunityMapper.selectList(any())).thenThrow(unavailable);
        when(positionMapper.selectList(any())).thenThrow(unavailable);
        when(settlementMapper.selectList(any())).thenThrow(unavailable);

        ArbitrageDashboardQueryService service = new ArbitrageDashboardQueryService(
                opportunityMapper, positionMapper, settlementMapper, singlePositionMapper);

        var response = service.overview();

        assertTrue(response.partial());
        assertEquals(0, response.data().executableOpportunities());
        assertEquals(0, response.data().openPositions());
        assertEquals("UNKNOWN", response.data().riskState());
    }
}
