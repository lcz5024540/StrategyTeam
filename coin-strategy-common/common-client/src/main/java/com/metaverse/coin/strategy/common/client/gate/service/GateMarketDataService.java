package com.metaverse.coin.strategy.common.client.gate.service;

import com.metaverse.coin.strategy.common.client.gate.GateApi;

public class GateMarketDataService extends GateService {

    public GateMarketDataService(GateApi gateApi) {
        super(gateApi);
    }

    public String getSpotTickers(String currencyPair) {
        return execute(gateApi.getSpotTickers(currencyPair));
    }

    public String getFuturesTickers(String settle, String contract) {
        return execute(gateApi.getFuturesTickers(settle, contract));
    }
}
