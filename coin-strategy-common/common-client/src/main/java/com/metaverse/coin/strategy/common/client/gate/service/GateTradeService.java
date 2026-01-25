package com.metaverse.coin.strategy.common.client.gate.service;

import com.metaverse.coin.strategy.common.client.gate.GateApi;
import java.util.Map;

public class GateTradeService extends GateService {

    public GateTradeService(GateApi gateApi) {
        super(gateApi);
    }

    public String placeSpotOrder(Map<String, Object> body) {
        return execute(gateApi.placeSpotOrder(body));
    }

    public String cancelSpotOrder(String orderId, String currencyPair) {
        return execute(gateApi.cancelSpotOrder(orderId, currencyPair));
    }

    public String placeFuturesOrder(String settle, Map<String, Object> body) {
        return execute(gateApi.placeFuturesOrder(settle, body));
    }

    public String cancelFuturesOrder(String settle, String orderId) {
        return execute(gateApi.cancelFuturesOrder(settle, orderId));
    }
}
