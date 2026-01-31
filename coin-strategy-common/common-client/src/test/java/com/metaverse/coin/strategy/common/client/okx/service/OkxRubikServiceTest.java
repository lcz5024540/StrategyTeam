package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.rubik.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OkxRubikServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxRubikService okxRubikService;

    @Mock
    private Call<OkxRestResponse<String>> callString;
    @Mock
    private Call<OkxRestResponse<OkxTakerVolume>> callTakerVolume;
    @Mock
    private Call<OkxRestResponse<OkxMarginLendingRatio>> callMarginLendingRatio;
    @Mock
    private Call<OkxRestResponse<OkxLongShortRatio>> callLongShortRatio;
    @Mock
    private Call<OkxRestResponse<OkxOpenInterestVolume>> callOpenInterestVolume;
    @Mock
    private Call<OkxRestResponse<OkxPutCallRatio>> callPutCallRatio;
    @Mock
    private Call<OkxRestResponse<OkxOpenInterestVolumeExpiry>> callOpenInterestVolumeExpiry;
    @Mock
    private Call<OkxRestResponse<OkxOpenInterestVolumeStrike>> callOpenInterestVolumeStrike;
    @Mock
    private Call<OkxRestResponse<OkxTakerFlow>> callTakerFlow;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        okxRubikService = new OkxRubikService(okxApi);
    }

    private <T> void mockCall(Call<OkxRestResponse<T>> call) throws IOException {
        when(call.execute()).thenReturn(Response.success(new OkxRestResponse<>()));
    }

    @Test
    public void testGetSupportCoin() throws IOException {
        mockCall(callString);
        when(okxApi.getSupportCoin()).thenReturn(callString);
        assertNotNull(okxRubikService.getSupportCoin());
    }

    @Test
    public void testGetTakerVolume() throws IOException {
        mockCall(callTakerVolume);
        when(okxApi.getTakerVolume(any(), any(), any(), any(), any())).thenReturn(callTakerVolume);
        assertNotNull(okxRubikService.getTakerVolume("BTC", "SPOT", "123", "456", "5m"));
    }

    @Test
    public void testGetMarginLendingRatio() throws IOException {
        mockCall(callMarginLendingRatio);
        when(okxApi.getMarginLendingRatio(any(), any(), any(), any())).thenReturn(callMarginLendingRatio);
        assertNotNull(okxRubikService.getMarginLendingRatio("BTC", "123", "456", "5m"));
    }

    @Test
    public void testGetLongShortAccountRatio() throws IOException {
        mockCall(callLongShortRatio);
        when(okxApi.getLongShortAccountRatio(any(), any(), any(), any())).thenReturn(callLongShortRatio);
        assertNotNull(okxRubikService.getLongShortAccountRatio("BTC", "123", "456", "5m"));
    }

    @Test
    public void testGetOpenInterestVolume() throws IOException {
        mockCall(callOpenInterestVolume);
        when(okxApi.getOpenInterestVolume(any(), any(), any(), any(), any())).thenReturn(callOpenInterestVolume);
        assertNotNull(okxRubikService.getOpenInterestVolume("BTC", "SWAP", "123", "456", "5m"));
    }

    @Test
    public void testGetOptionOpenInterestVolume() throws IOException {
        mockCall(callOpenInterestVolume);
        when(okxApi.getOptionOpenInterestVolume(any(), any())).thenReturn(callOpenInterestVolume);
        assertNotNull(okxRubikService.getOptionOpenInterestVolume("BTC", "5m"));
    }

    @Test
    public void testGetOptionOpenInterestVolumeRatio() throws IOException {
        mockCall(callPutCallRatio);
        when(okxApi.getOptionOpenInterestVolumeRatio(any(), any())).thenReturn(callPutCallRatio);
        assertNotNull(okxRubikService.getOptionOpenInterestVolumeRatio("BTC", "5m"));
    }

    @Test
    public void testGetOptionOpenInterestVolumeExpiry() throws IOException {
        mockCall(callOpenInterestVolumeExpiry);
        when(okxApi.getOptionOpenInterestVolumeExpiry(any(), any())).thenReturn(callOpenInterestVolumeExpiry);
        assertNotNull(okxRubikService.getOptionOpenInterestVolumeExpiry("BTC", "5m"));
    }

    @Test
    public void testGetOptionOpenInterestVolumeStrike() throws IOException {
        mockCall(callOpenInterestVolumeStrike);
        when(okxApi.getOptionOpenInterestVolumeStrike(any(), any(), any())).thenReturn(callOpenInterestVolumeStrike);
        assertNotNull(okxRubikService.getOptionOpenInterestVolumeStrike("BTC", "123", "5m"));
    }

    @Test
    public void testGetTakerFlow() throws IOException {
        mockCall(callTakerFlow);
        when(okxApi.getTakerFlow(any(), any())).thenReturn(callTakerFlow);
        assertNotNull(okxRubikService.getTakerFlow("BTC", "5m"));
    }
}
