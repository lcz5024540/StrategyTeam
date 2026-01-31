package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal.*;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

public class OkxSignalBotServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxSignalBotService service;

    @Mock
    private Call<OkxRestResponse<PlaceSignalAlgoOrderRsp>> callPlace;
    @Mock
    private Call<OkxRestResponse<CancelSignalAlgoOrderRsp>> callCancel;
    @Mock
    private Call<OkxRestResponse<SignalAlgoOrder>> callGet;
    @Mock
    private Call<OkxRestResponse<ChangeSignalMarginBalanceRsp>> callChangeMargin;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OkxSignalBotService(okxApi);
    }

    @Test
    public void testPlaceSignalAlgoOrder() throws Exception {
        when(okxApi.placeSignalAlgoOrder(any(PlaceSignalAlgoOrderReq.class))).thenReturn(callPlace);
        when(callPlace.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<PlaceSignalAlgoOrderRsp> response = service.placeSignalAlgoOrder(new PlaceSignalAlgoOrderReq());
        assertNotNull(response);
        verify(okxApi).placeSignalAlgoOrder(any(PlaceSignalAlgoOrderReq.class));
    }

    @Test
    public void testCancelSignalAlgoOrder() throws Exception {
        when(okxApi.cancelSignalAlgoOrder(any(CancelSignalAlgoOrderReq.class))).thenReturn(callCancel);
        when(callCancel.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<CancelSignalAlgoOrderRsp> response = service.cancelSignalAlgoOrder(new CancelSignalAlgoOrderReq());
        assertNotNull(response);
        verify(okxApi).cancelSignalAlgoOrder(any(CancelSignalAlgoOrderReq.class));
    }

    @Test
    public void testGetSignalAlgoOrdersPending() throws Exception {
        when(okxApi.getSignalAlgoOrdersPending(anyMap())).thenReturn(callGet);
        when(callGet.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<SignalAlgoOrder> response = service.getSignalAlgoOrdersPending(Collections.emptyMap());
        assertNotNull(response);
        verify(okxApi).getSignalAlgoOrdersPending(anyMap());
    }

    @Test
    public void testChangeSignalMarginBalance() throws Exception {
        when(okxApi.changeSignalMarginBalance(any(ChangeSignalMarginBalanceReq.class))).thenReturn(callChangeMargin);
        when(callChangeMargin.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<ChangeSignalMarginBalanceRsp> response = service.changeSignalMarginBalance(new ChangeSignalMarginBalanceReq());
        assertNotNull(response);
        verify(okxApi).changeSignalMarginBalance(any(ChangeSignalMarginBalanceReq.class));
    }
}
