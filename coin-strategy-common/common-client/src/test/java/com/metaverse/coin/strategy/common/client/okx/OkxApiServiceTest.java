package com.metaverse.coin.strategy.common.client.okx;

import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.*;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

public class OkxApiServiceTest {

    @Mock
    private OkxApi okxApi;

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private Call<OkxRestResponse<OkxAccountConfig>> callAccountConfig;
    @Mock
    private Call<OkxRestResponse<OkxMaxSize>> callMaxSize;
    @Mock
    private Call<OkxRestResponse<OkxPositionRisk>> callPositionRisk;
    @Mock
    private Call<OkxRestResponse<OkxBill>> callBills;
    @Mock
    private Call<OkxRestResponse<OkxMaxAvailSize>> callMaxAvailSize;
    @Mock
    private Call<OkxRestResponse<OkxTradeFee>> callTradeFee;
    @Mock
    private Call<OkxRestResponse<OkxLeverageInfo>> callLeverageInfo;

    private OkxApiService okxApiService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        okxApiService = new OkxApiService(okxApi, okHttpClient, true);
    }

    @Test
    public void testGetAccountConfig() throws Exception {
        when(okxApi.getAccountConfig()).thenReturn(callAccountConfig);
        when(callAccountConfig.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        okxApiService.getAccountConfig();
        verify(okxApi).getAccountConfig();
    }

    @Test
    public void testGetMaxSize() throws Exception {
        when(okxApi.getMaxSize(anyMap())).thenReturn(callMaxSize);
        when(callMaxSize.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> map = new HashMap<>();
        okxApiService.getMaxSize(map);
        verify(okxApi).getMaxSize(map);
    }

    @Test
    public void testGetPositionRisk() throws Exception {
        when(okxApi.getPositionRisk(anyMap())).thenReturn(callPositionRisk);
        when(callPositionRisk.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> map = new HashMap<>();
        okxApiService.getPositionRisk(map);
        verify(okxApi).getPositionRisk(map);
    }

    @Test
    public void testGetBills() throws Exception {
        when(okxApi.getBills(anyMap())).thenReturn(callBills);
        when(callBills.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> map = new HashMap<>();
        okxApiService.getBills(map);
        verify(okxApi).getBills(map);
    }

    @Test
    public void testGetMaxAvailSize() throws Exception {
        when(okxApi.getMaxAvailSize(anyMap())).thenReturn(callMaxAvailSize);
        when(callMaxAvailSize.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> map = new HashMap<>();
        okxApiService.getMaxAvailSize(map);
        verify(okxApi).getMaxAvailSize(map);
    }

    @Test
    public void testGetTradeFee() throws Exception {
        when(okxApi.getTradeFee(anyMap())).thenReturn(callTradeFee);
        when(callTradeFee.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        okxApiService.getTradeFee("SPOT", "BTC-USDT", null, null);
        verify(okxApi).getTradeFee(anyMap());
    }

    @Test
    public void testGetLeverageInfo() throws Exception {
        when(okxApi.getLeverageInfo(anyMap())).thenReturn(callLeverageInfo);
        when(callLeverageInfo.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        okxApiService.getLeverageInfo("BTC-USDT", "cross");
        verify(okxApi).getLeverageInfo(anyMap());
    }
}
