package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring.*;

import java.util.List;
import java.util.Map;

public class OkxRecurringBuyService extends OkxService {

    public OkxRecurringBuyService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<PlaceRecurringBuyAlgoOrderRsp> placeRecurringBuyAlgoOrder(PlaceRecurringBuyAlgoOrderReq req) {
        return execute(okxApi.placeRecurringBuyAlgoOrder(req), "Place recurring buy algo order failed");
    }

    public OkxRestResponse<CancelRecurringBuyAlgoOrderRsp> stopRecurringBuyAlgoOrder(CancelRecurringBuyAlgoOrderReq req) {
        return execute(okxApi.stopRecurringBuyAlgoOrder(req), "Stop recurring buy algo order failed");
    }

    public OkxRestResponse<AmendRecurringBuyAlgoOrderRsp> amendRecurringBuyAlgoOrder(AmendRecurringBuyAlgoOrderReq req) {
        return execute(okxApi.amendRecurringBuyAlgoOrder(req), "Amend recurring buy algo order failed");
    }

    public OkxRestResponse<RecurringAlgoOrder> getRecurringBuyAlgoOrdersPending(Map<String, Object> queryMap) {
        return execute(okxApi.getRecurringBuyAlgoOrdersPending(queryMap), "Get recurring buy algo orders pending failed");
    }

    public OkxRestResponse<RecurringAlgoOrder> getRecurringBuyAlgoOrdersHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getRecurringBuyAlgoOrdersHistory(queryMap), "Get recurring buy algo orders history failed");
    }

    public OkxRestResponse<RecurringAlgoOrder> getRecurringBuyAlgoOrderDetails(Map<String, Object> queryMap) {
        return execute(okxApi.getRecurringBuyAlgoOrderDetails(queryMap), "Get recurring buy algo order details failed");
    }

    public OkxRestResponse<RecurringAlgoSubOrder> getRecurringBuyAlgoSubOrders(Map<String, Object> queryMap) {
        return execute(okxApi.getRecurringBuyAlgoSubOrders(queryMap), "Get recurring buy algo sub orders failed");
    }
}
