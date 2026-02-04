package com.metaverse.coin.strategy.common.client.binance.mock.service;

import com.binance.connector.futures.client.impl.CMFuturesClientImpl;
import com.metaverse.coin.strategy.common.client.binance.service.BinanceCoinFuturesService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.*;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BinanceCoinFuturesServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private CMFuturesClientImpl futuresClient;

    @Mock
    private OkHttpClient httpClient;
    @Mock
    private Call call;

    private BinanceCoinFuturesService coinFuturesService;

    @BeforeEach
    public void setUp() {
        coinFuturesService = new BinanceCoinFuturesService(futuresClient, null, null, null, httpClient);
    }

    @Test
    public void testCheckServerTime() {
        String mockResponse = "{\"serverTime\": 1617283200000}";
        when(futuresClient.market().time()).thenReturn(mockResponse);

        Long serverTime = coinFuturesService.checkServerTime();

        assertEquals(1617283200000L, serverTime);
    }

    @Test
    public void testGetExchangeInfo() {
        String mockResponse = "{\"timezone\": \"UTC\", \"serverTime\": 1617283200000, \"rateLimits\": [], \"exchangeFilters\": [], \"symbols\": []}";
        when(futuresClient.market().exchangeInfo()).thenReturn(mockResponse);

        BinanceFuturesExchangeInfo exchangeInfo = coinFuturesService.getExchangeInfo();

        assertNotNull(exchangeInfo);
        assertEquals("UTC", exchangeInfo.getTimezone());
        assertEquals(1617283200000L, exchangeInfo.getServerTime());
    }

    @Test
    public void testGetKlines() {
        String mockResponse = "[[1617283200000, \"59000.00\", \"59100.00\", \"58900.00\", \"59050.00\", \"100.00\", 1617283260000, \"5905000.00\", 1000, \"50.00\", \"2952500.00\", \"0\"]]";
        when(futuresClient.market().klines(any())).thenReturn(mockResponse);

        List<BinanceFuturesKline> klines = coinFuturesService.getKlines("BTCUSD_PERP", "1m", null, null, null);

        assertNotNull(klines);
        assertEquals(1, klines.size());
        BinanceFuturesKline kline = klines.get(0);
        assertEquals(1617283200000L, kline.getOpenTime());
        assertEquals(new BigDecimal("59000.00"), kline.getOpen());
    }
    
    @Test
    public void testGetOpenInterestHist() {
        String mockResponse = "[{\"symbol\":\"BTCUSD_PERP\", \"pair\":\"BTCUSD\", \"sumOpenInterest\":\"1000\", \"sumOpenInterestValue\":\"59000000\", \"timestamp\":1617283200000}]";
        when(futuresClient.market().openInterestStatistics(any())).thenReturn(mockResponse);
        
        List<BinanceFuturesOpenInterestHist> hist = coinFuturesService.getOpenInterestHist("BTCUSD", "PERPETUAL", "5m", 10, null, null);
        
        assertNotNull(hist);
        assertEquals(1, hist.size());
        BinanceFuturesOpenInterestHist item = hist.get(0);
        assertEquals("BTCUSD_PERP", item.getSymbol());
        assertEquals(new BigDecimal("1000"), item.getSumOpenInterest());
    }

    @Test
    public void testGetAllForceOrders() throws IOException {
        String mockResponse = "[{\"symbol\":\"BTCUSD_PERP\", \"price\":\"50000\", \"origQty\":\"100\", \"avgPrice\":\"50000\"}]";

        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://test.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.get("application/json"), mockResponse))
                .build();

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        List<BinanceFuturesForceOrder> orders = coinFuturesService.getAllForceOrders("BTCUSD_PERP", null, null, null, 10);

        assertNotNull(orders);
        assertEquals(1, orders.size());
        assertEquals(new BigDecimal("50000"), orders.get(0).getAvgPrice());
    }

    @Test
    public void testGetIndexConstituents() throws IOException {
        String mockResponse = "{\"symbol\":\"BTCUSD\", \"constituents\":[{\"exchange\":\"Binance\",\"symbol\":\"BTC/USD\"}]}";

        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://test.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.get("application/json"), mockResponse))
                .build();

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        BinanceFuturesConstituents result = coinFuturesService.getIndexConstituents("BTCUSD");

        assertNotNull(result);
        assertEquals("BTCUSD", result.getSymbol());
        assertEquals(1, result.getConstituents().size());
        assertEquals("Binance", result.getConstituents().get(0).getExchange());
    }
}
