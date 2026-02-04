package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.staking.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OkxEarnServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxEarnService service;

    @Mock
    private Call<OkxRestResponse<OkxStakingOffer>> callOffer;
    @Mock
    private Call<OkxRestResponse<OkxStakingPurchaseRsp>> callPurchase;
    @Mock
    private Call<OkxRestResponse<OkxStakingRedeemRsp>> callRedeem;
    @Mock
    private Call<OkxRestResponse<OkxStakingCancelRsp>> callCancel;
    @Mock
    private Call<OkxRestResponse<OkxStakingOrder>> callOrder;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OkxEarnService(okxApi);
    }

    @Test
    public void testGetStakingOffers() throws Exception {
        when(okxApi.getStakingOffers(anyMap())).thenReturn(callOffer);
        when(callOffer.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxStakingOffer> response = service.getStakingOffers(Collections.emptyMap());
        assertNotNull(response);
        verify(okxApi).getStakingOffers(anyMap());
    }

    @Test
    public void testPurchaseStaking() throws Exception {
        when(okxApi.purchaseStaking(any(OkxStakingPurchaseReq.class))).thenReturn(callPurchase);
        when(callPurchase.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxStakingPurchaseReq req = new OkxStakingPurchaseReq();
        OkxRestResponse<OkxStakingPurchaseRsp> response = service.purchaseStaking(req);
        assertNotNull(response);
        verify(okxApi).purchaseStaking(req);
    }

    @Test
    public void testRedeemStaking() throws Exception {
        when(okxApi.redeemStaking(any(OkxStakingRedeemReq.class))).thenReturn(callRedeem);
        when(callRedeem.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxStakingRedeemReq req = new OkxStakingRedeemReq();
        OkxRestResponse<OkxStakingRedeemRsp> response = service.redeemStaking(req);
        assertNotNull(response);
        verify(okxApi).redeemStaking(req);
    }

    @Test
    public void testCancelStaking() throws Exception {
        when(okxApi.cancelStaking(any(OkxStakingCancelReq.class))).thenReturn(callCancel);
        when(callCancel.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxStakingCancelReq req = new OkxStakingCancelReq();
        OkxRestResponse<OkxStakingCancelRsp> response = service.cancelStaking(req);
        assertNotNull(response);
        verify(okxApi).cancelStaking(req);
    }

    @Test
    public void testGetActiveStakingOrders() throws Exception {
        when(okxApi.getActiveStakingOrders(anyMap())).thenReturn(callOrder);
        when(callOrder.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxStakingOrder> response = service.getActiveStakingOrders(Collections.emptyMap());
        assertNotNull(response);
        verify(okxApi).getActiveStakingOrders(anyMap());
    }

    @Test
    public void testGetStakingOrdersHistory() throws Exception {
        when(okxApi.getStakingOrdersHistory(anyMap())).thenReturn(callOrder);
        when(callOrder.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxStakingOrder> response = service.getStakingOrdersHistory(Collections.emptyMap());
        assertNotNull(response);
        verify(okxApi).getStakingOrdersHistory(anyMap());
    }
}
