package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class OkxGridTradingServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxGridTradingService service;

    @Mock
    private Call<OkxRestResponse<AmendGridAlgoOrderRsp>> callAmend;
    @Mock
    private Call<OkxRestResponse<GridAlgoOrder>> callList;
    @Mock
    private Call<OkxRestResponse<AddGridInvestmentRsp>> callAddInvestment;
    @Mock
    private Call<OkxRestResponse<InstantTriggerGridAlgoOrderRsp>> callInstantTrigger;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OkxGridTradingService(okxApi);
    }

    @Test
    public void testAmendGridAlgoOrder() throws Exception {
        when(okxApi.amendGridAlgoOrder(any(AmendGridAlgoOrderReq.class))).thenReturn(callAmend);
        when(callAmend.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        AmendGridAlgoOrderReq req = new AmendGridAlgoOrderReq();
        OkxRestResponse<AmendGridAlgoOrderRsp> response = service.amendGridAlgoOrder(req);
        assertNotNull(response);
        verify(okxApi).amendGridAlgoOrder(req);
    }

    @Test
    public void testGetGridAlgoOrderDetails() throws Exception {
        when(okxApi.getGridAlgoOrderDetails(anyString(), anyString())).thenReturn(callList);
        when(callList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<GridAlgoOrder> response = service.getGridAlgoOrderDetails("1", "2");
        assertNotNull(response);
        verify(okxApi).getGridAlgoOrderDetails("1", "2");
    }

    @Test
    public void testGetGridAlgoOrdersPending() throws Exception {
        when(okxApi.getGridAlgoOrdersPending(anyMap())).thenReturn(callList);
        when(callList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<GridAlgoOrder> response = service.getGridAlgoOrdersPending(Collections.emptyMap());
        assertNotNull(response);
        verify(okxApi).getGridAlgoOrdersPending(any());
    }

    @Test
    public void testAddGridInvestment() throws Exception {
        when(okxApi.addGridInvestment(any(AddGridInvestmentReq.class))).thenReturn(callAddInvestment);
        when(callAddInvestment.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        AddGridInvestmentReq req = new AddGridInvestmentReq();
        OkxRestResponse<AddGridInvestmentRsp> response = service.addGridInvestment(req);
        assertNotNull(response);
        verify(okxApi).addGridInvestment(req);
    }

    @Test
    public void testInstantTriggerGridAlgoOrder() throws Exception {
        when(okxApi.instantTriggerGridAlgoOrder(any(InstantTriggerGridAlgoOrderReq.class))).thenReturn(callInstantTrigger);
        when(callInstantTrigger.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        InstantTriggerGridAlgoOrderReq req = new InstantTriggerGridAlgoOrderReq();
        OkxRestResponse<InstantTriggerGridAlgoOrderRsp> response = service.instantTriggerGridAlgoOrder(req);
        assertNotNull(response);
        verify(okxApi).instantTriggerGridAlgoOrder(req);
    }
}
