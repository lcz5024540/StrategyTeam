package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;

import java.util.List;
import java.util.Map;

public class OkxAlgoTradingService extends OkxService {

    public OkxAlgoTradingService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<Map<String, Object>> placeAlgoOrder(Map<String, Object> req) {
        return execute(okxApi.placeAlgoOrder(req), "Place algo order failed");
    }

    public OkxRestResponse<Map<String, Object>> cancelAlgoOrder(List<Map<String, Object>> req) {
        return execute(okxApi.cancelAlgoOrder(req), "Cancel algo order failed");
    }

    public OkxRestResponse<Map<String, Object>> getAlgoOrdersHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getAlgoOrdersHistory(queryMap), "Get algo orders history failed");
    }
}
