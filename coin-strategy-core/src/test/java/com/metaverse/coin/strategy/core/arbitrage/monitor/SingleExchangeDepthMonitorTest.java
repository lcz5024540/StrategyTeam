package com.metaverse.coin.strategy.core.arbitrage.monitor;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceFuturesService;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceMarketDataService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SingleExchangeDepthMonitorTest {

    @Mock
    private BinanceApiService binanceApiService;

    @Mock
    private BinanceMarketDataService marketDataService;

    @Mock
    private BinanceFuturesService futuresService;

    @Spy
    private SingleExchangeSpotFuturesProperties properties = new SingleExchangeSpotFuturesProperties();

    @InjectMocks
    private SingleExchangeDepthMonitor depthMonitor;

    @Test
    void testRetryUntilDepthSnapshotBecomesAvailable() throws Exception {
        setProperty("liquidityRetryCount", 3);
        setProperty("liquidityRetryIntervalMillis", 0L);

        when(binanceApiService.getMarketDataService()).thenReturn(marketDataService);
        when(binanceApiService.getFuturesService()).thenReturn(futuresService);
        when(marketDataService.getOrderBook("BTCUSDT", 20))
                .thenThrow(new RuntimeException("temporary"))
                .thenReturn(buildSpotOrderBook(
                        List.of(level("50000", "1")),
                        List.of(level("50010", "1"), level("50020", "1"))
                ));
        when(futuresService.getOrderBook("BTCUSDT", 20))
                .thenReturn(buildFuturesOrderBook(
                        List.of(level("50000", "1"), level("49990", "1")),
                        List.of(level("50010", "1"))
                ));

        SingleExchangeDepthSnapshot snapshot = depthMonitor.getDepthSnapshot("BTCUSDT");

        assertFalse(snapshot.isFallbackUsed());
        assertNotNull(snapshot.getSpotOrderBook());
        assertNotNull(snapshot.getFuturesOrderBook());
        verify(marketDataService, times(2)).getOrderBook("BTCUSDT", 20);
        verify(futuresService, times(1)).getOrderBook("BTCUSDT", 20);
    }

    @Test
    void testFallbackAfterRetryExhausted() throws Exception {
        setProperty("liquidityRetryCount", 3);
        setProperty("liquidityRetryIntervalMillis", 0L);

        when(binanceApiService.getMarketDataService()).thenReturn(marketDataService);
        when(binanceApiService.getFuturesService()).thenReturn(futuresService);
        when(marketDataService.getOrderBook("BTCUSDT", 20))
                .thenReturn(buildSpotOrderBook(List.of(level("50000", "1")), List.of()));
        when(futuresService.getOrderBook("BTCUSDT", 20))
                .thenReturn(buildFuturesOrderBook(List.of(level("50000", "1")), List.of()));

        SingleExchangeDepthSnapshot snapshot = depthMonitor.getDepthSnapshot("BTCUSDT");

        assertTrue(snapshot.isFallbackUsed());
        verify(marketDataService, times(3)).getOrderBook("BTCUSDT", 20);
        verify(futuresService, times(3)).getOrderBook("BTCUSDT", 20);
    }

    private BinanceOrderBook buildSpotOrderBook(List<List<String>> bids, List<List<String>> asks) {
        BinanceOrderBook orderBook = new BinanceOrderBook();
        orderBook.setBids(bids);
        orderBook.setAsks(asks);
        return orderBook;
    }

    private BinanceFuturesOrderBook buildFuturesOrderBook(List<List<String>> bids, List<List<String>> asks) {
        BinanceFuturesOrderBook orderBook = new BinanceFuturesOrderBook();
        orderBook.setBids(bids);
        orderBook.setAsks(asks);
        return orderBook;
    }

    private List<String> level(String price, String quantity) {
        return List.of(price, quantity);
    }

    private void setProperty(String fieldName, Object value) throws Exception {
        Field field = SingleExchangeSpotFuturesProperties.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(properties, value);
    }
}
