package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.market.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class OkxMarketDataServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxMarketDataService okxMarketDataService;

    @Mock
    private Call<OkxRestResponse<OkxTicker>> callTickers;
    @Mock
    private Call<OkxRestResponse<OkxIndexTicker>> callIndexTickers;
    @Mock
    private Call<OkxRestResponse<OkxOrderBook>> callOrderBook;
    @Mock
    private Call<OkxRestResponse<OkxCandle>> callCandles;
    @Mock
    private Call<OkxRestResponse<OkxTrade>> callTrades;
    @Mock
    private Call<OkxRestResponse<OkxPlatformVolume>> callVolume;
    @Mock
    private Call<OkxRestResponse<Map<String, Object>>> callMap;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        okxMarketDataService = new OkxMarketDataService(okxApi);
    }

    @Test
    public void testGetTickers() throws Exception {
        when(okxApi.getTickers(anyString(), any())).thenReturn(callTickers);
        when(callTickers.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxTicker> response = okxMarketDataService.getTickers("SWAP", null);
        assertNotNull(response);
        verify(okxApi).getTickers("SWAP", null);
    }

    @Test
    public void testGetOrderBook() throws Exception {
        when(okxApi.getOrderBook(anyString(), anyInt())).thenReturn(callOrderBook);
        when(callOrderBook.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxOrderBook> response = okxMarketDataService.getOrderBook("BTC-USDT", 1);
        assertNotNull(response);
        verify(okxApi).getOrderBook("BTC-USDT", 1);
    }

    @Test
    public void testGetCandles() throws Exception {
        when(okxApi.getCandles(anyString(), anyString(), any(), any(), any())).thenReturn(callCandles);
        when(callCandles.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxCandle> response = okxMarketDataService.getCandles("BTC-USDT", "1m", null, null, 100);
        assertNotNull(response);
        verify(okxApi).getCandles("BTC-USDT", "1m", null, null, 100);
    }

    @Test
    public void testGetTrades() throws Exception {
        when(okxApi.getTrades(anyString(), anyInt())).thenReturn(callTrades);
        when(callTrades.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxTrade> response = okxMarketDataService.getTrades("BTC-USDT", 100);
        assertNotNull(response);
        verify(okxApi).getTrades("BTC-USDT", 100);
    }

    @Test
    public void testGetPlatform24Volume() throws Exception {
        when(okxApi.getPlatform24Volume()).thenReturn(callVolume);
        when(callVolume.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxPlatformVolume> response = okxMarketDataService.getPlatform24Volume();
        assertNotNull(response);
        verify(okxApi).getPlatform24Volume();
    }

    @Test
    public void testGetOrderBookFull() throws Exception {
        when(okxApi.getOrderBookFull(anyString(), anyInt())).thenReturn(callOrderBook);
        when(callOrderBook.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxOrderBook> response = okxMarketDataService.getOrderBookFull("BTC-USDT", 1);
        assertNotNull(response);
        verify(okxApi).getOrderBookFull("BTC-USDT", 1);
    }

    @Test
    public void testGetOptionInstrumentFamilyTrades() throws Exception {
        when(okxApi.getOptionInstrumentFamilyTrades(anyString())).thenReturn(callTrades);
        when(callTrades.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxTrade> response = okxMarketDataService.getOptionInstrumentFamilyTrades("BTC-USD");
        assertNotNull(response);
        verify(okxApi).getOptionInstrumentFamilyTrades("BTC-USD");
    }

    @Test
    public void testGetCallAuctionDetails() throws Exception {
        when(okxApi.getCallAuctionDetails(anyString())).thenReturn(callMap);
        when(callMap.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<Map<String, Object>> response = okxMarketDataService.getCallAuctionDetails("BTC-USDT");
        assertNotNull(response);
        verify(okxApi).getCallAuctionDetails("BTC-USDT");
    }

    @Test
    public void testGetHistoryCandles() throws Exception {
        when(okxApi.getHistoryCandles(anyString(), anyString(), any(), any(), any())).thenReturn(callCandles);
        when(callCandles.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxCandle> response = okxMarketDataService.getHistoryCandles("BTC-USDT", "1m", null, null, 100);
        assertNotNull(response);
        verify(okxApi).getHistoryCandles("BTC-USDT", "1m", null, null, 100);
    }

    @Test
    public void testGetIndexCandles() throws Exception {
        when(okxApi.getIndexCandles(anyString(), anyString(), any(), any(), any())).thenReturn(callCandles);
        when(callCandles.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxCandle> response = okxMarketDataService.getIndexCandles("BTC-USDT", "1m", null, null, 100);
        assertNotNull(response);
        verify(okxApi).getIndexCandles("BTC-USDT", "1m", null, null, 100);
    }

    @Test
    public void testGetMarkPriceCandles() throws Exception {
        when(okxApi.getMarkPriceCandles(anyString(), anyString(), any(), any(), any())).thenReturn(callCandles);
        when(callCandles.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxCandle> response = okxMarketDataService.getMarkPriceCandles("BTC-USDT", "1m", null, null, 100);
        assertNotNull(response);
        verify(okxApi).getMarkPriceCandles("BTC-USDT", "1m", null, null, 100);
    }
}
