package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.savings.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OkxSavingsServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxSavingsService service;

    @Mock
    private Call<OkxRestResponse<OkxSavingsBalance>> callBalance;
    @Mock
    private Call<OkxRestResponse<OkxSavingsPurchaseRedemptRsp>> callPurchaseRedempt;
    @Mock
    private Call<OkxRestResponse<OkxSavingsSetLendingRateRsp>> callSetRate;
    @Mock
    private Call<OkxRestResponse<OkxSavingsLendingHistory>> callLendingHistory;
    @Mock
    private Call<OkxRestResponse<OkxSavingsLendingRateSummary>> callRateSummary;
    @Mock
    private Call<OkxRestResponse<OkxSavingsLendingRateHistory>> callRateHistory;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OkxSavingsService(okxApi);
    }

    @Test
    public void testGetSavingsBalance() throws Exception {
        when(okxApi.getSavingsBalance(anyString())).thenReturn(callBalance);
        when(callBalance.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxSavingsBalance> response = service.getSavingsBalance("BTC");
        assertNotNull(response);
        verify(okxApi).getSavingsBalance("BTC");
    }

    @Test
    public void testPurchaseRedempt() throws Exception {
        when(okxApi.savingsPurchaseRedempt(any(OkxSavingsPurchaseRedemptReq.class))).thenReturn(callPurchaseRedempt);
        when(callPurchaseRedempt.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxSavingsPurchaseRedemptRsp> response = service.purchaseRedempt("BTC", BigDecimal.ONE, "purchase", BigDecimal.valueOf(0.01));
        assertNotNull(response);
        verify(okxApi).savingsPurchaseRedempt(any(OkxSavingsPurchaseRedemptReq.class));
    }

    @Test
    public void testSetLendingRate() throws Exception {
        when(okxApi.setSavingsLendingRate(any(OkxSavingsSetLendingRateReq.class))).thenReturn(callSetRate);
        when(callSetRate.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxSavingsSetLendingRateRsp> response = service.setLendingRate("BTC", BigDecimal.valueOf(0.01));
        assertNotNull(response);
        verify(okxApi).setSavingsLendingRate(any(OkxSavingsSetLendingRateReq.class));
    }

    @Test
    public void testGetLendingHistory() throws Exception {
        when(okxApi.getSavingsLendingHistory(anyMap())).thenReturn(callLendingHistory);
        when(callLendingHistory.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxSavingsLendingHistory> response = service.getLendingHistory(Collections.emptyMap());
        assertNotNull(response);
        verify(okxApi).getSavingsLendingHistory(anyMap());
    }

    @Test
    public void testGetLendingRateSummary() throws Exception {
        when(okxApi.getSavingsLendingRateSummary(anyString())).thenReturn(callRateSummary);
        when(callRateSummary.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxSavingsLendingRateSummary> response = service.getLendingRateSummary("BTC");
        assertNotNull(response);
        verify(okxApi).getSavingsLendingRateSummary("BTC");
    }

    @Test
    public void testGetLendingRateHistory() throws Exception {
        when(okxApi.getSavingsLendingRateHistory(anyMap())).thenReturn(callRateHistory);
        when(callRateHistory.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxSavingsLendingRateHistory> response = service.getLendingRateHistory(Collections.emptyMap());
        assertNotNull(response);
        verify(okxApi).getSavingsLendingRateHistory(anyMap());
    }
}
