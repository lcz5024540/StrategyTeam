package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.algo.OkxAlgoOrder;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import retrofit2.Call;
import retrofit2.Response;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

public class OkxTradeServiceTest {

    @Mock
    private OkxApi okxApi;

    private OkxTradeService okxTradeService;

    @Mock
    private Call<OkxRestResponse<PlaceOrderRsp>> callBatchOrders;
    @Mock
    private Call<OkxRestResponse<CancelOrderRsp>> callCancelBatchOrders;
    @Mock
    private Call<OkxRestResponse<AmendOrderRsp>> callAmendOrder;
    @Mock
    private Call<OkxRestResponse<AmendOrderRsp>> callAmendBatchOrders;
    @Mock
    private Call<OkxRestResponse<Map<String, Object>>> callMap;
    @Mock
    private Call<OkxRestResponse<Object>> callObject;
    @Mock
    private Call<OkxRestResponse<CancelAllAfterRsp>> callCancelAllAfter;
    @Mock
    private Call<OkxRestResponse<OkxAlgoOrder>> callAlgoList;
    @Mock
    private Call<OkxRestResponse<OkxOrder>> callOrderList;
    @Mock
    private Call<OkxRestResponse<OkxTransaction>> callTransactionList;
    @Mock
    private Call<OkxRestResponse<OkxClosePositionRsp>> callClosePositionRspList;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        okxTradeService = new OkxTradeService(okxApi);
    }

    @Test
    public void testBatchOrders() throws Exception {
        when(okxApi.batchOrders(anyList())).thenReturn(callBatchOrders);
        when(callBatchOrders.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        List<PlaceOrderReq> req = Collections.singletonList(new PlaceOrderReq());
        OkxRestResponse<PlaceOrderRsp> response = okxTradeService.batchOrders(req);
        assertNotNull(response);
        verify(okxApi).batchOrders(req);
    }

    @Test
    public void testCancelBatchOrders() throws Exception {
        when(okxApi.cancelBatchOrders(anyList())).thenReturn(callCancelBatchOrders);
        when(callCancelBatchOrders.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        List<CancelOrderReq> req = Collections.singletonList(new CancelOrderReq());
        OkxRestResponse<CancelOrderRsp> response = okxTradeService.cancelBatchOrders(req);
        assertNotNull(response);
        verify(okxApi).cancelBatchOrders(req);
    }

    @Test
    public void testAmendOrder() throws Exception {
        when(okxApi.amendOrder(any(AmendOrderReq.class))).thenReturn(callAmendOrder);
        when(callAmendOrder.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        AmendOrderReq req = new AmendOrderReq();
        OkxRestResponse<AmendOrderRsp> response = okxTradeService.amendOrder(req);
        assertNotNull(response);
        verify(okxApi).amendOrder(req);
    }

    @Test
    public void testAmendBatchOrders() throws Exception {
        when(okxApi.amendBatchOrders(anyList())).thenReturn(callAmendBatchOrders);
        when(callAmendBatchOrders.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        List<AmendOrderReq> req = Collections.singletonList(new AmendOrderReq());
        OkxRestResponse<AmendOrderRsp> response = okxTradeService.amendBatchOrders(req);
        assertNotNull(response);
        verify(okxApi).amendBatchOrders(req);
    }

    @Test
    public void testGetOrdersHistoryArchive() throws Exception {
        when(okxApi.getOrdersHistoryArchive(anyMap())).thenReturn(callOrderList);
        when(callOrderList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> queryMap = new HashMap<>();
        OkxRestResponse<OkxOrder> response = okxTradeService.getOrdersHistoryArchive(queryMap);
        assertNotNull(response);
        verify(okxApi).getOrdersHistoryArchive(queryMap);
    }

    @Test
    public void testGetFills() throws Exception {
        when(okxApi.getFills(anyMap())).thenReturn(callTransactionList);
        when(callTransactionList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> queryMap = new HashMap<>();
        OkxRestResponse<OkxTransaction> response = okxTradeService.getFills(queryMap);
        assertNotNull(response);
        verify(okxApi).getFills(queryMap);
    }

    @Test
    public void testGetFillsHistory() throws Exception {
        when(okxApi.getFillsHistory(anyMap())).thenReturn(callTransactionList);
        when(callTransactionList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> queryMap = new HashMap<>();
        OkxRestResponse<OkxTransaction> response = okxTradeService.getFillsHistory(queryMap);
        assertNotNull(response);
        verify(okxApi).getFillsHistory(queryMap);
    }

    @Test
    public void testMassCancel() throws Exception {
        when(okxApi.massCancel(any(MassCancelOrderReq.class))).thenReturn(callObject);
        when(callObject.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        MassCancelOrderReq req = new MassCancelOrderReq();
        OkxRestResponse<Object> response = okxTradeService.massCancel(req);
        assertNotNull(response);
        verify(okxApi).massCancel(req);
    }

    @Test
    public void testCancelAllAfter() throws Exception {
        when(okxApi.cancelAllAfter(any(CancelAllAfterReq.class))).thenReturn(callCancelAllAfter);
        when(callCancelAllAfter.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        CancelAllAfterReq req = new CancelAllAfterReq();
        OkxRestResponse<CancelAllAfterRsp> response = okxTradeService.cancelAllAfter(req);
        assertNotNull(response);
        verify(okxApi).cancelAllAfter(req);
    }

    @Test
    public void testGetAlgoOrdersPending() throws Exception {
        when(okxApi.getAlgoOrdersPending(anyMap())).thenReturn(callAlgoList);
        when(callAlgoList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> queryMap = new HashMap<>();
        OkxRestResponse<OkxAlgoOrder> response = okxTradeService.getAlgoOrdersPending(queryMap);
        assertNotNull(response);
        verify(okxApi).getAlgoOrdersPending(queryMap);
    }

    @Test
    public void testClosePosition() throws Exception {
        when(okxApi.closePosition(anyMap())).thenReturn(callClosePositionRspList);
        when(callClosePositionRspList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxClosePositionRsp> response = okxTradeService.closePosition("BTC-USDT", "cross", "long");
        assertNotNull(response);
        verify(okxApi).closePosition(anyMap());
    }

    @Test
    public void testGetOrder() throws Exception {
        when(okxApi.getOrder(anyString(), anyString(), anyString())).thenReturn(callOrderList);
        when(callOrderList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        OkxRestResponse<OkxOrder> response = okxTradeService.getOrder("BTC-USDT", "123", "cl123");
        assertNotNull(response);
        verify(okxApi).getOrder("BTC-USDT", "123", "cl123");
    }

    @Test
    public void testGetOrdersHistory() throws Exception {
        when(okxApi.getOrdersHistory(anyMap())).thenReturn(callOrderList);
        when(callOrderList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> queryMap = new HashMap<>();
        OkxRestResponse<OkxOrder> response = okxTradeService.getOrdersHistory(queryMap);
        assertNotNull(response);
        verify(okxApi).getOrdersHistory(queryMap);
    }

    @Test
    public void testGetOrdersPending() throws Exception {
        when(okxApi.getOrdersPending(anyMap())).thenReturn(callOrderList);
        when(callOrderList.execute()).thenReturn(Response.success(new OkxRestResponse<>()));

        Map<String, Object> queryMap = new HashMap<>();
        OkxRestResponse<OkxOrder> response = okxTradeService.getOrdersPending(queryMap);
        assertNotNull(response);
        verify(okxApi).getOrdersPending(queryMap);
    }
}
