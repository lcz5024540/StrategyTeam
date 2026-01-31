package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.market.OkxTrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class OkxPublicDataServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxPublicDataService okxPublicDataService;

    @Mock
    private Call<OkxRestResponse<OkxTrade>> callTrades;
    @Mock
    private Call<OkxRestResponse<Map<String, Object>>> callMap;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        okxPublicDataService = new OkxPublicDataService(okxApi);
    }

    @Test
    public void testGetPublicOptionTrades() throws Exception {
        when(okxApi.getPublicOptionTrades(anyString(), anyInt())).thenReturn(callTrades);
        when(callTrades.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxTrade> response = okxPublicDataService.getPublicOptionTrades("BTC-USD-SWAP", 100);
        assertNotNull(response);
        verify(okxApi).getPublicOptionTrades("BTC-USD-SWAP", 100);
    }
}
