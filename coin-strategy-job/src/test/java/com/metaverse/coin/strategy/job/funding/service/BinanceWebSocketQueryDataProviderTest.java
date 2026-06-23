package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
}
