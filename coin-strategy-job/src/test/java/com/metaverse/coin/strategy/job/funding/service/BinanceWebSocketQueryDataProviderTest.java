package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceFuturesService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesIncome;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BinanceWebSocketQueryDataProviderTest {

    @Test
    void returnsCachedSpotPriceWithoutHttpCall() {
        BinanceWebSocketProperties properties = new BinanceWebSocketProperties();
        BinanceWebSocketStreamService streamService = mock(BinanceWebSocketStreamService.class);
        BinanceApiService apiService = mock(BinanceApiService.class);
        when(streamService.getCachedSpotPrice("BTCUSDT"))
                .thenReturn(BinancePriceTicker.builder().symbol("BTCUSDT").price(new BigDecimal("50001")).build());

        BinanceWebSocketQueryDataProvider provider = new BinanceWebSocketQueryDataProvider(properties, streamService, apiService);

        assertEquals(new BigDecimal("50001"), provider.getSpotPrice("BTCUSDT").getPrice());
        verify(apiService, never()).getTickerPrice("BTCUSDT");
    }

    @Test
    void fallsBackToHttpAndWarmsCacheWhenCacheMisses() {
        BinanceWebSocketProperties properties = new BinanceWebSocketProperties();
        BinanceWebSocketStreamService streamService = mock(BinanceWebSocketStreamService.class);
        BinanceApiService apiService = mock(BinanceApiService.class);
        BinancePriceTicker fallback = BinancePriceTicker.builder()
                .symbol("BTCUSDT")
                .price(new BigDecimal("50002"))
                .build();
        when(streamService.getCachedSpotPrice("BTCUSDT")).thenReturn(null);
        when(apiService.getTickerPrice("BTCUSDT")).thenReturn(fallback);

        BinanceWebSocketQueryDataProvider provider = new BinanceWebSocketQueryDataProvider(properties, streamService, apiService);

        assertEquals(new BigDecimal("50002"), provider.getSpotPrice("BTCUSDT").getPrice());
        verify(apiService).getTickerPrice("BTCUSDT");
        verify(streamService).cacheSpotPrice(fallback);
    }

    @Test
    void getFundingFeeIncomeQueriesRestWithFundingFeeIncomeType() {
        // M3: 资金费实际入账属账户 user-data，不做 websocket 缓存，直查 REST /fapi/v1/income，incomeType 固定为 FUNDING_FEE。
        BinanceWebSocketProperties properties = new BinanceWebSocketProperties();
        BinanceWebSocketStreamService streamService = mock(BinanceWebSocketStreamService.class);
        BinanceApiService apiService = mock(BinanceApiService.class);
        BinanceFuturesService futuresService = mock(BinanceFuturesService.class);
        when(apiService.getFuturesService()).thenReturn(futuresService);
        BinanceFuturesIncome income = new BinanceFuturesIncome();
        income.setSymbol("BTCUSDT");
        income.setIncomeType("FUNDING_FEE");
        income.setIncome(new BigDecimal("1.25"));
        when(futuresService.getIncomeHistory("BTCUSDT", "FUNDING_FEE", 1000L, 2000L, null))
                .thenReturn(List.of(income));

        BinanceWebSocketQueryDataProvider provider = new BinanceWebSocketQueryDataProvider(properties, streamService, apiService);

        List<BinanceFuturesIncome> result = provider.getFundingFeeIncome("BTCUSDT", 1000L, 2000L);

        assertEquals(1, result.size());
        assertEquals(new BigDecimal("1.25"), result.get(0).getIncome());
        verify(futuresService).getIncomeHistory("BTCUSDT", "FUNDING_FEE", 1000L, 2000L, null);
        // income 不入 websocket 缓存，也不触发价格类缓存写入。
        verify(streamService, never()).getCachedSpotPrice("BTCUSDT");
    }
}
