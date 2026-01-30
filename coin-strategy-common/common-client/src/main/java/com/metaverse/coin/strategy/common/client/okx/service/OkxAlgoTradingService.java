package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.algo.*;

import java.util.List;
import java.util.Map;

public class OkxAlgoTradingService extends OkxService {

    public OkxAlgoTradingService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<PlaceAlgoOrderRsp> placeAlgoOrder(Map<String, Object> req) {
        return execute(okxApi.placeAlgoOrder(req), "Place algo order failed");
    }

    public OkxRestResponse<CancelAlgoOrderRsp> cancelAlgoOrder(List<Map<String, Object>> req) {
        return execute(okxApi.cancelAlgoOrder(req), "Cancel algo order failed");
    }

    public OkxRestResponse<AmendAlgoOrderRsp> amendAlgoOrder(List<AmendAlgoOrderReq> req) {
        return execute(okxApi.amendAlgoOrder(req), "Amend algo order failed");
    }

    public OkxRestResponse<OkxAlgoOrder> getAlgoOrder(String algoId, String algoClOrdId) {
        return execute(okxApi.getAlgoOrder(algoId, algoClOrdId), "Get algo order failed");
    }

    public OkxRestResponse<OkxAlgoOrder> getAlgoOrdersPending(Map<String, Object> queryMap) {
        return execute(okxApi.getAlgoOrdersPending(queryMap), "Get algo orders pending failed");
    }

    public OkxRestResponse<OkxAlgoOrder> getAlgoOrdersHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getAlgoOrdersHistory(queryMap), "Get algo orders history failed");
    }
}
