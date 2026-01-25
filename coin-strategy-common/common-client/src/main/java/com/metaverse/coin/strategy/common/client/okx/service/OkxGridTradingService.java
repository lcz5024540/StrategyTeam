package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.PlaceGridAlgoOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.PlaceGridAlgoOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.StopGridAlgoOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.StopGridAlgoOrderRsp;

import java.util.Map;

public class OkxGridTradingService extends OkxService {

    public OkxGridTradingService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<PlaceGridAlgoOrderRsp> placeGridAlgoOrder(PlaceGridAlgoOrderReq req) {
        return execute(okxApi.placeGridAlgoOrder(req), "Place grid algo order failed");
    }

    public OkxRestResponse<StopGridAlgoOrderRsp> stopGridAlgoOrder(StopGridAlgoOrderReq req) {
        return execute(okxApi.stopGridAlgoOrder(req), "Stop grid algo order failed");
    }

    public OkxRestResponse<Map<String, Object>> amendGridAlgoOrder(Map<String, Object> req) {
        return execute(okxApi.amendGridAlgoOrder(req), "Amend grid algo order failed");
    }
}
