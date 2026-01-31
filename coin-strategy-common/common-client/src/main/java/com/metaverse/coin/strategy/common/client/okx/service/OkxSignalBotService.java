package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal.*;

import java.util.List;
import java.util.Map;

public class OkxSignalBotService extends OkxService {

    public OkxSignalBotService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<PlaceSignalAlgoOrderRsp> placeSignalAlgoOrder(PlaceSignalAlgoOrderReq req) {
        return execute(okxApi.placeSignalAlgoOrder(req), "Place signal algo order failed");
    }

    public OkxRestResponse<PlaceSignalAlgoSubOrderRsp> placeSignalAlgoSubOrder(PlaceSignalAlgoSubOrderReq req) {
        return execute(okxApi.placeSignalAlgoSubOrder(req), "Place signal algo sub order failed");
    }

    public OkxRestResponse<CancelSignalAlgoOrderRsp> cancelSignalAlgoOrder(CancelSignalAlgoOrderReq req) {
        return execute(okxApi.cancelSignalAlgoOrder(req), "Cancel signal algo order failed");
    }

    public OkxRestResponse<AmendSignalAlgoOrderRsp> amendSignalAlgoOrder(AmendSignalAlgoOrderReq req) {
        return execute(okxApi.amendSignalAlgoOrder(req), "Amend signal algo order failed");
    }

    public OkxRestResponse<SignalAlgoOrder> getSignalAlgoOrderDetails(Map<String, Object> queryMap) {
        return execute(okxApi.getSignalAlgoOrderDetails(queryMap), "Get signal algo order details failed");
    }

    public OkxRestResponse<SignalAlgoOrder> getSignalAlgoOrdersHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getSignalAlgoOrdersHistory(queryMap), "Get signal algo orders history failed");
    }

    public OkxRestResponse<SignalAlgoOrder> getSignalAlgoOrdersPending(Map<String, Object> queryMap) {
        return execute(okxApi.getSignalAlgoOrdersPending(queryMap), "Get signal algo orders pending failed");
    }

    public OkxRestResponse<SignalAlgoSubOrder> getSignalAlgoSubOrders(Map<String, Object> queryMap) {
        return execute(okxApi.getSignalAlgoSubOrders(queryMap), "Get signal algo sub orders failed");
    }

    public OkxRestResponse<SignalAlgoPosition> getSignalAlgoPositions(Map<String, Object> queryMap) {
        return execute(okxApi.getSignalAlgoPositions(queryMap), "Get signal algo positions failed");
    }

    public OkxRestResponse<CloseSignalPositionRsp> closeSignalPosition(CloseSignalPositionReq req) {
        return execute(okxApi.closeSignalPosition(req), "Close signal position failed");
    }

    public OkxRestResponse<ChangeSignalMarginBalanceRsp> changeSignalMarginBalance(ChangeSignalMarginBalanceReq req) {
        return execute(okxApi.changeSignalMarginBalance(req), "Change signal margin balance failed");
    }
}
