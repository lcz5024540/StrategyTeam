package com.metaverse.coin.strategy.web.service.strategy;

import com.metaverse.coin.strategy.common.dao.mapper.CrossExchangeArbOpportunityMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.CrossExchangeArbOpportunityEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FundingRateMatrixQueryServiceTest {

    @Test
    void groupsLatestPairwiseScanIntoOneRowPerSymbol() {
        CrossExchangeArbOpportunityMapper mapper = mock(CrossExchangeArbOpportunityMapper.class);
        Date previous = new Date(1_000L);
        Date latest = new Date(2_000L);
        when(mapper.selectList(any())).thenReturn(List.of(
                opportunity("BTCUSDT", "OKX", "HTX", "0.0010", "0.0020", "0.000125", previous),
                opportunity("BTCUSDT", "OKX", "HTX", "0.0024", "0.0048", "0.000300", latest),
                opportunity("BTCUSDT", "OKX", "BITGET", "0.0024", "0.0080", "0.000700", latest)));

        var response = new FundingRateMatrixQueryService(mapper).fundingRateMatrix();

        assertEquals(1, response.data().items().size());
        var btc = response.data().items().get(0);
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
    }

    @Test
    void sortsRowsByMaximumHourlySpreadDescendingAndKeepsMissingSpreadLast() {
        CrossExchangeArbOpportunityMapper mapper = mock(CrossExchangeArbOpportunityMapper.class);
        Date latest = new Date(2_000L);
        when(mapper.selectList(any())).thenReturn(List.of(
                opportunity("ETHUSDT", "OKX", "HTX", "0.0010", "0.0020", "0.000200", latest),
                opportunity("BTCUSDT", "OKX", "HTX", "0.0010", "0.0040", "0.000500", latest),
                opportunity("BTCUSDT", "OKX", "BITGET", "0.0010", "0.0030", null, latest),
                opportunity("SOLUSDT", "OKX", "BITGET", "0.0010", "0.0010", null, latest)));

        var rows = new FundingRateMatrixQueryService(mapper).fundingRateMatrix().data().items();

        assertEquals(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT"),
                rows.stream().map(row -> row.symbol()).toList());
        assertEquals("HTX", rows.get(0).shortExchange());
        assertEquals(new BigDecimal("0.000500"), rows.get(0).hourlyFundingSpread());
        assertNull(rows.get(2).hourlyFundingSpread());
    }

    private CrossExchangeArbOpportunityEntity opportunity(String symbol, String longExchange,
            String shortExchange, String longRate, String shortRate, String hourlySpread, Date sampledAt) {
        return new CrossExchangeArbOpportunityEntity().setSymbol(symbol).setLongExchange(longExchange)
                .setShortExchange(shortExchange).setLongFundingRate(new BigDecimal(longRate))
                .setLongFundingIntervalHours("HTX".equals(longExchange) ? 4 : 8)
                .setShortFundingRate(new BigDecimal(shortRate)).setShortFundingIntervalHours("HTX".equals(shortExchange) ? 4 : 8)
                .setHourlyFundingSpread(hourlySpread == null ? null : new BigDecimal(hourlySpread))
                .setEstimatedNetYield(BigDecimal.ZERO).setExecutableNotional(new BigDecimal("2000"))
                .setStatus("QUALIFIED").setDiscoveredAt(sampledAt).setUpdatedAt(sampledAt);
    }
}
