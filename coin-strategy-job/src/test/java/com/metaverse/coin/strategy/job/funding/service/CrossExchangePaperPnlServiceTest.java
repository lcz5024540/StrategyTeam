package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeFundingSettlementMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeFundingSettlementEntity;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossExchangePaperPnlServiceTest {
    @Test
    void attributesBasisFundingAndEstimatedExecutionCosts() {
        CrossExchangeArbPositionMapper positions = mock(CrossExchangeArbPositionMapper.class);
        CrossExchangeArbOrderMapper orders = mock(CrossExchangeArbOrderMapper.class);
        CrossExchangeFundingSettlementMapper settlements = mock(CrossExchangeFundingSettlementMapper.class);
        when(positions.selectCount(any())).thenReturn(1L);
        when(orders.selectList(any())).thenReturn(List.of(
                order("BINANCE", "LONG", "1", "100"),
                order("OKX", "SHORT", "1", "101"),
                order("BINANCE", "SHORT", "1", "105"),
                order("OKX", "LONG", "1", "103")));
        when(settlements.selectList(any())).thenReturn(List.of(
                new CrossExchangeFundingSettlementEntity().setActualAmount(new BigDecimal("0.8")),
                new CrossExchangeFundingSettlementEntity().setActualAmount(new BigDecimal("0.2"))));

        CrossExchangePaperPnlService.PaperPnlSummary result = new CrossExchangePaperPnlService(
                positions, orders, settlements, new MultiExchangeFundingRateProperties())
                .summarize("position");

        assertEquals(0, new BigDecimal("3").compareTo(result.basisPnl()));
        assertEquals(0, new BigDecimal("1.0").compareTo(result.fundingIncome()));
        assertEquals(0, new BigDecimal("0.409").compareTo(result.estimatedExecutionCost()));
        assertEquals(0, new BigDecimal("3.591").compareTo(result.totalPnl()));
        assertEquals("PAPER_PUBLIC_ESTIMATE", result.source());
    }

    private CrossExchangeArbOrderEntity order(String exchange, String side, String quantity,
            String price) {
        return new CrossExchangeArbOrderEntity()
                .setExchangeName(exchange)
                .setLegSide(side)
                .setFilledQuantity(new BigDecimal(quantity))
                .setAveragePrice(new BigDecimal(price));
    }
}
